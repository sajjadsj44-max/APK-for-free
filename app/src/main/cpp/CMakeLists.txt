cmake_minimum_required(VERSION 3.22.1)
project(whisperjni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp source is cloned here by the GitHub build (see .github/workflows/build.yml)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/src/whisper.cpp)
    message(FATAL_ERROR "whisper.cpp not found in ${WHISPER_DIR}. Clone it there first.")
endif()

# Generic arm64 build: runs on every 64-bit phone (no CPU-specific instructions)
set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(whisperjni SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    whisper_jni.cpp
)

target_include_directories(whisperjni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
target_compile_definitions(whisperjni PUBLIC GGML_USE_CPU)
target_compile_definitions(whisperjni PRIVATE WHISPER_VERSION="android")

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisperjni PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_compile_options(ggml-base PRIVATE -O3)
    target_compile_options(ggml-cpu  PRIVATE -O3)
    target_link_options(whisperjni PRIVATE -Wl,--gc-sections)
endif()

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(whisperjni PRIVATE ggml ${LOG_LIB} android)
else()
    target_link_libraries(whisperjni PRIVATE ggml)
endif()
