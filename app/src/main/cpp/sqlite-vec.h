#ifndef CHATBUDDY_SQLITE_VEC_H
#define CHATBUDDY_SQLITE_VEC_H

#include "sqlite3ext.h"

#define SQLITE_VEC_API
#define SQLITE_VEC_VERSION "v0.1.7-alpha.10"
#define SQLITE_VEC_DATE "2026-02-13T17:54:41Z"
#define SQLITE_VEC_SOURCE "ce7b53e8490e40cd44af73aee463f99b6b50598c"
#define SQLITE_VEC_VERSION_MAJOR 0
#define SQLITE_VEC_VERSION_MINOR 1
#define SQLITE_VEC_VERSION_PATCH 7

#ifdef __cplusplus
extern "C" {
#endif

int sqlite3_vec_init(
    sqlite3 *db,
    char **error_message,
    const sqlite3_api_routines *api
);

#ifdef __cplusplus
}
#endif

#endif
