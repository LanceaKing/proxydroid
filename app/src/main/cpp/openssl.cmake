# All dependency objects are checksum-verified, static and PIC.
include(ExternalProject)
set(PROXYDROID_OPENSSL_VERSION 3.5.9)
set(PROXYDROID_OPENSSL_SHA256 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a)
set(PROXYDROID_OPENSSL_URL "https://www.openssl.org/source/openssl-${PROXYDROID_OPENSSL_VERSION}.tar.gz" CACHE STRING "OpenSSL archive or local mirror")
set(openssl_root "${CMAKE_CURRENT_BINARY_DIR}/openssl")
file(MAKE_DIRECTORY "${openssl_root}/install/include")
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(openssl_target android-arm)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(openssl_target android-arm64)
elseif(ANDROID_ABI STREQUAL "x86")
    set(openssl_target android-x86)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(openssl_target android-x86_64)
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()
file(GLOB ndk_prebuilt "${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin")
list(GET ndk_prebuilt 0 ndk_bin)
find_program(OPENSSL_MAKE NAMES gmake make)
ExternalProject_Add(proxydroid-openssl
    PREFIX "${openssl_root}" URL "${PROXYDROID_OPENSSL_URL}"
    URL_HASH "SHA256=${PROXYDROID_OPENSSL_SHA256}"
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env
        "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${ndk_bin}:$ENV{PATH}"
        <SOURCE_DIR>/Configure ${openssl_target} -D__ANDROID_API__=${ANDROID_PLATFORM_LEVEL}
        no-shared no-tests no-apps no-module no-legacy -fPIC
        "--prefix=${openssl_root}/install" --libdir=lib
    BUILD_COMMAND "${CMAKE_COMMAND}" -E env "PATH=${ndk_bin}:$ENV{PATH}" "${OPENSSL_MAKE}" -s -j4
    INSTALL_COMMAND "${CMAKE_COMMAND}" -E env "PATH=${ndk_bin}:$ENV{PATH}" "${OPENSSL_MAKE}" install_sw
    LOG_CONFIGURE 1 LOG_BUILD 1 LOG_INSTALL 1 LOG_OUTPUT_ON_FAILURE 1
    BUILD_BYPRODUCTS "${openssl_root}/install/lib/libssl.a" "${openssl_root}/install/lib/libcrypto.a")
foreach(lib ssl crypto)
    add_library(OpenSSL::${lib} STATIC IMPORTED GLOBAL)
    set_target_properties(OpenSSL::${lib} PROPERTIES IMPORTED_LOCATION "${openssl_root}/install/lib/lib${lib}.a"
        INTERFACE_INCLUDE_DIRECTORIES "${openssl_root}/install/include")
    add_dependencies(OpenSSL::${lib} proxydroid-openssl)
endforeach()
add_library(OpenSSL::SSL ALIAS OpenSSL::ssl)
add_library(OpenSSL::Crypto ALIAS OpenSSL::crypto)
