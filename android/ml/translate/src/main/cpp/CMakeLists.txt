cmake_minimum_required(VERSION 3.22)
project(mangareader_llama CXX)

# Section 5 tier 1's native half. LLAMA_SOURCE_DIR is passed by the Gradle build, which
# downloads and checksum-verifies a pinned llama.cpp release first -- see
# ml/translate/build.gradle.kts. Nothing here fetches anything.
if(NOT DEFINED LLAMA_SOURCE_DIR)
    message(FATAL_ERROR "LLAMA_SOURCE_DIR is not set; run the build through Gradle")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Only the library is wanted. llama.cpp's tools and examples are desktop binaries that
# would fail to link against Android's libc and cost minutes of build time to discover it.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# arm64-v8a only, which is what the target device is and the only ABI this project ships.
# OpenMP is off: the NDK does not carry it by default, and section 3 wants the thread
# count pinned to the four big cores rather than handed to a runtime that will find eight.
set(GGML_OPENMP   OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# **This line is most of the performance.**
#
# ggml auto-detects ARM features when building for the host, but this is a cross-compile,
# so without an explicit -march it takes the NDK's baseline `armv8-a`: no `dotprod`, no
# `i8mm`. Those two instructions are what make int4 matmul fast, and a Q4_K_M model
# decoded without them runs many times slower -- measured here as minutes per page rather
# than the tens of seconds docs/on-device-llm.md predicts.
#
# **dotprod yes, i8mm no**, and that is measured rather than assumed. The Cortex-A78 in
# the target device is ARMv8.2-A; `i8mm` arrived in ARMv8.6-A. Building with `+i8mm`
# compiles and links happily and then dies at the first token with
#
#     Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC) in ggml_gemm_q4_K_8x8_q8_K
#
# because ggml selects an i8mm repack kernel it was told the CPU had. The device's own
# `/proc/cpuinfo` Features line is the authority: it lists `asimddp` (dotprod) and no
# `i8mm`.
#
# This is not universal either: an ARMv8.0 phone would SIGILL on the dotprod kernels the
# same way. It pairs with the arm64-v8a-only abiFilter, and shipping beyond this device
# needs either a runtime feature check or ggml's multi-variant CPU backend.
if(ANDROID_ABI STREQUAL "arm64-v8a" AND NOT DEFINED GGML_CPU_ARM_ARCH)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

# A tutorial AVD build runs natively on x86_64; do not optimize for the build host.
if(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_library(mangareader_llama SHARED llama_jni.cpp)

target_link_libraries(mangareader_llama
    PRIVATE
        llama
        android
        log)
