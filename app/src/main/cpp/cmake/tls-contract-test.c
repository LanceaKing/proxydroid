#include <event2/event.h>
#include <event2/bufferevent_ssl.h>
#include <event2/util.h>
#include <openssl/bio.h>
#include <openssl/crypto.h>
#include <openssl/rand.h>
#include <stdio.h>
#include <string.h>

#define REQUIRE(x) do { if (!(x)) { fprintf(stderr, "TLS contract failed at line %d: %s\n", __LINE__, #x); return 1; } } while (0)

int main(void)
{
    unsigned char random[64], cleared[64] = {0};
    REQUIRE(RAND_bytes(random, sizeof(random)) == 1);
    REQUIRE(memcmp(random, cleared, sizeof(random)) != 0);
    OPENSSL_cleanse(random, sizeof(random));
    REQUIRE(memcmp(random, cleared, sizeof(random)) == 0);

    REQUIRE(evutil_secure_rng_init() == 0);
    evutil_secure_rng_get_bytes(random, sizeof(random));
    REQUIRE(memcmp(random, cleared, sizeof(random)) != 0);

    BIO *bio = BIO_new(BIO_s_mem());
    REQUIRE(bio != NULL);
    REQUIRE(BIO_write(bio, "test", 4) == 4);
    REQUIRE(BIO_read(bio, random, 4) == 4);
    REQUIRE(memcmp(random, "test", 4) == 0);
    REQUIRE(BIO_number_written(bio) == 4);
    REQUIRE(BIO_number_read(bio) == 4);
    BIO_free(bio);

    return 0;
}
