# Scoped to the upstream libevent subdirectory by the parent build. The locked
# OpenSSL is built later by ExternalProject, so FindOpenSSL cannot inspect its
# generated headers during configuration. Reuse exactly those imported targets
# instead of discovering a second (possibly host) OpenSSL installation.
if(NOT TLS_BACKEND STREQUAL "openssl" OR
        NOT TARGET OpenSSL::SSL OR NOT TARGET OpenSSL::Crypto)
    message(FATAL_ERROR "Configure the selected OpenSSL targets before libevent")
endif()
get_target_property(OPENSSL_INCLUDE_DIR OpenSSL::SSL INTERFACE_INCLUDE_DIRECTORIES)
# Upstream also exports this directory on event_openssl_static. It is a build
# dependency, not an installable libevent header path.
set(OPENSSL_INCLUDE_DIR "$<BUILD_INTERFACE:${OPENSSL_INCLUDE_DIR}>")
get_target_property(OPENSSL_VERSION OpenSSL::SSL PROXYDROID_VERSION)
set(OPENSSL_SSL_LIBRARY OpenSSL::SSL)
set(OPENSSL_CRYPTO_LIBRARY OpenSSL::Crypto)
set(OPENSSL_LIBRARIES OpenSSL::SSL OpenSSL::Crypto)
set(OPENSSL_FOUND TRUE)
set(OpenSSL_FOUND TRUE)
set(OpenSSL_VERSION "${OPENSSL_VERSION}")
