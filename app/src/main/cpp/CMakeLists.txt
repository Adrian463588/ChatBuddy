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
