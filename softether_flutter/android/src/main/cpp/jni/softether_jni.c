#include "softether_jni.h"
#include "softether_protocol.h"
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <signal.h>

#define TAG "SoftEtherJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    signal(SIGPIPE, SIG_IGN);
    LOGD("JNI_OnLoad: SIGPIPE ignored");
    return JNI_VERSION_1_6;
}

// Currently installed protect target: the app's VpnService instance. Only one
// VPN tunnel is active at a time, so a single global reference is enough: it is
// replaced on every connect and released when a new one is installed.
static jobject g_protector = NULL;
static jmethodID g_protect_method = NULL;

// The hook resolves protect(I)Z on the platform class android.net.VpnService,
// not on anything the app declares. Two earlier attempts to look the method up
// on the app side both failed on device ("SocketProtector.protect(I)Z
// unavailable", so the callback was cleared and every additional socket ran
// through the tunnel): first on the Kotlin SAM lambda's own class (a hidden
// class from LambdaMetafactory), then on the nested interface looked up by name.
// android.net.VpnService lives on the boot classpath, so FindClass() always
// succeeds and protect(int) is public and stable across releases.
#define PROTECTOR_CLASS "android/net/VpnService"
#define PROTECTOR_METHOD "protect"
#define PROTECTOR_SIG "(I)Z"

// Invoked from the native connect path (possibly on a background thread) for a
// freshly created socket fd, before connect(). Attaches the thread to the JVM if
// needed and calls VpnService.protect(fd) so Android can exclude the socket from
// the VPN tunnel. Returns 0 on success.
static int jni_protect_fd(void* userdata, int fd) {
    (void)userdata;
    if (g_jvm == NULL || g_protector == NULL || g_protect_method == NULL) {
        return -1;
    }

    JNIEnv* env = NULL;
    int attached = 0;
    jint env_status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (env_status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) != JNI_OK) {
            LOGW("protect: AttachCurrentThread failed");
            return -1;
        }
        attached = 1;
    } else if (env_status != JNI_OK || env == NULL) {
        // Only JNI_EDETACHED was handled before: any other failure left env
        // NULL and the CallBooleanMethod below dereferenced it (native crash).
        LOGW("protect: GetEnv failed (status=%d)", (int)env_status);
        return -1;
    }

    jboolean ok = (*env)->CallBooleanMethod(env, g_protector, g_protect_method, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        LOGE("protect: Java exception in VpnService.protect");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }

    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    LOGD("protect: fd=%d -> %s", fd, ok == JNI_TRUE ? "ok" : "failed");
    return ok == JNI_TRUE ? 0 : -1;
}

// Install (or clear, with vpnService=null) the socket-protect hook on the
// native connection. Must be called before connecting so additional TCP sockets
// bypass the tunnel. Returns true when the hook was installed.
JNIEXPORT jboolean JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetProtectCallback(
    JNIEnv *env, jobject thiz, jlong handle, jobject vpnService) {
    if (handle == 0) {
        LOGE("Invalid handle for setProtectCallback");
        return JNI_FALSE;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;

    if (g_protector != NULL) {
        (*env)->DeleteGlobalRef(env, g_protector);
        g_protector = NULL;
        g_protect_method = NULL;
    }

    if (vpnService == NULL) {
        softether_set_protect_callback(conn, NULL, NULL);
        return JNI_FALSE;
    }

    g_protector = (*env)->NewGlobalRef(env, vpnService);

    jclass cls = (*env)->FindClass(env, PROTECTOR_CLASS);
    if (cls != NULL) {
        g_protect_method = (*env)->GetMethodID(env, cls, PROTECTOR_METHOD, PROTECTOR_SIG);
        (*env)->DeleteLocalRef(env, cls);
    }
    if (g_protect_method == NULL) {
        // FindClass / GetMethodID left a pending exception (OOM or
        // NoSuchMethodError). Leaving it pending makes the JVM throw it into
        // Kotlin on the next frame as an Error — which the connect flow's
        // catch (Exception) does not handle — and the process dies. Clear it
        // and fall back to "no socket protection" instead.
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        LOGE("setProtectCallback: VpnService.protect(I)Z unavailable");
        if (g_protector != NULL) {
            (*env)->DeleteGlobalRef(env, g_protector);
            g_protector = NULL;
        }
        softether_set_protect_callback(conn, NULL, NULL);
        return JNI_FALSE;
    }

    LOGD("setProtectCallback: %s.%s resolved", PROTECTOR_CLASS, PROTECTOR_METHOD);
    softether_set_protect_callback(conn, jni_protect_fd, NULL);
    return JNI_TRUE;
}

// JNI Implementation for SoftEtherClient

JNIEXPORT jlong JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeCreate(
    JNIEnv *env, jobject thiz) {
    LOGD("nativeCreate called");
    
    softether_connection_t* conn = softether_create();
    if (conn == NULL) {
        LOGE("Failed to create connection");
        return 0;
    }
    
    return (jlong)conn;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong handle) {
    LOGD("nativeDestroy called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    
    // Get state before destroying for logging
    softether_state_t state = softether_get_state(conn);
    LOGD("Destroying connection in state: %s", softether_state_string(state));
    
    softether_destroy(conn);
    // Release the protect-callback target: it holds a global reference to the
    // app's VpnService, so keeping it after teardown would pin the service. Safe
    // here — teardown runs only once the connect flow has ended, so no protect
    // upcall can be in flight.
    if (g_protector != NULL) {
        (*env)->DeleteGlobalRef(env, g_protector);
        g_protector = NULL;
        g_protect_method = NULL;
    }
    LOGD("nativeDestroy completed");
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnect(
    JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port, 
    jstring username, jstring password) {
    LOGD("nativeConnect called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return ERR_UNKNOWN;
    }
    
    const char* host_str = (*env)->GetStringUTFChars(env, host, NULL);
    const char* username_str = (*env)->GetStringUTFChars(env, username, NULL);
    const char* password_str = (*env)->GetStringUTFChars(env, password, NULL);
    
    if (host_str == NULL || username_str == NULL || password_str == NULL) {
        LOGE("Failed to get string parameters");
        if (host_str) (*env)->ReleaseStringUTFChars(env, host, host_str);
        if (username_str) (*env)->ReleaseStringUTFChars(env, username, username_str);
        if (password_str) (*env)->ReleaseStringUTFChars(env, password, password_str);
        return ERR_UNKNOWN;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_connect(conn, host_str, port, username_str, password_str);
    
    (*env)->ReleaseStringUTFChars(env, host, host_str);
    (*env)->ReleaseStringUTFChars(env, username, username_str);
    (*env)->ReleaseStringUTFChars(env, password, password_str);
    
    return result;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnectWithHub(
    JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port, 
    jstring username, jstring password, jstring hubName, jboolean useTcp,
    jstring clientProductName, jstring clientVersion, jint clientBuild,
    jstring clientOsName, jstring clientOsVersion, jstring clientOsProductId,
    jstring clientHostName, jstring clientIpAddress, jint clientPort,
    jstring serverHostName, jstring serverIpAddress, jint serverPort) {
    LOGD("nativeConnectWithHub called (useTcp=%d)", (int)useTcp);    
    if (handle == 0) {
        LOGE("Invalid handle");
        return ERR_UNKNOWN;
    }
    
    const char* host_str = (*env)->GetStringUTFChars(env, host, NULL);
    const char* username_str = (*env)->GetStringUTFChars(env, username, NULL);
    const char* password_str = (*env)->GetStringUTFChars(env, password, NULL);
    const char* hub_name_str = (*env)->GetStringUTFChars(env, hubName, NULL);
    const char* client_product_name_str = (*env)->GetStringUTFChars(env, clientProductName, NULL);
    const char* client_version_str = (*env)->GetStringUTFChars(env, clientVersion, NULL);
    const char* client_os_name_str = (*env)->GetStringUTFChars(env, clientOsName, NULL);
    const char* client_os_version_str = (*env)->GetStringUTFChars(env, clientOsVersion, NULL);
    const char* client_os_product_id_str = (*env)->GetStringUTFChars(env, clientOsProductId, NULL);
    const char* client_host_name_str = (*env)->GetStringUTFChars(env, clientHostName, NULL);
    const char* client_ip_address_str = (*env)->GetStringUTFChars(env, clientIpAddress, NULL);
    const char* server_host_name_str = (*env)->GetStringUTFChars(env, serverHostName, NULL);
    const char* server_ip_address_str = (*env)->GetStringUTFChars(env, serverIpAddress, NULL);
    
    if (host_str == NULL || username_str == NULL || password_str == NULL || hub_name_str == NULL ||
        client_product_name_str == NULL || client_version_str == NULL || client_os_name_str == NULL ||
        client_os_version_str == NULL || client_os_product_id_str == NULL || client_host_name_str == NULL ||
        client_ip_address_str == NULL || server_host_name_str == NULL || server_ip_address_str == NULL) {
        LOGE("Failed to get string parameters");
        if (host_str) (*env)->ReleaseStringUTFChars(env, host, host_str);
        if (username_str) (*env)->ReleaseStringUTFChars(env, username, username_str);
        if (password_str) (*env)->ReleaseStringUTFChars(env, password, password_str);
        if (hub_name_str) (*env)->ReleaseStringUTFChars(env, hubName, hub_name_str);
        if (client_product_name_str) (*env)->ReleaseStringUTFChars(env, clientProductName, client_product_name_str);
        if (client_version_str) (*env)->ReleaseStringUTFChars(env, clientVersion, client_version_str);
        if (client_os_name_str) (*env)->ReleaseStringUTFChars(env, clientOsName, client_os_name_str);
        if (client_os_version_str) (*env)->ReleaseStringUTFChars(env, clientOsVersion, client_os_version_str);
        if (client_os_product_id_str) (*env)->ReleaseStringUTFChars(env, clientOsProductId, client_os_product_id_str);
        if (client_host_name_str) (*env)->ReleaseStringUTFChars(env, clientHostName, client_host_name_str);
        if (client_ip_address_str) (*env)->ReleaseStringUTFChars(env, clientIpAddress, client_ip_address_str);
        if (server_host_name_str) (*env)->ReleaseStringUTFChars(env, serverHostName, server_host_name_str);
        if (server_ip_address_str) (*env)->ReleaseStringUTFChars(env, serverIpAddress, server_ip_address_str);
        return ERR_UNKNOWN;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;

    // Serialize connect and disconnect to prevent freeing SSL while the
    // connect path is using it.  Recursive: softether_connect_with_hub calls
    // softether_disconnect internally on error paths.
    pthread_mutex_lock(&conn->connect_mutex);
    int result = softether_connect_with_hub(conn, host_str, port, username_str, password_str, hub_name_str, (int)useTcp,
        client_product_name_str, client_version_str, (int)clientBuild,
        client_os_name_str, client_os_version_str, client_os_product_id_str,
        client_host_name_str, client_ip_address_str, (int)clientPort,
        server_host_name_str, server_ip_address_str, (int)serverPort);
    pthread_mutex_unlock(&conn->connect_mutex);
    
    (*env)->ReleaseStringUTFChars(env, host, host_str);
    (*env)->ReleaseStringUTFChars(env, username, username_str);
    (*env)->ReleaseStringUTFChars(env, password, password_str);
    (*env)->ReleaseStringUTFChars(env, hubName, hub_name_str);
    (*env)->ReleaseStringUTFChars(env, clientProductName, client_product_name_str);
    (*env)->ReleaseStringUTFChars(env, clientVersion, client_version_str);
    (*env)->ReleaseStringUTFChars(env, clientOsName, client_os_name_str);
    (*env)->ReleaseStringUTFChars(env, clientOsVersion, client_os_version_str);
    (*env)->ReleaseStringUTFChars(env, clientOsProductId, client_os_product_id_str);
    (*env)->ReleaseStringUTFChars(env, clientHostName, client_host_name_str);
    (*env)->ReleaseStringUTFChars(env, clientIpAddress, client_ip_address_str);
    (*env)->ReleaseStringUTFChars(env, serverHostName, server_host_name_str);
    (*env)->ReleaseStringUTFChars(env, serverIpAddress, server_ip_address_str);
    
    return result;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetAuthType(
    JNIEnv *env, jobject thiz, jlong handle, jint authType) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_set_auth_type(conn, (int)authType);
    LOGD("Auth type set to %d via JNI", (int)authType);
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDisconnect(
    JNIEnv *env, jobject thiz, jlong handle) {
    LOGD("nativeDisconnect called");
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    
    // Check if connection is in a state that can be disconnected
    softether_state_t state = softether_get_state(conn);
    if (state == STATE_DISCONNECTED || state == STATE_DISCONNECTING) {
        LOGD("Connection already disconnected or disconnecting, state=%s", 
             softether_state_string(state));
        return;
    }
    
    LOGD("Disconnecting connection in state: %s", softether_state_string(state));
    softether_disconnect(conn);
    LOGD("nativeDisconnect completed");
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetState(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        return STATE_DISCONNECTED;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    return (jint)softether_get_state(conn);
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSend(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    
    jbyte* data_bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (data_bytes == NULL) {
        LOGE("Failed to get data bytes");
        return -1;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_send(conn, (const uint8_t*)data_bytes, (size_t)length);
    
    (*env)->ReleaseByteArrayElements(env, data, data_bytes, JNI_ABORT);
    
    return result;
}

// Phase 13E: send a slice of the Java buffer without any copy on the
// Kotlin side (TUN read loop passes its scratch buffer + offset directly).
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSendSlice(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint offset, jint length) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    if (offset < 0 || length < 0) {
        LOGE("Invalid offset/length");
        return -1;
    }

    jsize array_len = (*env)->GetArrayLength(env, data);
    if ((jlong)offset + length > array_len) {
        LOGE("Slice out of bounds: offset=%d len=%d array=%d", offset, length, array_len);
        return -1;
    }

    jbyte* data_bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (data_bytes == NULL) {
        LOGE("Failed to get data bytes");
        return -1;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_send(conn, (const uint8_t*)data_bytes + offset, (size_t)length);

    (*env)->ReleaseByteArrayElements(env, data, data_bytes, JNI_ABORT);

    return result;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceive(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint maxLength) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    
    jbyte* buffer_bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buffer_bytes == NULL) {
        LOGE("Failed to get buffer bytes");
        return -1;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    int result = softether_receive(conn, (uint8_t*)buffer_bytes, (size_t)maxLength);
    
    if (result > 0) {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, 0);
    } else {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, JNI_ABORT);
    }
    
    return result;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSocketFd(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        LOGE("Invalid handle for getSocketFd");
        return -1;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    return conn->socket_fd;
}

// Phase 13D: receive multiple frames per JNI crossing.
// Returns total bytes written into `buffer` (0 = nothing available), or -1 on
// error. lengths[0..n-1] hold per-frame sizes; entries beyond n are zeroed,
// so callers detect the frame count by scanning for the first zero.
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceiveBatch(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint maxLength,
    jintArray lengths, jint maxPackets) {
    if (handle == 0) {
        LOGE("Invalid handle");
        return -1;
    }
    if (buffer == NULL || lengths == NULL || maxPackets <= 0 || maxLength <= 0) {
        LOGE("Invalid arguments for receiveBatch");
        return -1;
    }

    jbyte* buffer_bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buffer_bytes == NULL) {
        LOGE("Failed to get buffer bytes");
        return -1;
    }
    jint* length_elems = (*env)->GetIntArrayElements(env, lengths, NULL);
    if (length_elems == NULL) {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, JNI_ABORT);
        LOGE("Failed to get lengths array");
        return -1;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    uint32_t count = 0;
    int result = softether_receive_batch(conn, (uint8_t*)buffer_bytes, (uint32_t)maxLength,
                                         (uint32_t*)length_elems, (uint32_t)maxPackets,
                                         &count);

    (*env)->ReleaseIntArrayElements(env, lengths, length_elems, 0);
    if (result > 0) {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, 0);
    } else {
        (*env)->ReleaseByteArrayElements(env, buffer, buffer_bytes, JNI_ABORT);
    }

    return result;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetMaxConnection(
    JNIEnv *env, jobject thiz, jlong handle, jint maxConnections) {
    if (handle == 0) {
        LOGE("Invalid handle for setMaxConnection");
        return;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    if (maxConnections < 1) maxConnections = 1;
    if (maxConnections > MAX_SE_CONNECTIONS) maxConnections = MAX_SE_CONNECTIONS;
    conn->max_connection = maxConnections;
    LOGD("Set max_connection to %d", conn->max_connection);
}

// Session / multi-connection options from the profile (SoftEther Windows client
// equivalents): number of TCP connections, additional-connection interval, and
// the login-PACK flags for SSL session encryption, compression and QoS.
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSessionOptions(
    JNIEnv *env, jobject thiz, jlong handle, jint maxConnections, jint intervalMs,
    jboolean useEncrypt, jboolean useCompress, jboolean qos) {
    if (handle == 0) {
        LOGE("Invalid handle for setSessionOptions");
        return;
    }
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_set_session_options(conn, maxConnections, intervalMs,
                                  useEncrypt ? 1 : 0,
                                  useCompress ? 1 : 0,
                                  qos ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNumConnections(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return 0;
    softether_connection_t* conn = (softether_connection_t*)handle;
    return (jint)softether_get_num_connections(conn);
}

// Diagnostic snapshot of the negotiated session.
// Returns long[12]: { numConnections, requestedMaxConnection, serverMaxConnection,
//                     useSslData, serverUseEncrypt, serverUseCompress, usingNatT,
//                     additionalAttempts, additionalFailedCount,
//                     additionalLastStage, additionalLastError,
//                     additionalLastAttemptMs }
// or NULL when the handle is invalid.
JNIEXPORT jlongArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSessionInfo(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    softether_session_info_t info;
    softether_get_session_info(conn, &info);

    enum {
        IDX_NUM_CONNECTIONS, IDX_REQUESTED_MAX, IDX_SERVER_MAX,
        IDX_USE_SSL_DATA, IDX_SERVER_ENCRYPT, IDX_SERVER_COMPRESS,
        IDX_USING_NAT_T, IDX_ADDL_ATTEMPTS, IDX_ADDL_FAILED,
        IDX_ADDL_STAGE, IDX_ADDL_ERROR, IDX_ADDL_MS,
        IDX_ADDL_FAIL_STAGE, IDX_PROTECT_AVAILABLE, IDX_ADDL_PROTECT, IDX_COUNT
    };

    jlongArray arr = (*env)->NewLongArray(env, IDX_COUNT);
    if (arr == NULL) return NULL;

    jlong vals[IDX_COUNT];
    vals[IDX_NUM_CONNECTIONS] = (jlong)info.num_connections;
    vals[IDX_REQUESTED_MAX] = (jlong)info.requested_max_connection;
    vals[IDX_SERVER_MAX] = (jlong)info.server_max_connection;
    vals[IDX_USE_SSL_DATA] = (jlong)info.use_ssl_data;
    vals[IDX_SERVER_ENCRYPT] = (jlong)info.server_use_encrypt;
    vals[IDX_SERVER_COMPRESS] = (jlong)info.server_use_compress;
    vals[IDX_USING_NAT_T] = (jlong)info.using_nat_t;
    vals[IDX_ADDL_ATTEMPTS] = (jlong)info.additional_attempts;
    vals[IDX_ADDL_FAILED] = (jlong)info.additional_failed_count;
    vals[IDX_ADDL_STAGE] = (jlong)info.additional_last_stage;
    vals[IDX_ADDL_ERROR] = (jlong)info.additional_last_error;
    vals[IDX_ADDL_MS] = (jlong)info.additional_last_attempt_ms;
    vals[IDX_ADDL_FAIL_STAGE] = (jlong)info.additional_last_fail_stage;
    vals[IDX_PROTECT_AVAILABLE] = (jlong)info.protect_available;
    vals[IDX_ADDL_PROTECT] = (jlong)info.additional_last_protect;

    (*env)->SetLongArrayRegion(env, arr, 0, IDX_COUNT, vals);
    return arr;
}

JNIEXPORT jstring JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSessionDetail(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    softether_session_info_t info;
    softether_get_session_info(conn, &info);

    // The detail line itself already carries primary_local and protect, so only
    // the hook state is appended here. It used to repeat both, which doubled the
    // length of every "Addl detail" line without adding information.
    char detail[320];
    snprintf(detail, sizeof(detail), "%s | hook=%s",
             info.additional_last_detail[0] ? info.additional_last_detail : "(none)",
             info.protect_available ? "installed" : "MISSING");
    return (*env)->NewStringUTF(env, detail);
}

// Reason the last data-link read failed ("primary fd=42 revents=0x10 (POLLHUP)").
// Null when no link has failed. The Kotlin receive loop only sees a bare -1, so
// this is what names the link and the cause in the app log.
JNIEXPORT jstring JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetLastLinkError(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    char detail[160];
    softether_get_last_link_error(conn, detail, (int)sizeof(detail));
    if (detail[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, detail);
}

// Per-link traffic split. Returns long[2*(MAX_SE_CONNECTIONS+1)]:
//   [0 .. MAX]                    -> tx bytes per link (0 = primary, i+1 = slot i)
//   [MAX+1 .. 2*MAX+1]            -> rx bytes per link
// or NULL when the handle is invalid. Used by the app log to show whether the
// extra TCP connections actually carry traffic.
JNIEXPORT jlongArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetLinkStats(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    uint64_t tx[MAX_SE_CONNECTIONS + 1];
    uint64_t rx[MAX_SE_CONNECTIONS + 1];
    int n = softether_get_link_stats(conn, tx, rx, MAX_SE_CONNECTIONS + 1);
    if (n <= 0) return NULL;

    jlong vals[2 * (MAX_SE_CONNECTIONS + 1)];
    for (int i = 0; i < n; i++) {
        vals[i] = (jlong)tx[i];
        vals[n + i] = (jlong)rx[i];
    }

    jlongArray arr = (*env)->NewLongArray(env, 2 * n);
    if (arr == NULL) return NULL;
    (*env)->SetLongArrayRegion(env, arr, 0, 2 * n, vals);
    return arr;
}

JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetAllSocketFds(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    int fds[MAX_SE_CONNECTIONS + 1];
    int count = softether_get_active_socket_fds(conn, fds, MAX_SE_CONNECTIONS + 1);

    if (count == 0) return NULL;

    jintArray arr = (*env)->NewIntArray(env, count);
    if (arr == NULL) return NULL;

    jint* jfds = (jint*)malloc(sizeof(jint) * count);
    if (jfds == NULL) return NULL;
    for (int i = 0; i < count; i++) {
        jfds[i] = (jint)fds[i];
    }
    (*env)->SetIntArrayRegion(env, arr, 0, count, jfds);
    free(jfds);

    LOGD("nativeGetAllSocketFds: returned %d FDs", count);
    return arr;
}

// Phase 13G: permanent traffic/health counters.
// Returns long[9]: { txPackets, txBytes, rxPackets, rxBytes, rxSkippedBlocks,
//                    rudpOverflowCount, rudpRxPackets, rudpTickGaps, rudpDataSuspended }
// or NULL when the handle is invalid.
JNIEXPORT jlongArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetStats(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;

    softether_stats_t st;
    softether_get_stats(conn, &st);

    enum {
        IDX_TX_PACKETS, IDX_TX_BYTES, IDX_RX_PACKETS, IDX_RX_BYTES,
        IDX_RX_SKIPPED, IDX_RUDP_OVERFLOW, IDX_RUDP_RX, IDX_RUDP_GAPS,
        IDX_RUDP_SUSPENDED, IDX_COUNT
    };

    jlongArray arr = (*env)->NewLongArray(env, IDX_COUNT);
    if (arr == NULL) return NULL;

    jlong vals[IDX_COUNT];
    vals[IDX_TX_PACKETS] = (jlong)st.tx_packets;
    vals[IDX_TX_BYTES] = (jlong)st.tx_bytes;
    vals[IDX_RX_PACKETS] = (jlong)st.rx_packets;
    vals[IDX_RX_BYTES] = (jlong)st.rx_bytes;
    vals[IDX_RX_SKIPPED] = (jlong)st.rx_skipped_blocks;
    vals[IDX_RUDP_OVERFLOW] = (jlong)st.rudp_overflow_count;
    vals[IDX_RUDP_RX] = (jlong)st.rudp_rx_packets;
    vals[IDX_RUDP_GAPS] = (jlong)st.rudp_tick_gaps;
    vals[IDX_RUDP_SUSPENDED] = (jlong)st.rudp_data_suspended;

    (*env)->SetLongArrayRegion(env, arr, 0, IDX_COUNT, vals);
    return arr;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetRudpSocketFd(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return -1;
    softether_connection_t* conn = (softether_connection_t*)handle;
    if (conn->rudp) {
        return rudp_get_udp_fd(conn->rudp);
    }
    return -1;
}

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNatTUdpSocketFd(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return -1;
    softether_connection_t* conn = (softether_connection_t*)handle;
    if (conn->using_nat_t && conn->nat_t_transport != NULL) {
        return rudp_transport_get_udp_fd(conn->nat_t_transport);
    }
    return -1;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(
    JNIEnv *env, jobject thiz, jlong handle, jint option, jlong value) {
    LOGD("nativeSetOption called: option=%d, value=%ld", option, (long)value);
    
    if (handle == 0) {
        LOGE("Invalid handle");
        return;
    }
    
    softether_connection_t* conn = (softether_connection_t*)handle;
    
    switch (option) {
        case 1: // OPTION_TIMEOUT
            conn->timeout_ms = (int)value;
            LOGD("Set timeout to %d ms", conn->timeout_ms);
            break;
        case 4: // OPTION_UDP_PORT (seUdpPort) — enables the direct R-UDP stage
            conn->udp_port = (int)value;
            LOGD("Set UDP port to %d", conn->udp_port);
            break;
        case 5: // OPTION_UDP_ONLY — skip the doomed direct-TCP attempt
            conn->udp_only = (int)value;
            LOGD("Set UDP-only to %d", conn->udp_only);
            break;
        case 6: // OPTION_IDLE_POLL_MS — receive-loop idle wait (energy saving)
            conn->idle_poll_ms = (int)value;
            LOGD("Set idle poll to %d ms", conn->idle_poll_ms);
            break;
        default:
            LOGE("Unknown option: %d", option);
            break;
    }
}

JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDoDhcp(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) {
        LOGE("Invalid handle for DHCP");
        return NULL;
    }

    softether_connection_t* conn = (softether_connection_t*)handle;
    dhcp_result_t result;
    memset(&result, 0, sizeof(result));

    int ret = softether_do_dhcp(conn, &result);

    // After DHCP success, resolve gateway MAC via ARP
    if (ret == 0 && result.success && result.gateway != 0) {
        conn->assigned_ip = result.assigned_ip;
        LOGD("DHCP success, resolving gateway MAC...");
        int arp_ret = softether_resolve_gateway(conn, result.gateway);
        if (arp_ret != 0) {
            LOGW("Gateway ARP resolution failed, falling back to DNS server");
            if (result.dns_server != 0) {
                softether_resolve_gateway(conn, result.dns_server);
            }
        }
    }

    // Return array: [success, assigned_ip, subnet_mask, gateway, dns_server, dns_server2, lease_time]
    jintArray arr = (*env)->NewIntArray(env, 7);
    if (arr == NULL) return NULL;

    jint values[7];
    values[0] = (ret == 0 && result.success) ? 1 : 0;
    values[1] = (jint)result.assigned_ip;
    values[2] = (jint)result.subnet_mask;
    values[3] = (jint)result.gateway;
    values[4] = (jint)result.dns_server;
    values[5] = (jint)result.dns_server2;
    values[6] = (jint)result.lease_time;

    (*env)->SetIntArrayRegion(env, arr, 0, 7, values);
    return arr;
}

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeForceCloseSocket(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_force_close_socket(conn);
}

// Set a stable client MAC before connect (local-bridge ARP stability).
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetClientMac(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray mac) {
    if (handle == 0 || mac == NULL) return;
    jbyte* m = (*env)->GetByteArrayElements(env, mac, NULL);
    if (m == NULL) return;
    softether_connection_t* conn = (softether_connection_t*)handle;
    softether_set_client_mac(conn, (const uint8_t*)m);
    (*env)->ReleaseByteArrayElements(env, mac, m, JNI_ABORT);
}

// Benchmark/diagnostics: return this session's virtual MAC (6 bytes) or NULL.
JNIEXPORT jbyteArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetClientMac(
    JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return NULL;
    softether_connection_t* conn = (softether_connection_t*)handle;
    jbyteArray arr = (*env)->NewByteArray(env, 6);
    if (arr == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, 6, (jbyte*)conn->client_mac);
    return arr;
}

// Benchmark/diagnostics: send a raw L2 Ethernet frame (bypasses the
// auto-eth-header wrapping of softether_send). Returns bytes sent or -1.
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSendRaw(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length) {
    if (handle == 0 || data == NULL || length <= 0) return -1;
    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;
    softether_connection_t* conn = (softether_connection_t*)handle;
    int ret = softether_send_raw(conn, (const uint8_t*)buf, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return ret;
}
