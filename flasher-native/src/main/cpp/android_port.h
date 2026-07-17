#pragma once

#include <jni.h>
#include <stdint.h>
#include <time.h>

#include "esp_loader.h"
#include "esp_loader_io.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Android esp-serial-flasher port: implements esp_loader_port_ops_t by calling
 * back into a Kotlin `EspLoaderNative` instance for anything that touches real
 * hardware (byte I/O, baud rate, DTR/RTS, log lines). See CLAUDE.md "JNI port
 * design" for the rationale.
 *
 * Threading invariant: every JNI entry point in jni_bridge.c must be called from
 * the same Java thread for a given handle's lifetime, and must refresh `env`
 * (via android_port_set_env) before touching the loader - callbacks fire
 * synchronously on that same thread/stack, so no AttachCurrentThread dance is
 * needed, but a stale `env` from a different thread would be unsafe to use.
 */
typedef struct {
    esp_loader_port_t base; /* must be the first callback target: container_of recovers this struct from it */

    JNIEnv *env;         /* refreshed at the top of every JNI entry point, see above */
    jobject callback_obj; /* global ref to the Kotlin EspLoaderNative instance */

    jmethodID m_read;             /* int onNativeRead(byte[] buf, int size, int timeoutMs) */
    jmethodID m_write;            /* int onNativeWrite(byte[] buf, int size, int timeoutMs) */
    jmethodID m_set_baud_rate;    /* int onNativeSetBaudRate(int baud) -> 0 on success */
    jmethodID m_enter_bootloader; /* void onNativeEnterBootloader() */
    jmethodID m_reset_target;     /* void onNativeResetTarget() */
    jmethodID m_log;              /* void onNativeLog(int level, String message) */

    struct timespec timer_deadline;
} android_port_t;

/** Vtable satisfying esp_loader_port_ops_t; PORT=USER_DEFINED links this manually. */
extern const esp_loader_port_ops_t android_port_ops;

/**
 * Allocates and wires up an android_port_t for `callback_obj` (the Kotlin
 * EspLoaderNative instance making this call - pass the JNI-implicit `thiz`).
 * Takes a global ref to `callback_obj`; release it via android_port_destroy().
 */
android_port_t *android_port_create(JNIEnv *env, jobject callback_obj);

void android_port_destroy(JNIEnv *env, android_port_t *port);

/** Refresh the JNIEnv* to use for callbacks - call at the top of every JNI entry point. */
static inline void android_port_set_env(android_port_t *port, JNIEnv *env) {
    port->env = env;
}

#ifdef __cplusplus
}
#endif
