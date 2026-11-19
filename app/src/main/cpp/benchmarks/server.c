/* Controlled reference proxy: eight fixed TLS/cipher/auth listeners, never a
 * general-purpose proxy. It echoes payloads and authenticates public test creds. */
#include "common.h"
#include <openssl/hmac.h>
#include <poll.h>

struct connection { int fd, ntlm; SSL_CTX *ctx; };
static int trace_connections;
static const unsigned char ntlm_key[16] = {
    0xf3,0x8e,0xfe,0xa4,0x8a,0xda,0x6a,0xfa,0xa9,0x5a,0xe4,0x46,0x69,0xe5,0x63,0x4b};
static uint16_t le16(const unsigned char *p) { return p[0] | (p[1] << 8); }
static uint32_t le32(const unsigned char *p) { return le16(p) | ((uint32_t)le16(p+2) << 16); }

static int read_header(SSL *ssl, char *buffer, size_t capacity) {
    size_t used = 0;
    while (used + 1 < capacity) {
        if (SSL_read(ssl, buffer + used, 1) != 1) return -1;
        buffer[++used] = 0;
        if (used >= 4 && !memcmp(buffer + used - 4, "\r\n\r\n", 4)) return 0;
        if (!strcmp(buffer, "PDROID-PING\n")) return 1;
    }
    return -1;
}
static int decode_auth(char *header, unsigned char *out) {
    char *p = strcasestr(header, "\r\nProxy-Authorization: NTLM ");
    if (!p) return -1;
    p += strlen("\r\nProxy-Authorization: NTLM ");
    char *end = strstr(p, "\r\n");
    if (!end || end-p > 8192 || (end-p) % 4) return -1;
    int length = EVP_DecodeBlock(out, (unsigned char *)p, (int)(end-p));
    if (length >= 0 && end > p && end[-1] == '=') --length;
    if (length >= 0 && end-p > 1 && end[-2] == '=') --length;
    return length;
}
static int authenticate(SSL *ssl, char *header) {
    unsigned char message[8192];
    int length = decode_auth(header, message);
    if (length < 12 || memcmp(message, "NTLMSSP\0\1\0\0\0", 12)) return -1;
    /* Type 2: flags match the engine tests; target info is Domain + terminator. */
    unsigned char challenge[68] = {0};
    memcpy(challenge, "NTLMSSP\0\2\0\0\0", 12);
    challenge[20]=1; challenge[21]=0x82; challenge[22]=0x88;
    memcpy(challenge+24, "12345678", 8);
    challenge[40]=20; challenge[42]=20; challenge[44]=48;
    challenge[48]=2; challenge[50]=12;
    memcpy(challenge+52, "D\0o\0m\0a\0i\0n\0", 12);
    unsigned char encoded[128];
    EVP_EncodeBlock(encoded, challenge, sizeof(challenge));
    char response[512];
    int n = snprintf(response, sizeof(response), "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: NTLM %s\r\nContent-Length: 0\r\n\r\n", encoded);
    if (ssl_write_all(ssl, response, n) || read_header(ssl, header, 65536)) return -1;
    length = decode_auth(header, message);
    if (length < 64 || memcmp(message, "NTLMSSP\0\3\0\0\0", 12)) return -1;
    uint16_t count = le16(message+20);
    uint32_t offset = le32(message+24);
    if (count < 24 || offset > (uint32_t)length || count > length-offset) return -1;
    unsigned char input[8192], digest[EVP_MAX_MD_SIZE];
    unsigned int digest_size;
    memcpy(input, "12345678", 8);
    memcpy(input+8, message+offset+16, count-16);
    if (!HMAC(EVP_md5(), ntlm_key, sizeof(ntlm_key), input, count-8, digest, &digest_size) ||
        CRYPTO_memcmp(digest, message+offset, 16)) return -1;
    return 0;
}
static void *serve(void *argument) {
    struct connection connection = *(struct connection *)argument;
    free(argument);
    if (!connection.ctx) {
        char data[65536];
        ssize_t count;
        while ((count = recv(connection.fd, data, sizeof(data), 0)) > 0)
            if (write_all(connection.fd, data, count)) break;
        close(connection.fd);
        return NULL;
    }
    SSL *ssl = SSL_new(connection.ctx);
    char *header = malloc(65536);
    uint64_t received = 0, sent = 0;
    if (!ssl || !header) goto done;
    SSL_set_fd(ssl, connection.fd);
    if (SSL_accept(ssl) != 1) goto done;
    if (trace_connections) fprintf(stderr, "LAN_TLS_ACCEPT fd=%d version=%s cipher=%s ntlm=%d\n",
                                   connection.fd, SSL_get_version(ssl), SSL_get_cipher(ssl), connection.ntlm);
    int request = read_header(ssl, header, 65536);
    if (request == 1) {
        ssl_write_all(ssl, "PONG", 4); goto done;
    }
    if (request || (connection.ntlm && authenticate(ssl, header))) goto done;
    if (strncmp(header, "CONNECT ", 8)) goto done;
    const char response[] = "HTTP/1.1 200 Connection established\r\n\r\n";
    if (ssl_write_all(ssl, response, sizeof(response)-1)) goto done;
    int n;
    for (;;) {
        n = SSL_read(ssl, header, 65536);
        if (n <= 0) {
            int error = SSL_get_error(ssl, n), saved_errno = errno;
            if (error != SSL_ERROR_ZERO_RETURN) {
                fprintf(stderr, "Echo fd=%d TLS read failed: SSL error %d, errno %d (%s); received=%llu sent=%llu\n",
                        connection.fd, error, saved_errno, strerror(saved_errno),
                        (unsigned long long)received, (unsigned long long)sent);
                ERR_print_errors_fp(stderr);
            }
            break;
        }
        received += n;
        if (ssl_write_all(ssl, header, n)) {
            fprintf(stderr, "Echo fd=%d TLS write failed; received=%llu sent=%llu\n", connection.fd,
                    (unsigned long long)received, (unsigned long long)sent);
            break;
        }
        sent += n;
    }
done:
    if (trace_connections) fprintf(stderr, "LAN_ECHO_DONE fd=%d received=%llu sent=%llu shutdown=%d\n",
                                   connection.fd, (unsigned long long)received, (unsigned long long)sent,
                                   ssl ? SSL_get_shutdown(ssl) : -1);
    if (ssl) { SSL_shutdown(ssl); SSL_free(ssl); }
    free(header);
    close(connection.fd);
    return NULL;
}
int main(int argc, char **argv) {
    if (argc != 5 && argc != 6) {
        fprintf(stderr, "Usage: %s BIND_IPV4 BASE_PORT CERT_PEM KEY_PEM [SINGLE_PROFILE_0_TO_7|network]\n", argv[0]); return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    trace_connections = getenv("PROXYDROID_BENCH_TRACE") != NULL;
    SSL_CTX *contexts[4] = {0};
    struct pollfd listeners[8];
    for (int i = 0; i < 8; ++i) listeners[i] = (struct pollfd){.fd=-1};
    int base = atoi(argv[2]);
    int profile = -1;
    int network_only = argc == 6 && !strcmp(argv[5], "network");
    if (network_only) profile = 0;
    else if (argc == 6) {
        char *end;
        long selected = strtol(argv[5], &end, 10);
        if (!argv[5][0] || *end || selected < 0 || selected > 7) return 2;
        profile = (int)selected;
    }
    if (base < 1024 || base > 65527) return 2;
    for (int i = 0; i < 4; ++i) {
        SSL_CTX *ctx = contexts[i] = SSL_CTX_new(TLS_server_method());
        int version = i < 2 ? TLS1_2_VERSION : TLS1_3_VERSION;
        const char *cipher = i%2 ? "ECDHE-RSA-CHACHA20-POLY1305" : "ECDHE-RSA-AES128-GCM-SHA256";
        const char *suite = i%2 ? "TLS_CHACHA20_POLY1305_SHA256" : "TLS_AES_128_GCM_SHA256";
        if (!ctx || SSL_CTX_set_min_proto_version(ctx, version) != 1 ||
            SSL_CTX_set_max_proto_version(ctx, version) != 1 ||
            SSL_CTX_use_certificate_chain_file(ctx, argv[3]) != 1 ||
            SSL_CTX_use_PrivateKey_file(ctx, argv[4], SSL_FILETYPE_PEM) != 1 ||
            SSL_CTX_set_cipher_list(ctx, cipher) != 1 || SSL_CTX_set_ciphersuites(ctx, suite) != 1 ||
            SSL_CTX_set1_groups_list(ctx, "X25519") != 1) { ERR_print_errors_fp(stderr); return 1; }
        SSL_CTX_set_session_id_context(ctx, (unsigned char *)"pdroid-bench", 12);
    }
    for (int i = 0; i < 8; ++i) {
        if (profile >= 0 && i != profile) continue;
        int fd = socket(AF_INET, SOCK_STREAM, 0), yes = 1;
        struct sockaddr_in address = {.sin_family=AF_INET, .sin_port=htons(base+(profile < 0 ? i : 0))};
        if (fd < 0 || inet_pton(AF_INET, argv[1], &address.sin_addr) != 1) return 2;
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        if (bind(fd, (struct sockaddr *)&address, sizeof(address)) || listen(fd, 256)) { perror("listener"); return 1; }
        listeners[i] = (struct pollfd){.fd=fd, .events=POLLIN};
    }
    printf("{\"ready\":true,\"base_port\":%d,\"ports\":%d,\"profile\":%d,\"network_only\":%s,\"group\":\"X25519\",\"certificate\":\"RSA-2048\",\"library\":\"%s\",\"pid\":%ld,\"clock_ticks\":%ld,\"page_size\":%ld}\n",
           base, profile < 0 ? 8 : 1, profile, network_only ? "true" : "false", OpenSSL_version(OPENSSL_VERSION),
           (long)getpid(), sysconf(_SC_CLK_TCK), sysconf(_SC_PAGESIZE));
    fflush(stdout);
    while (poll(listeners, 8, -1) >= 0) for (int i = 0; i < 8; ++i) {
        if (!(listeners[i].revents & POLLIN)) continue;
        int fd = accept(listeners[i].fd, NULL, NULL);
        if (fd < 0) continue;
        configure_socket(fd);
        struct connection *connection = malloc(sizeof(*connection));
        if (!connection) { close(fd); continue; }
        *connection = (struct connection){fd, i%2, network_only ? NULL : contexts[i/2]};
        pthread_t thread;
        if (pthread_create(&thread, NULL, serve, connection)) { close(fd); free(connection); }
        else pthread_detach(thread);
    }
    return 1;
}
