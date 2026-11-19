/* Native driver. Forwarding runs the real engine in a child, so its CPU/RSS
 * exclude the load generator. No app, profile, routing or firewall is changed. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include "common.h"
#include "engine.h"
#include <fcntl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <math.h>
#include <poll.h>
#ifdef __linux__
#include <sched.h>
#endif

#define BLOCK 65536
#define STREAM_WINDOW (4 * BLOCK)
#define MAX_SAMPLES 200000
static unsigned char payload[BLOCK];
static double samples[MAX_SAMPLES];
static size_t sample_count;
static pthread_mutex_t sample_lock = PTHREAD_MUTEX_INITIALIZER;
static double deadline;
static int local_port, short_requests;
static const char *connect_host = "127.0.0.1";
struct worker { uint64_t sent, received, errors, requests, peak_inflight; };
struct engine_metrics { double cpu; long rss_kib; };

static int engine_affinity(void) {
    const char *value = getenv("PROXYDROID_BENCH_ENGINE_CPU_MASK");
    if (!value) return 0;
#ifdef __linux__
    char *end;
    errno = 0;
    unsigned long long mask = strtoull(value, &end, 16);
    if (!*value || *end || errno || !mask) {
        fprintf(stderr, "Invalid engine CPU mask\n"); return -1;
    }
    cpu_set_t selected;
    CPU_ZERO(&selected);
    for (int cpu = 0; cpu < 64; ++cpu) if (mask & (1ULL << cpu)) CPU_SET(cpu, &selected);
    if (sched_setaffinity(0, sizeof(selected), &selected)) { perror("engine CPU affinity"); return -1; }
    return 0;
#else
    fprintf(stderr, "Engine CPU affinity requires Linux/Android\n"); return -1;
#endif
}

static void stream_error(struct worker *worker, int fd, const char *operation, int error) {
    fprintf(stderr, "Forward stream fd=%d %s: %s; sent=%llu received=%llu\n", fd,
            operation, error ? strerror(error) : "invalid stream",
            (unsigned long long)worker->sent, (unsigned long long)worker->received);
    ++worker->errors;
}

static double cpu_seconds(const struct rusage *usage) {
    return usage->ru_utime.tv_sec + usage->ru_utime.tv_usec/1e6 +
           usage->ru_stime.tv_sec + usage->ru_stime.tv_usec/1e6;
}
static long rss_kib(const struct rusage *usage) {
#ifdef __APPLE__
    return usage->ru_maxrss / 1024;
#else
    return usage->ru_maxrss;
#endif
}
static void sample(double milliseconds) {
    pthread_mutex_lock(&sample_lock);
    if (sample_count < MAX_SAMPLES) samples[sample_count++] = milliseconds;
    pthread_mutex_unlock(&sample_lock);
}
static int compare_double(const void *a, const void *b) {
    double x=*(const double *)a, y=*(const double *)b;
    return (x>y) - (x<y);
}
static int verify_bytes(const unsigned char *data, size_t count, uint64_t offset) {
    for (size_t i=0; i<count; ++i) if (data[i] != payload[(offset+i)%BLOCK]) return -1;
    return 0;
}
static void forward_stream(int fd, struct worker *worker) {
    unsigned char data[BLOCK];
    double idle_deadline = now_seconds() + IO_TIMEOUT_SECONDS;
    int sending = 1;
    for (;;) {
        double now = now_seconds();
        if (sending && now >= deadline) {
            if (shutdown(fd, SHUT_WR)) { stream_error(worker, fd, "half-close", errno); break; }
            sending = 0;
        }
        if (now >= idle_deadline) { stream_error(worker, fd, "I/O timeout", ETIMEDOUT); break; }
        uint64_t inflight = worker->sent - worker->received;
        struct pollfd event = {.fd = fd, .events = POLLIN};
        if (sending && inflight < STREAM_WINDOW) event.events |= POLLOUT;
        double until = sending ? fmin(deadline, idle_deadline) : idle_deadline;
        int ready = poll(&event, 1, (int)ceil((until-now)*1000));
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) { stream_error(worker, fd, "poll", errno); break; }
        if (!ready) continue;
        if (event.revents & POLLNVAL) { stream_error(worker, fd, "poll", EBADF); break; }
        if (event.revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = recv(fd, data, sizeof(data), MSG_DONTWAIT);
            if (n < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                stream_error(worker, fd, "read", errno); break;
            }
            if (!n) {
                if (sending) stream_error(worker, fd, "premature EOF", 0);
                break;
            }
            if (n > 0) {
                if ((uint64_t)n > worker->sent-worker->received || verify_bytes(data, n, worker->received)) {
                    stream_error(worker, fd, "payload mismatch", 0); break;
                }
                worker->received += n;
                idle_deadline = now_seconds() + IO_TIMEOUT_SECONDS;
            }
        }
        if ((event.revents & POLLOUT) && sending && now_seconds() < deadline) {
            size_t offset = worker->sent % BLOCK, count = BLOCK-offset;
            uint64_t credit = STREAM_WINDOW - (worker->sent-worker->received);
            if (count > credit) count = credit;
            ssize_t n = send(fd, payload+offset, count, MSG_DONTWAIT);
            if (n < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                stream_error(worker, fd, "write", errno); break;
            }
            if (n > 0) {
                worker->sent += n;
                inflight = worker->sent-worker->received;
                if (inflight > worker->peak_inflight) worker->peak_inflight = inflight;
                idle_deadline = now_seconds() + IO_TIMEOUT_SECONDS;
            }
        }
    }
    ++worker->requests;
    if (worker->sent != worker->received) stream_error(worker, fd, "byte count mismatch", 0);
}
static void *run_worker(void *argument) {
    struct worker *worker = argument;
    do {
        double started = now_seconds();
        int fd = connect_ip(connect_host, local_port);
        if (fd < 0) { ++worker->errors; break; }
        if (short_requests) {
            unsigned char data[4096];
            size_t used = 0;
            if (write_all(fd, payload, sizeof(data))) ++worker->errors;
            else {
                while (used < sizeof(data)) {
                    ssize_t n = read(fd, data+used, sizeof(data)-used);
                    if (n < 0 && errno == EINTR) continue;
                    if (n <= 0) { ++worker->errors; break; }
                    used += n;
                }
                if (used == sizeof(data) && !verify_bytes(data, used, 0)) {
                    worker->sent += used; worker->received += used; ++worker->requests;
                    sample((now_seconds()-started)*1000);
                } else if (used == sizeof(data)) ++worker->errors;
            }
        } else {
            forward_stream(fd, worker);
        }
        close(fd);
    } while (short_requests && now_seconds() < deadline && !worker->errors);
    return NULL;
}
static int temporary_config(const char *content) {
    char path[4096];
    const char *temporary = getenv("TMPDIR");
    snprintf(path, sizeof(path), "%s/pdroid-bench-XXXXXX", temporary ? temporary : "/tmp");
    int fd = mkstemp(path);
    if (fd < 0) return -1;
    unlink(path);
    if (write_all(fd, content, strlen(content)) || lseek(fd, 0, SEEK_SET) < 0) { close(fd); return -1; }
    return fd;
}
static int choose_port(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {.sin_family=AF_INET, .sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    socklen_t length = sizeof(address);
    if (fd < 0) return -1;
    int result = bind(fd, (struct sockaddr *)&address, sizeof(address)) ||
                 getsockname(fd, (struct sockaddr *)&address, &length);
    close(fd);
    return result ? -1 : ntohs(address.sin_port);
}
static pid_t start_engine(const char *ip, int port, const char *ca, int ntlm, int *control, int *metrics) {
    int ready[2], stop[2], result[2];
    local_port = choose_port();
    if (local_port < 0 || pipe(ready) || pipe(stop) || pipe(result)) return -1;
    pid_t child = fork();
    if (child < 0) return -1;
    if (!child) {
        close(ready[0]); close(stop[1]); close(result[0]);
        char config[4096], credentials[1024];
        snprintf(config, sizeof(config),
            "base { redirector = generic; daemon = off; log_info = off; }\n"
            "redsocks { local_ip = 127.0.0.1; local_port = %d; ip = %s; port = %d;"
            "type = http-connect; splice = off; tls = on; tls_host = \"bench.proxy\"; %s }\n",
            local_port, ip, port, ntlm ? "cntlm_context = \"default\";" : "");
        snprintf(credentials, sizeof(credentials),
            "Proxy %s:%d\nUsername User\nDomain Domain\nPassword Password\nAuth NTLMv2\n", ip, port);
        int config_fd=temporary_config(config), trust_fd=open(ca,O_RDONLY);
        int cntlm_fd=ntlm ? temporary_config(credentials) : -1;
        redsocks_engine *engine = redsocks_engine_create();
        redsocks_engine_options options = {config_fd, trust_fd, cntlm_fd, 0};
        int status = -1;
        struct rusage before, after;
        getrusage(RUSAGE_SELF, &before);
        if (!engine_affinity() && engine && config_fd>=0 && trust_fd>=0 && (!ntlm || cntlm_fd>=0))
            status = redsocks_engine_start(engine, &options);
        if (config_fd>=0) close(config_fd);
        if (trust_fd>=0) close(trust_fd);
        if (cntlm_fd>=0) close(cntlm_fd);
        write_all(ready[1], &status, sizeof(status)); close(ready[1]);
        char byte;
        if (!status) { while (read(stop[0], &byte, 1)<0 && errno==EINTR) {} }
        if (engine) {
            redsocks_engine_request_stop(engine); redsocks_engine_join(engine); redsocks_engine_destroy(engine);
        }
        getrusage(RUSAGE_SELF, &after);
        struct engine_metrics value = {cpu_seconds(&after)-cpu_seconds(&before), rss_kib(&after)};
        write_all(result[1], &value, sizeof(value));
        _exit(status ? 1 : 0);
    }
    close(ready[1]); close(stop[0]); close(result[1]);
    int status = -1;
    ssize_t n = read(ready[0], &status, sizeof(status)); close(ready[0]);
    if (n != sizeof(status) || status) {
        close(stop[1]); close(result[0]); waitpid(child, NULL, 0); return -1;
    }
    *control=stop[1]; *metrics=result[0];
    return child;
}
static int handshakes(const char *ip, int port, const char *ca, int version, int count, int resume,
                      uint64_t *errors, int *reused, char *cipher, size_t cipher_size) {
    SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
    if (!ctx || !SSL_CTX_set_min_proto_version(ctx, version) ||
        !SSL_CTX_set_max_proto_version(ctx, version) || !SSL_CTX_load_verify_locations(ctx, ca, NULL) ||
        !SSL_CTX_set1_groups_list(ctx, "X25519:P-256")) {
        fprintf(stderr, "TLS client context setup failed\n");
        ERR_print_errors_fp(stderr); SSL_CTX_free(ctx); return -1;
    }
    SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, NULL);
    SSL_SESSION *session = NULL;
    for (int i=0; i<count+(resume?1:0); ++i) {
        int fd = connect_ip(ip, port);
        SSL *ssl = SSL_new(ctx);
        int success = fd >= 0 && ssl && SSL_set_fd(ssl, fd) == 1 &&
                      SSL_set_tlsext_host_name(ssl, "bench.proxy") == 1 && SSL_set1_host(ssl, "bench.proxy") == 1;
        if (success && resume && i) success = session && SSL_set_session(ssl, session) == 1;
        double started = now_seconds();
        if (success) {
            int result = SSL_connect(ssl);
            if (result != 1) {
                fprintf(stderr, "TLS handshake failed: SSL error %d, errno %d\n", SSL_get_error(ssl, result), errno);
                ERR_print_errors_fp(stderr); success = 0;
            }
        }
        double elapsed = (now_seconds()-started)*1000;
        if (success) {
            snprintf(cipher, cipher_size, "%s", SSL_get_cipher(ssl));
            int was_reused = SSL_session_reused(ssl);
            if (i || !resume) { sample(elapsed); *reused += was_reused; }
            if (resume && i && !was_reused) {
                fprintf(stderr, "Server did not resume the offered TLS session\n"); success = 0;
            }
            unsigned char pong[4]; int used=0;
            if (ssl_write_all(ssl, "PDROID-PING\n", 12)) success=0;
            while (success && used<4) {
                int n=SSL_read(ssl,pong+used,4-used);
                if (n<=0) success=0; else used+=n;
            }
            if (success && memcmp(pong,"PONG",4)) success=0;
            if (success && resume) {
                SSL_SESSION *next=SSL_get1_session(ssl);
                SSL_SESSION_free(session); session=next;
            }
        }
        if (!success) ++*errors;
        if (ssl) { SSL_shutdown(ssl); SSL_free(ssl); }
        if (fd>=0) close(fd);
        if (!success) break;
    }
    SSL_SESSION_free(session); SSL_CTX_free(ctx);
    return 0;
}
int main(int argc, char **argv) {
    if (argc != 10) {
        fprintf(stderr, "Usage: %s MODE IPv4 PORT CA TLS_VERSION CONCURRENCY SECONDS ITERATIONS AUTH\nMODE=handshake|resume|short|forward AUTH=none|ntlm\n", argv[0]); return 2;
    }
    signal(SIGPIPE,SIG_IGN);
    const char *mode=argv[1];
    int port=atoi(argv[3]), version=!strcmp(argv[5],"1.3") ? TLS1_3_VERSION : TLS1_2_VERSION;
    int concurrency=atoi(argv[6]), duration=atoi(argv[7]), iterations=atoi(argv[8]);
    int ntlm=!strcmp(argv[9],"ntlm");
    if (port<1 || port>65535 || concurrency<1 || concurrency>64 || duration<1 || duration>3600 || iterations<1 || iterations>MAX_SAMPLES) return 2;
    for (size_t i=0;i<BLOCK;++i) payload[i]=i%251;
    uint64_t errors=0,sent=0,received=0,requests=0,peak_inflight=0;
    int reused=0; char cipher[128]="";
    struct rusage before,after; struct engine_metrics metrics={0};
    getrusage(RUSAGE_SELF,&before);
    double started=now_seconds();
    int direct=!strcmp(mode,"handshake") || !strcmp(mode,"resume");
    if (direct) {
        if (handshakes(argv[2],port,argv[4],version,iterations,!strcmp(mode,"resume"),&errors,&reused,cipher,sizeof(cipher))) ++errors;
        requests=sample_count;
        getrusage(RUSAGE_SELF,&after);
        metrics=(struct engine_metrics){cpu_seconds(&after)-cpu_seconds(&before),rss_kib(&after)};
    } else {
        int network_only = !strcmp(mode,"network");
        if (strcmp(mode,"short") && strcmp(mode,"forward") && !network_only) return 2;
        short_requests=!strcmp(mode,"short");
        int control=-1,results=-1;
        pid_t engine=0;
        if (network_only) { connect_host=argv[2]; local_port=port; }
        else engine=start_engine(argv[2],port,argv[4],ntlm,&control,&results);
        if (engine<0) { fprintf(stderr,"Benchmark engine failed to start\n"); return 1; }
        pthread_t threads[64]; struct worker workers[64]={0}; int created=0;
        started=now_seconds(); deadline=started+duration;
        for (;created<concurrency;++created)
            if (pthread_create(&threads[created],NULL,run_worker,&workers[created])) { ++errors; break; }
        for (int i=0;i<created;++i) {
            pthread_join(threads[i],NULL);
            errors+=workers[i].errors; sent+=workers[i].sent; received+=workers[i].received; requests+=workers[i].requests;
            if (workers[i].peak_inflight > peak_inflight) peak_inflight = workers[i].peak_inflight;
        }
        if (network_only) {
            getrusage(RUSAGE_SELF,&after);
            metrics=(struct engine_metrics){cpu_seconds(&after)-cpu_seconds(&before),rss_kib(&after)};
        } else {
            write_all(control,"x",1); close(control);
            if (read(results,&metrics,sizeof(metrics))!=sizeof(metrics)) ++errors;
            close(results); int status; waitpid(engine,&status,0);
            if (!WIFEXITED(status) || WEXITSTATUS(status)) ++errors;
        }
    }
    double elapsed=now_seconds()-started;
    qsort(samples,sample_count,sizeof(double),compare_double);
    double p50=sample_count ? samples[(sample_count-1)/2] : 0;
    double p95=sample_count ? samples[(size_t)((sample_count-1)*0.95)] : 0;
    double gib=(sent+received)/(1024.0*1024*1024);
    printf("{\"valid\":%s,\"mode\":\"%s\",\"seconds\":%.6f,\"requests\":%llu,\"errors\":%llu,"
           "\"sent_bytes\":%llu,\"received_bytes\":%llu,\"mbps_bidirectional\":%.6f,\"cpu_seconds\":%.6f,"
           "\"cpu_seconds_per_gib\":%.6f,\"peak_rss_kib\":%ld,\"p50_ms\":%.6f,\"p95_ms\":%.6f,"
           "\"reused\":%d,\"cipher\":\"%s\",\"stream_window_bytes\":%d,\"peak_inflight_bytes\":%llu,\"build_info\":\"%s\"}\n",
           errors || !requests ? "false":"true",mode,elapsed,(unsigned long long)requests,(unsigned long long)errors,
           (unsigned long long)sent,(unsigned long long)received,(sent+received)*8/elapsed/1e6,metrics.cpu,
           gib ? metrics.cpu/gib:0,metrics.rss_kib,p50,p95,reused,cipher,STREAM_WINDOW,
           (unsigned long long)peak_inflight,redsocks_engine_build_info());
    return errors || !requests ? 1:0;
}
