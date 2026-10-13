#ifndef SOFTETHER_JNI_H
#define SOFTETHER_JNI_H

#include <jni.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Connection management
JNIEXPORT jlong JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeCreate(
    JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnect(
    JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port, 
    jstring username, jstring password);

JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDisconnect(
    JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetState(
    JNIEnv *env, jobject thiz, jlong handle);

// Data I/O
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSend(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length);

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceive(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint maxLength);

// Configuration
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(
    JNIEnv *env, jobject thiz, jlong handle, jint option, jlong value);

// Socket access (for VpnService.protect())
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSocketFd(
    JNIEnv *env, jobject thiz, jlong handle);

// NAT-T RUDP transport UDP socket (for VpnService.protect()); -1 when the
// connection is not running over the NAT-T transport.
JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNatTUdpSocketFd(
    JNIEnv *env, jobject thiz, jlong handle);

// Multi-connection support
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetMaxConnection(
    JNIEnv *env, jobject thiz, jlong handle, jint maxConnections);

// Session options: max connections, additional-connect interval, and the
// login-PACK flags (SSL session encryption, compression, QoS).
JNIEXPORT void JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetSessionOptions(
    JNIEnv *env, jobject thiz, jlong handle, jint maxConnections, jint intervalMs,
    jboolean useEncrypt, jboolean useCompress, jboolean qos);

// Installs (or clears, with null) the socket-protect hook: the native connect
// path calls VpnService.protect(fd) on each new socket before connecting it, so
// Android can exclude it from the VPN tunnel. Returns true when installed.
JNIEXPORT jboolean JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetProtectCallback(
    JNIEnv *env, jobject thiz, jlong handle, jobject vpnService);

JNIEXPORT jint JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNumConnections(
    JNIEnv *env, jobject thiz, jlong handle);

// Diagnostic snapshot of the negotiated session (connection count, server
// limits, data-channel TLS, accepted encryption/compression flags).
JNIEXPORT jlongArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSessionInfo(
    JNIEnv *env, jobject thiz, jlong handle);

// Human-readable specifics of the last finished additional-connect attempt
// (target address, errno, OpenSSL reason, HTTP status, local socket address).
JNIEXPORT jstring JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSessionDetail(
    JNIEnv *env, jobject thiz, jlong handle);

// Per-link traffic split: long[2*(MAX_SE_CONNECTIONS+1)], tx bytes first then
// rx bytes (index 0 = primary, i+1 = additional slot i).
JNIEXPORT jlongArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetLinkStats(
    JNIEnv *env, jobject thiz, jlong handle);

// Reason the last data-link read failed, or null when no link has failed.
JNIEXPORT jstring JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetLastLinkError(
    JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetAllSocketFds(
    JNIEnv *env, jobject thiz, jlong handle);

// DHCP over SoftEther tunnel
JNIEXPORT jintArray JNICALL Java_vn_unlimit_softether_client_SoftEtherClient_nativeDoDhcp(
    JNIEnv *env, jobject thiz, jlong handle);

// Test functions for instrumentation tests
JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestTcpConnection(
    JNIEnv *env, jobject thiz, jstring host, jint port, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestTlsHandshake(
    JNIEnv *env, jobject thiz, jstring host, jint port, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestSoftEtherHandshake(
    JNIEnv *env, jobject thiz, jstring host, jint port, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestAuthentication(
    JNIEnv *env, jobject thiz, jstring host, jint port, 
    jstring username, jstring password, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestSession(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestDataTransmission(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint packetCount, 
    jint packetSize, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestKeepalive(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint durationSeconds, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestFullLifecycle(
    JNIEnv *env, jobject thiz, jstring host, jint port,
    jstring username, jstring password, jint timeoutMs);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestRudpV2Loopback(
    JNIEnv *env, jobject thiz);

JNIEXPORT jobject JNICALL Java_vn_unlimit_softether_test_NativeConnectionTest_nativeTestRudpTransportLoopback(
    JNIEnv *env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // SOFTETHER_JNI_H
