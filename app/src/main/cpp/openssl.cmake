# Called from cmake/tls.cmake with checksum-locked tls_* variables.
include(ExternalProject)
set(openssl_root "${CMAKE_CURRENT_BINARY_DIR}/tls-openssl")
file(MAKE_DIRECTORY "${openssl_root}/install/include")
set(openssl_env "CC=${CMAKE_C_COMPILER}" "AR=${CMAKE_AR}" "RANLIB=${CMAKE_RANLIB}")
set(openssl_target "")
if(ANDROID)
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
    get_filename_component(ndk_bin "${CMAKE_C_COMPILER}" DIRECTORY)
    set(openssl_env "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${ndk_bin}:$ENV{PATH}")
    list(APPEND openssl_target "-D__ANDROID_API__=${ANDROID_PLATFORM_LEVEL}")
endif()
find_program(OPENSSL_MAKE NAMES gmake make REQUIRED)
string(TOUPPER "${CMAKE_BUILD_TYPE}" build_type)
set(openssl_flags "${CMAKE_C_FLAGS} ${CMAKE_C_FLAGS_${build_type}} -fPIC")
if(openssl_flags MATCHES "-fsanitize=[^ ]*(undefined|function)")
    # Configure does not add PEDANTIC when UBSan is already present in CFLAGS.
    string(APPEND openssl_flags " -DPEDANTIC")
    if(CMAKE_C_COMPILER_ID MATCHES "Clang")
        # OpenSSL 3.5's generic stack/lhash APIs cast typed callbacks to void *
        # signatures, including inside provider initialization. PEDANTIC does
        # not change those casts. Exclude only function-type instrumentation
        # in this downloaded dependency; retain ASan and all other UBSan
        # checks here, and the complete sanitizer set in our own targets.
        string(APPEND openssl_flags " -fno-sanitize=function")
        message(STATUS "OpenSSL only: Clang function-type sanitizer excluded for upstream generic callbacks")
    endif()
endif()
set(tls_upstream_flags "${openssl_flags}")
set(openssl_options)
string(JSON option_count LENGTH "${tls_options}")
math(EXPR option_last "${option_count}-1")
foreach(index RANGE ${option_last})
    string(JSON value GET "${tls_options}" ${index})
    list(APPEND openssl_options "${value}")
endforeach()
ExternalProject_Add(proxydroid-openssl
    PREFIX "${openssl_root}" URL "${tls_url}" URL_HASH "SHA256=${tls_sha256}"
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env ${openssl_env} "CFLAGS=${openssl_flags}"
        <SOURCE_DIR>/Configure ${openssl_target} ${openssl_options}
        "--prefix=${openssl_root}/install" --libdir=lib
    BUILD_COMMAND "${CMAKE_COMMAND}" -E env ${openssl_env} "${OPENSSL_MAKE}" -s -j4
    INSTALL_COMMAND "${CMAKE_COMMAND}" -E env ${openssl_env} "${OPENSSL_MAKE}" install_sw
    LOG_CONFIGURE 1 LOG_BUILD 1 LOG_INSTALL 1 LOG_OUTPUT_ON_FAILURE 1
    BUILD_BYPRODUCTS "${openssl_root}/install/lib/libssl.a" "${openssl_root}/install/lib/libcrypto.a")
foreach(lib SSL Crypto)
    string(TOLOWER "${lib}" archive)
    add_library(OpenSSL::${lib} STATIC IMPORTED GLOBAL)
    set_target_properties(OpenSSL::${lib} PROPERTIES IMPORTED_LOCATION "${openssl_root}/install/lib/lib${archive}.a"
        INTERFACE_INCLUDE_DIRECTORIES "${openssl_root}/install/include")
    add_dependencies(OpenSSL::${lib} proxydroid-openssl)
endforeach()
target_link_libraries(OpenSSL::SSL INTERFACE OpenSSL::Crypto ${CMAKE_DL_LIBS})
