#ifndef PROXYDROID_BENCH_COMMON_H
#define PROXYDROID_BENCH_COMMON_H
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <openssl/ssl.h>
#include <openssl/err.h>

#define IO_TIMEOUT_SECONDS 10

static double now_seconds(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec + time.tv_nsec / 1e9;
}
static void configure_socket(int fd) {
    int yes = 1;
    struct timeval timeout = {IO_TIMEOUT_SECONDS, 0};
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}
static int connect_ip(const char *ip, int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons(port)};
    if (fd < 0) return -1;
    configure_socket(fd);
    if (inet_pton(AF_INET, ip, &address.sin_addr) != 1 ||
        connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        fprintf(stderr, "TCP connect to %s:%d failed: %s\n", ip, port, strerror(errno));
        close(fd); return -1;
    }
    return fd;
}
static int write_all(int fd, const void *bytes, size_t length) {
    const char *data = bytes;
    while (length) {
        ssize_t n = write(fd, data, length);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        data += n; length -= (size_t)n;
    }
    return 0;
}
static int ssl_write_all(SSL *ssl, const void *bytes, int length) {
    const char *data = bytes;
    while (length) {
        int n = SSL_write(ssl, data, length);
        if (n <= 0) {
            int error = SSL_get_error(ssl, n), saved_errno = errno;
            fprintf(stderr, "TLS write failed: SSL error %d, errno %d (%s)\n",
                    error, saved_errno, strerror(saved_errno));
            ERR_print_errors_fp(stderr);
            return -1;
        }
        data += n; length -= n;
    }
    return 0;
}
#endif
