#include "android_port.h"

#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000LL + (int64_t) ts.tv_nsec / 1000000LL;
}

/* True if a Java exception is pending; clears it and logs to stderr so a bug in
 * a callback doesn't silently corrupt the protocol state machine. */
static bool check_and_clear_exception(JNIEnv *env, const char *where) {
    if ((*env)->ExceptionCheck(env)) {
        fprintf(stderr, "flasher_native: Java exception in %s\n", where);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return true;
    }
    return false;
}

android_port_t *android_port_create(JNIEnv *env, jobject callback_obj) {
    android_port_t *port = calloc(1, sizeof(android_port_t));
    if (port == NULL) {
        return NULL;
    }

    port->base.ops = &android_port_ops;
    port->env = env;
    port->callback_obj = (*env)->NewGlobalRef(env, callback_obj);

    jclass cls = (*env)->GetObjectClass(env, callback_obj);
    port->m_read = (*env)->GetMethodID(env, cls, "onNativeRead", "([BII)I");
    port->m_write = (*env)->GetMethodID(env, cls, "onNativeWrite", "([BII)I");
    port->m_set_baud_rate = (*env)->GetMethodID(env, cls, "onNativeSetBaudRate", "(I)I");
    port->m_enter_bootloader = (*env)->GetMethodID(env, cls, "onNativeEnterBootloader", "()V");
    port->m_reset_target = (*env)->GetMethodID(env, cls, "onNativeResetTarget", "()V");
    port->m_log = (*env)->GetMethodID(env, cls, "onNativeLog", "(ILjava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    if (check_and_clear_exception(env, "android_port_create/GetMethodID")) {
        android_port_destroy(env, port);
        return NULL;
    }

    return port;
}

void android_port_destroy(JNIEnv *env, android_port_t *port) {
    if (port == NULL) {
        return;
    }
    if (port->callback_obj != NULL) {
        (*env)->DeleteGlobalRef(env, port->callback_obj);
    }
    free(port);
}

static esp_loader_error_t android_write(esp_loader_port_t *base, const uint8_t *data, uint16_t size, uint32_t timeout_ms) {
    android_port_t *p = container_of(base, android_port_t, base);
    JNIEnv *env = p->env;

    int64_t deadline = now_ms() + (int64_t) timeout_ms;
    uint16_t total = 0;
    while (total < size) {
        int64_t remaining = deadline - now_ms();
        if (remaining < 0) {
            remaining = 0;
        }

        jsize chunk_size = (jsize) (size - total);
        jbyteArray buf = (*env)->NewByteArray(env, chunk_size);
        (*env)->SetByteArrayRegion(env, buf, 0, chunk_size, (const jbyte *) (data + total));

        jint n = (*env)->CallIntMethod(env, p->callback_obj, p->m_write, buf, chunk_size, (jint) remaining);
        (*env)->DeleteLocalRef(env, buf);

        if (check_and_clear_exception(env, "onNativeWrite")) {
            return ESP_LOADER_ERROR_FAIL;
        }
        if (n <= 0) {
            return ESP_LOADER_ERROR_TIMEOUT;
        }

        total += (uint16_t) n;
    }
    return ESP_LOADER_SUCCESS;
}

static esp_loader_error_t android_read(esp_loader_port_t *base, uint8_t *data, uint16_t size, uint32_t timeout_ms) {
    android_port_t *p = container_of(base, android_port_t, base);
    JNIEnv *env = p->env;

    int64_t deadline = now_ms() + (int64_t) timeout_ms;
    uint16_t total = 0;
    while (total < size) {
        int64_t remaining = deadline - now_ms();
        if (remaining < 0) {
            remaining = 0;
        }

        jsize chunk_size = (jsize) (size - total);
        jbyteArray buf = (*env)->NewByteArray(env, chunk_size);

        jint n = (*env)->CallIntMethod(env, p->callback_obj, p->m_read, buf, chunk_size, (jint) remaining);

        if (check_and_clear_exception(env, "onNativeRead")) {
            (*env)->DeleteLocalRef(env, buf);
            return ESP_LOADER_ERROR_FAIL;
        }
        if (n <= 0) {
            (*env)->DeleteLocalRef(env, buf);
            return ESP_LOADER_ERROR_TIMEOUT;
        }

        (*env)->GetByteArrayRegion(env, buf, 0, n, (jbyte *) (data + total));
        (*env)->DeleteLocalRef(env, buf);

        total += (uint16_t) n;
    }
    return ESP_LOADER_SUCCESS;
}

static esp_loader_error_t android_change_transmission_rate(esp_loader_port_t *base, uint32_t rate) {
    android_port_t *p = container_of(base, android_port_t, base);
    JNIEnv *env = p->env;

    jint result = (*env)->CallIntMethod(env, p->callback_obj, p->m_set_baud_rate, (jint) rate);
    if (check_and_clear_exception(env, "onNativeSetBaudRate")) {
        return ESP_LOADER_ERROR_FAIL;
    }
    return (result == 0) ? ESP_LOADER_SUCCESS : ESP_LOADER_ERROR_FAIL;
}

static void android_enter_bootloader(esp_loader_port_t *base) {
    android_port_t *p = container_of(base, android_port_t, base);
    JNIEnv *env = p->env;
    (*env)->CallVoidMethod(env, p->callback_obj, p->m_enter_bootloader);
    check_and_clear_exception(env, "onNativeEnterBootloader");
}

static void android_reset_target(esp_loader_port_t *base) {
    android_port_t *p = container_of(base, android_port_t, base);
    JNIEnv *env = p->env;
    (*env)->CallVoidMethod(env, p->callback_obj, p->m_reset_target);
    check_and_clear_exception(env, "onNativeResetTarget");
}

static void android_delay_ms(esp_loader_port_t *base, uint32_t ms) {
    (void) base;
    struct timespec ts = {.tv_sec = ms / 1000, .tv_nsec = (long) (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

static void android_start_timer(esp_loader_port_t *base, uint32_t ms) {
    android_port_t *p = container_of(base, android_port_t, base);
    int64_t deadline = now_ms() + (int64_t) ms;
    p->timer_deadline.tv_sec = deadline / 1000;
    p->timer_deadline.tv_nsec = (long) (deadline % 1000) * 1000000L;
}

static uint32_t android_remaining_time(esp_loader_port_t *base) {
    android_port_t *p = container_of(base, android_port_t, base);
    int64_t deadline = (int64_t) p->timer_deadline.tv_sec * 1000LL + p->timer_deadline.tv_nsec / 1000000LL;
    int64_t remaining = deadline - now_ms();
    return (remaining > 0) ? (uint32_t) remaining : 0;
}

static void android_log(esp_loader_port_t *base, esp_loader_log_level_t level, const char *fmt, va_list args) {
    android_port_t *p = container_of(base, android_port_t, base);
    JNIEnv *env = p->env;

    char buf[256];
    vsnprintf(buf, sizeof(buf), fmt, args);

    jstring message = (*env)->NewStringUTF(env, buf);
    (*env)->CallVoidMethod(env, p->callback_obj, p->m_log, (jint) level, message);
    (*env)->DeleteLocalRef(env, message);
    check_and_clear_exception(env, "onNativeLog");
}

const esp_loader_port_ops_t android_port_ops = {
    .init = NULL,
    .deinit = NULL,
    .enter_bootloader = android_enter_bootloader,
    .reset_target = android_reset_target,
    .start_timer = android_start_timer,
    .remaining_time = android_remaining_time,
    .delay_ms = android_delay_ms,
    .log = android_log,
    .log_hex = NULL, /* hex dumps suppressed for v1; `log` alone feeds the live log console */
    .change_transmission_rate = android_change_transmission_rate,
    .write = android_write,
    .read = android_read,
    .spi_set_cs = NULL,
    .sdio_write = NULL,
    .sdio_read = NULL,
    .sdio_card_init = NULL,
};
