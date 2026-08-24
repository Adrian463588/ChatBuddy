cmake_minimum_required(VERSION 3.22.1)

project(chatbuddy_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "ChatBuddy native runtime must be built for Android")
endif()

# Reproducible native dependency. This is a build-time fetch, not an app
# runtime network dependency; model inference remains local after setup.
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG d59d455fd8ea09e5a2e87ce2a9d668267ffb5ccd
    GIT_SHALLOW TRUE
)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# Whisper is fetched at a pinned revision and compiled into the same Android
# native build. The model itself remains app-owned in SAF; this is only the
# runtime implementation used after the user installs the Whisper artifact.
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 52dec9d889cc8e5f0a1978f2eb37a96978a131be
    GIT_SHALLOW TRUE
)
FetchContent_Populate(whisper_cpp)

add_library(chatbuddy_llama SHARED chatbuddy_llama.cpp)
find_library(log-lib log)

target_include_directories(chatbuddy_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
)

target_compile_options(chatbuddy_llama PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(chatbuddy_llama PRIVATE llama ${log-lib})

add_library(chatbuddy_whisper SHARED
    ${whisper_cpp_SOURCE_DIR}/src/whisper.cpp
    chatbuddy_whisper.cpp
)

target_include_directories(chatbuddy_whisper PRIVATE
    ${whisper_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/ggml/src
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(chatbuddy_whisper PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION="1.9.3-dev"
)

target_compile_options(chatbuddy_whisper PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(chatbuddy_whisper PRIVATE llama ${log-lib} android)
