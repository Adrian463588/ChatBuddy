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

# sqlite-vec is built as a loadable extension for AndroidX BundledSQLiteDriver.
# The pinned commit is an upstream source dependency; no model or runtime data
# is downloaded by the application at runtime.
FetchContent_Declare(
    sqlite_vec
    GIT_REPOSITORY https://github.com/asg017/sqlite-vec.git
    GIT_TAG ce7b53e8490e40cd44af73aee463f99b6b50598c
    GIT_SHALLOW TRUE
)
FetchContent_Populate(sqlite_vec)

# sqlite-vec is an SQLite loadable extension and therefore needs the public
# SQLite C headers at compile time. The upstream source repository keeps the
# generated sqlite3.h out of the tree, so fetch the exact pinned release
# templates and generate the public header in the build directory.
set(SQLITE_HEADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/sqlite-headers")
file(MAKE_DIRECTORY "${SQLITE_HEADER_DIR}")
set(SQLITE_H_IN "${SQLITE_HEADER_DIR}/sqlite.h.in")
set(SQLITE_EXT_H "${SQLITE_HEADER_DIR}/sqlite3ext.h")
file(DOWNLOAD
    "https://raw.githubusercontent.com/sqlite/sqlite/version-3.46.1/src/sqlite.h.in"
    "${SQLITE_H_IN}"
    EXPECTED_HASH SHA256=f06c5401b1aa27c8aba0d1869c589316923c9fb9c425dced6e5dc0d4c648c1fb
    TLS_VERIFY ON
)
file(DOWNLOAD
    "https://raw.githubusercontent.com/sqlite/sqlite/version-3.46.1/src/sqlite3ext.h"
    "${SQLITE_EXT_H}"
    EXPECTED_HASH SHA256=b184dd1586d935133d37ad76fa353faf0a1021ff2fdedeedcc3498fff74bbb94
    TLS_VERIFY ON
)
file(READ "${SQLITE_H_IN}" SQLITE_H_CONTENT)
string(REPLACE "--VERS--" "3.46.1" SQLITE_H_CONTENT "${SQLITE_H_CONTENT}")
string(REPLACE "--VERSION-NUMBER--" "3046001" SQLITE_H_CONTENT "${SQLITE_H_CONTENT}")
file(WRITE "${SQLITE_HEADER_DIR}/sqlite3.h" "${SQLITE_H_CONTENT}")

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

add_library(chatbuddy_sqlite_vec SHARED
    ${sqlite_vec_SOURCE_DIR}/sqlite-vec.c
)

target_include_directories(chatbuddy_sqlite_vec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${sqlite_vec_SOURCE_DIR}
    ${sqlite_vec_SOURCE_DIR}/vendor
    ${SQLITE_HEADER_DIR}
)

target_compile_definitions(chatbuddy_sqlite_vec PRIVATE
    SQLITE_VEC_OMIT_FS
)

target_compile_options(chatbuddy_sqlite_vec PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_options(chatbuddy_sqlite_vec PRIVATE
    "-Wl,--allow-shlib-undefined"
)

target_link_libraries(chatbuddy_sqlite_vec PRIVATE ${log-lib} m)
