/*
 * JNI entry points for com.espotg.flasher.EspLoaderNative. Every exported
 * function must be called from the same Java thread that created its handle -
 * see the threading invariant documented in android_port.h.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "android_port.h"
#include "esp_loader.h"

typedef struct {
    esp_loader_t loader;
    android_port_t *port;
    esp_loader_flash_cfg_t flash_cfg;
    esp_loader_flash_deflate_cfg_t deflate_cfg;
} flasher_ctx_t;

static flasher_ctx_t *ctx_from_handle(JNIEnv *env, jlong handle) {
    flasher_ctx_t *ctx = (flasher_ctx_t *) (intptr_t) handle;
    if (ctx != NULL) {
        android_port_set_env(ctx->port, env);
    }
    return ctx;
}

JNIEXPORT jlong JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeCreate(JNIEnv *env, jobject thiz) {
    flasher_ctx_t *ctx = calloc(1, sizeof(flasher_ctx_t));
    if (ctx == NULL) {
        return 0;
    }

    ctx->port = android_port_create(env, thiz);
    if (ctx->port == NULL) {
        free(ctx);
        return 0;
    }

    if (esp_loader_init_serial(&ctx->loader, &ctx->port->base) != ESP_LOADER_SUCCESS) {
        android_port_destroy(env, ctx->port);
        free(ctx);
        return 0;
    }

    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return;
    }
    esp_loader_deinit(&ctx->loader);
    android_port_destroy(env, ctx->port);
    free(ctx);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeConnect(JNIEnv *env, jobject thiz, jlong handle,
                                                       jint sync_timeout_ms, jint trials,
                                                       jboolean use_stub) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    esp_loader_connect_args_t args = {
        .sync_timeout = (uint32_t) sync_timeout_ms,
        .trials = trials,
    };
    esp_loader_error_t err = use_stub
        ? esp_loader_connect_with_stub(&ctx->loader, &args)
        : esp_loader_connect(&ctx->loader, &args);
    return (jint) err;
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeGetTargetChip(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return -1;
    }
    return (jint) esp_loader_get_target(&ctx->loader);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeReadMac(JNIEnv *env, jobject thiz, jlong handle,
                                                       jbyteArray out_mac) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    uint8_t mac[6] = {0};
    esp_loader_error_t err = esp_loader_read_mac(&ctx->loader, mac);
    if (err == ESP_LOADER_SUCCESS) {
        (*env)->SetByteArrayRegion(env, out_mac, 0, 6, (const jbyte *) mac);
    }
    return (jint) err;
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeChangeBaudRate(JNIEnv *env, jobject thiz, jlong handle,
                                                              jint baud) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    return (jint) esp_loader_change_transmission_rate(&ctx->loader, (uint32_t) baud);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashStart(JNIEnv *env, jobject thiz, jlong handle,
                                                          jlong offset, jlong image_size,
                                                          jint block_size, jboolean skip_verify) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    memset(&ctx->flash_cfg, 0, sizeof(ctx->flash_cfg));
    ctx->flash_cfg.offset = (uint32_t) offset;
    ctx->flash_cfg.image_size = (uint32_t) image_size;
    ctx->flash_cfg.block_size = (uint32_t) block_size;
    ctx->flash_cfg.skip_verify = (skip_verify != JNI_FALSE);
    return (jint) esp_loader_flash_start(&ctx->loader, &ctx->flash_cfg);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashWrite(JNIEnv *env, jobject thiz, jlong handle,
                                                          jbyteArray payload, jint size) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, payload, NULL);
    esp_loader_error_t err = esp_loader_flash_write(&ctx->loader, &ctx->flash_cfg, bytes, (uint32_t) size);
    (*env)->ReleaseByteArrayElements(env, payload, bytes, JNI_ABORT);
    return (jint) err;
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashFinish(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    return (jint) esp_loader_flash_finish(&ctx->loader, &ctx->flash_cfg);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashDeflateStart(JNIEnv *env, jobject thiz, jlong handle,
                                                                 jlong offset, jlong image_size,
                                                                 jlong compressed_size, jint block_size) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    memset(&ctx->deflate_cfg, 0, sizeof(ctx->deflate_cfg));
    ctx->deflate_cfg.offset = (uint32_t) offset;
    ctx->deflate_cfg.image_size = (uint32_t) image_size;
    ctx->deflate_cfg.compressed_size = (uint32_t) compressed_size;
    ctx->deflate_cfg.block_size = (uint32_t) block_size;
    return (jint) esp_loader_flash_deflate_start(&ctx->loader, &ctx->deflate_cfg);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashDeflateWrite(JNIEnv *env, jobject thiz, jlong handle,
                                                                 jbyteArray payload, jint size) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, payload, NULL);
    esp_loader_error_t err = esp_loader_flash_deflate_write(&ctx->loader, &ctx->deflate_cfg, bytes, (uint32_t) size);
    (*env)->ReleaseByteArrayElements(env, payload, bytes, JNI_ABORT);
    return (jint) err;
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashDeflateFinish(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    return (jint) esp_loader_flash_deflate_finish(&ctx->loader, &ctx->deflate_cfg);
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeVerifyMd5(JNIEnv *env, jobject thiz, jlong handle,
                                                         jlong address, jlong size,
                                                         jbyteArray expected_md5) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    jbyte *md5 = (*env)->GetByteArrayElements(env, expected_md5, NULL);
    esp_loader_error_t err = esp_loader_flash_verify_known_md5(
        &ctx->loader, (uint32_t) address, (uint32_t) size, (const uint8_t *) md5);
    (*env)->ReleaseByteArrayElements(env, expected_md5, md5, JNI_ABORT);
    return (jint) err;
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeFlashRead(JNIEnv *env, jobject thiz, jlong handle,
                                                         jlong address, jbyteArray out, jint length) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    esp_loader_error_t err = esp_loader_flash_read(
        &ctx->loader, (uint8_t *) buf, (uint32_t) address, (uint32_t) length);
    /* 0 = JNI_COMMIT+free: copy the read bytes back to the Java array. */
    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    return (jint) err;
}

JNIEXPORT jint JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeDetectFlashSize(JNIEnv *env, jobject thiz, jlong handle,
                                                               jlongArray out_size) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return ESP_LOADER_ERROR_INVALID_PARAM;
    }
    uint32_t size = 0;
    esp_loader_error_t err = esp_loader_flash_detect_size(&ctx->loader, &size);
    if (err == ESP_LOADER_SUCCESS) {
        jlong s = (jlong) size;
        (*env)->SetLongArrayRegion(env, out_size, 0, 1, &s);
    }
    return (jint) err;
}

JNIEXPORT void JNICALL
Java_com_espotg_flasher_EspLoaderNative_nativeResetTarget(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    flasher_ctx_t *ctx = ctx_from_handle(env, handle);
    if (ctx == NULL) {
        return;
    }
    esp_loader_reset_target(&ctx->loader);
}
