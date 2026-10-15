cmake_minimum_required(VERSION 3.22.1)
project("softetherclient")

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

# Directories
set(PREBUILT_JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs)

# Prebuilt OpenSSL libraries
set(OPENSSL_ROOT ${PREBUILT_JNILIBS_DIR}/${ANDROID_ABI})
set(OPENSSL_LIBRARIES
    ${OPENSSL_ROOT}/libssl.a
    ${OPENSSL_ROOT}/libcrypto.a
)

# Check if prebuilt libraries exist
if(EXISTS ${OPENSSL_ROOT}/libssl.a AND EXISTS ${OPENSSL_ROOT}/libcrypto.a)
    message(STATUS "Using prebuilt OpenSSL from ${OPENSSL_ROOT}")
    set(USE_PREBUILT_OPENSSL TRUE)
else()
    message(WARNING "Prebuilt OpenSSL not found in ${OPENSSL_ROOT}, trying system OpenSSL")
    set(USE_PREBUILT_OPENSSL FALSE)
endif()

# Include directories.
# The build script installs an architecture-specific header set under
# cpp/openssl-build/<abi>/include (its configuration.h differs between 32- and
# 64-bit ABIs), so prefer that and fall back to the source tree when the headers
# have not been generated yet.
set(OPENSSL_ABI_INCLUDE
    ${CMAKE_CURRENT_SOURCE_DIR}/openssl-build/${ANDROID_ABI}/include)
if(EXISTS ${OPENSSL_ABI_INCLUDE}/openssl/configuration.h)
    message(STATUS "Using OpenSSL headers from ${OPENSSL_ABI_INCLUDE}")
    set(OPENSSL_INCLUDE_DIR ${OPENSSL_ABI_INCLUDE})
else()
    set(OPENSSL_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/openssl/include)
endif()

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/softether-core/include
    ${OPENSSL_INCLUDE_DIR}
)


# Source files for main library
set(SOFTETHER_SOURCES
    jni/softether_jni.c
    softether-core/src/proto/softether_protocol.c
    softether-core/src/proto/softether_pack.c
    softether-core/src/proto/softether_nat_t.c
    softether-core/src/proto/rudp_transport.c
    softether-core/src/proto/softether_rudp.c
    softether-core/src/crypto/aes_wrapper.c
    softether-core/src/crypto/compress.c
    softether-core/src/socket/tcp_socket.c
    softether-core/src/packet/packet_handler.c
    softether-core/src/net/dhcp_client.c
)

# Create main shared library
add_library(softether SHARED ${SOFTETHER_SOURCES})

# Link libraries for main library
target_link_libraries(softether
    ${log-lib}
    ${android-lib}
    ${z-lib}
)

# Link prebuilt OpenSSL if available
if(USE_PREBUILT_OPENSSL)
    target_link_libraries(softether ${OPENSSL_LIBRARIES})
else()
    # Fallback to system OpenSSL
    target_link_libraries(softether ssl crypto)
endif()

# Compiler flags
target_compile_options(softether PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -fPIC
)

# The NDK path ends up in the debug info, and the linker hashes that debug info
# into the build-id. The path differs between the CI builder and the F-Droid
# builder, so the build-id differs too. It survives stripping and ships inside
# the APK, and F-Droid compares the APKs byte for byte, so drop it.
target_link_options(softether PRIVATE -Wl,--build-id=none)

# Phase 13A: per-packet data-path trace logging. Off by default — a formatted
# logd write per packet dominates mobile throughput. Enable for data-path
# debugging (e.g. -DSE_TRACE_PACKETS=ON via externalNativeBuild arguments).
option(SE_TRACE_PACKETS "Enable per-packet trace logging" OFF)
if(SE_TRACE_PACKETS)
    add_compile_definitions(SE_TRACE_PACKETS=1)
endif()

# Debug/Release specific flags
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(softether PRIVATE -g -O0)
else()
    target_compile_options(softether PRIVATE -O2)
endif()

# Export compile commands for IDE support
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
