cmake_minimum_required(VERSION 3.22.1)

# Native Whisper is only worth having if it is optimised: a Debug build of ggml is many times slower
# and would make on-device transcription unusable even while developing.
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)

project(dpx_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product and half-precision vector arithmetic (Armv8.2) are what make quantised matmuls fast
    # on a phone. Every flagship SoC since ~2018 has them; the app checks /proc/cpuinfo before loading
    # and refuses cleanly rather than crashing on a CPU without them.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif ()

add_subdirectory(whisper.cpp)

add_library(dpxwhisper SHARED whisper_jni.cpp)
target_link_libraries(dpxwhisper PRIVATE whisper log android)

# Android 15+ devices can run with 16 KB memory pages; native libraries must be aligned for that.
target_link_options(dpxwhisper PRIVATE -Wl,-z,max-page-size=16384)
