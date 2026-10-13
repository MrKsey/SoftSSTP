/*
 * SoftEther VPN Data Channel - Block Format
 *
 * Real SoftEther uses a simple block-count + length-prefixed format for data
 * after the HTTP-based authentication phase:
 *
 *   [uint32 block_count]              -- number of blocks, big-endian
 *   [uint32 block_size_1][block_data_1]
 *   [uint32 block_size_2][block_data_2]
 *   ...
 *
 * Keepalive uses a special magic value:
 *   [0xFFFFFFFF]                      -- KEEP_ALIVE_MAGIC
 *   [uint32 keepalive_size][keepalive_data]
 */
#include "softether_protocol.h"
#include "softether_socket.h"
#include "softether_crypto.h"
#include "softether_compress.h"
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <poll.h>
#include <android/log.h>

#define TAG "SoftEtherPacket"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Atomically claim ownership of a shared SSL context slot. Exactly one
// concurrent caller receives the pointer and must destroy it; all others get
// NULL. Prevents double-destroy races when e.g. fill_recv_queue's error path,
// transmit_block's failover, and close_additional all retire the same socket.
static void* take_ssl_ctx_slot(void** slot) {
    void* p = *slot;
    if (p == NULL) return NULL;
    return __sync_bool_compare_and_swap(slot, p, NULL) ? p : NULL;
}

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Per-packet trace logging (Phase 13A). A formatted logd write per packet
// dominates mobile throughput, so these are compiled out unless
// SE_TRACE_PACKETS is defined (CMake option).
#ifdef SE_TRACE_PACKETS
#define LOGT(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#else
#define LOGT(...) ((void)0)
#endif

#define KEEP_ALIVE_MAGIC 0xFFFFFFFF
#define MAX_BLOCK_SIZE   (1600 * 1600)  // same as SoftEther MAX_PACKET_SIZE safety
#define COMPRESS_MAGIC   0xDEADBEEFCAFEFACELL

// Read exactly `len` bytes from the SSL connection.
// Returns 0 on success, SE_SSL_READ_TIMEOUT when the socket timed out before any
// byte of this block arrived (no data yet), -1 on error/partial read.
static int ssl_read_all(softether_connection_t* conn, uint8_t* buf, int len) {
    int total = 0;
    while (total < len) {
        if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) {
            return -1;
        }
        int ret = ssl_read((ssl_context_t*)conn->ssl, buf + total, len - total);
        if (ret == SE_SSL_READ_TIMEOUT) {
            // Nothing arrived within the socket timeout. If part of the block is
            // already in hand the stream is desynced — a real error; otherwise
            // this is simply "no data yet" and the link stays alive.
            return (total == 0) ? SE_SSL_READ_TIMEOUT : -1;
        }
        if (ret <= 0) {
            return -1;
        }
        total += ret;
    }
    return 0;
}

// Write exactly `len` bytes to the SSL connection. Returns 0 on success, -1 on error.
static int ssl_write_all(softether_connection_t* conn, const uint8_t* buf, int len) {
    int total = 0;
    LOGT("ssl_write_all: writing %d bytes (first 8: %02X %02X %02X %02X %02X %02X %02X %02X)",
         len,
         len > 0 ? buf[0] : 0, len > 1 ? buf[1] : 0,
         len > 2 ? buf[2] : 0, len > 3 ? buf[3] : 0,
         len > 4 ? buf[4] : 0, len > 5 ? buf[5] : 0,
         len > 6 ? buf[6] : 0, len > 7 ? buf[7] : 0);
    while (total < len) {
        if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) {
            LOGE("ssl_write_all: connection state %d", conn->state);
            return -1;
        }
        int ret = ssl_write((ssl_context_t*)conn->ssl, buf + total, len - total);
        if (ret <= 0) {
            LOGE("ssl_write_all: ssl_write returned %d (wrote %d/%d)", ret, total, len);
            return -1;
        }
        LOGT("ssl_write_all: ssl_write returned %d (progress %d/%d)", ret, total + ret, len);
        total += ret;
    }
    return 0;
}

// Per-link io_mutex for a raw (non-SSL) socket, resolved by fd. Defined below.
static pthread_mutex_t* link_io_resolve_fd(softether_connection_t* conn, int socket_fd);

// Record why a data link failed to read, so the app log can name the link and
// the cause instead of a bare native=-1. `add_idx` is the additional slot, or
// -1 for the primary link. `detail` is a short pre-formatted reason.
static void record_link_error(softether_connection_t* conn, int add_idx,
                              const char* detail) {
    if (conn == NULL || detail == NULL) return;
    if (add_idx >= 0) {
        snprintf(conn->last_link_error, sizeof(conn->last_link_error),
                 "additional[%d] %s", add_idx, detail);
    } else {
        snprintf(conn->last_link_error, sizeof(conn->last_link_error),
                 "primary %s", detail);
    }
}


// Read exactly `len` bytes from raw TCP socket.
// Returns 0 on success, SE_SSL_READ_TIMEOUT when the socket timed out before any
// byte arrived (no data yet), -1 on error/partial read.
static int raw_read_all(softether_connection_t* conn, uint8_t* buf, int len) {
    int total = 0;
    while (total < len) {
        if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) {
            return -1;
        }
        int ret = (int)recv(conn->socket_fd, buf + total, len - total, 0);
        if (ret < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // SO_RCVTIMEO expired: no data yet (see SE_SSL_READ_TIMEOUT).
                return (total == 0) ? SE_SSL_READ_TIMEOUT : -1;
            }
            LOGE("raw_read_all: recv returned %d (errno=%d)", ret, errno);
            return -1;
        }
        if (ret == 0) {
            LOGE("raw_read_all: peer closed (recv=0)");
            return -1;
        }
        total += ret;
    }
    return 0;
}

// Write exactly `len` bytes to raw TCP socket. Returns 0 on success, -1 on error.
//
// The write runs under the link's io_mutex even though raw mode has no SSL
// object to protect. A socket has several writers on different threads: the TUN
// send path (holding write_mutex), the periodic keepalive sweep and the ARP
// responder (both driven by the receive loop, holding nothing). A block the
// kernel accepts only partially — which is exactly what happens once the send
// buffer fills during a saturated upload — would otherwise let another thread's
// bytes land in the middle of it, and the server resets the session on the
// desynchronized stream. That was the "connection drops during a speed test"
// symptom, and it only ever hit raw-TCP sessions, never TLS ones (the SSL path
// already held this lock).
static int raw_write_all(softether_connection_t* conn, const uint8_t* buf, int len) {
    pthread_mutex_t* io = link_io_resolve_fd(conn, conn->socket_fd);
    if (io != NULL) pthread_mutex_lock(io);

    int rc = 0;
    int total = 0;
    while (total < len) {
        if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) {
            rc = -1;
            break;
        }
        int ret = (int)send(conn->socket_fd, buf + total, len - total, 0);
        if (ret <= 0) {
            // EAGAIN = SO_SNDTIMEO expired; report failure instead of spinning.
            if (ret < 0 && errno == EINTR) {
                continue;
            }
            LOGE("raw_write_all: send returned %d (errno=%d)", ret, errno);
            rc = -1;
            break;
        }
        total += ret;
    }

    if (io != NULL) pthread_mutex_unlock(io);
    return rc;
}

// ---- Socket-specific read helpers (for multi-connection) ----

// Read exactly `len` bytes from a specific SSL context (not conn->ssl).
// Returns 0 on success, SE_SSL_READ_TIMEOUT when the socket timed out before any
// byte arrived (no data yet), -1 on error/partial read.
static int ssl_read_all_ctx(ssl_context_t* ssl, uint8_t* buf, int len) {
    int total = 0;
    while (total < len) {
        int ret = ssl_read(ssl, buf + total, len - total);
        if (ret == SE_SSL_READ_TIMEOUT) {
            return (total == 0) ? SE_SSL_READ_TIMEOUT : -1;
        }
        if (ret <= 0) {
            return -1;
        }
        total += ret;
    }
    return 0;
}

// Read exactly `len` bytes from a specific raw TCP fd (not conn->socket_fd).
// EAGAIN means SO_RCVTIMEO expired (or the caller polled but data was consumed):
// bail out instead of spinning, so a stalled link cannot hang the receive loop.
// Returns 0 on success, SE_SSL_READ_TIMEOUT when nothing arrived (no data yet),
// -1 on error/partial read.
static int raw_read_all_fd(int fd, uint8_t* buf, int len) {
    int total = 0;
    while (total < len) {
        int ret = (int)recv(fd, buf + total, len - total, 0);
        if (ret < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return (total == 0) ? SE_SSL_READ_TIMEOUT : -1;
            }
            return -1;
        }
        if (ret == 0) {
            return -1;
        }
        total += ret;
    }
    return 0;
}

// ---- Per-link I/O locking (P1#5) ----
// Every SSL read/write/keepalive on a live TCP link takes that link's own
// io_mutex (conn->io_mutex for the primary socket, additional[i].io_mutex for
// the extra links). The old session-wide write_mutex was shared by ALL links and
// also by SSL reads, so a read on link A blocked sends on link B and a slow
// SSL_write on one link stalled staging/keepalives on healthy links. With a
// per-link mutex, I/O on different links is fully independent while I/O on the
// SAME SSL object stays mutually exclusive (BoringSSL forbids concurrent ops on
// one context). The staging/teardown write_mutex is unchanged. io_mutex is a
// strict leaf lock (never held while acquiring write_mutex / connect_mutex /
// ssl_lifetime_lock), and retirement of a socket NULLs its ssl/socket_fd while
// holding the SAME io_mutex.

// Resolve a caller-captured ssl/fd pair to its per-link I/O mutex. The slot
// pointers are read without the lock, so the caller must re-validate under the
// lock (link_io_live) before dereferencing the SSL — a concurrent retire holds
// the same io_mutex while NULLing the slot. Returns NULL when nothing matches
// (the socket was already retired) and sets *slot_idx = -1 for primary.
static pthread_mutex_t* link_io_resolve(softether_connection_t* conn,
                                        void* ssl, int socket_fd,
                                        int* slot_idx) {
    if (ssl == conn->ssl && socket_fd == conn->socket_fd) {
        *slot_idx = -1;
        return &conn->io_mutex;
    }
    for (int i = 0; i < MAX_SE_CONNECTIONS; i++) {
        if (conn->additional[i].ssl == ssl &&
            conn->additional[i].socket_fd == socket_fd) {
            *slot_idx = i;
            return &conn->additional[i].io_mutex;
        }
    }
    *slot_idx = -1;
    return NULL;
}

// Re-check, while holding the link's io_mutex, that the slot still references
// the caller's captured ssl/fd. 0 if still live, -1 if already retired.
static int link_io_live(softether_connection_t* conn, int slot_idx,
                        void* ssl, int socket_fd) {
    if (slot_idx < 0) {
        return (conn->ssl == ssl && conn->socket_fd == socket_fd) ? 0 : -1;
    }
    return (conn->additional[slot_idx].ssl == ssl &&
            conn->additional[slot_idx].socket_fd == socket_fd) ? 0 : -1;
}

// Per-link io_mutex for a raw (non-SSL) socket. Raw mode has no SSL context to
// key on, so the fd is the link identity. A retired slot has socket_fd == -1 and
// therefore never matches. Returns NULL when the fd belongs to no live link.
static pthread_mutex_t* link_io_resolve_fd(softether_connection_t* conn, int socket_fd) {
    if (conn == NULL || socket_fd < 0) return NULL;
    if (socket_fd == conn->socket_fd) return &conn->io_mutex;
    for (int i = 0; i < MAX_SE_CONNECTIONS; i++) {
        if (conn->additional[i].socket_fd == socket_fd) {
            return &conn->additional[i].io_mutex;
        }
    }
    return NULL;
}

// Read `len` bytes from a specific socket (SSL or raw).
static int data_read_all_sock(softether_connection_t* conn,
                              void* ssl, int socket_fd,
                              uint8_t* buf, int len) {
    if (!conn->use_ssl_data) {
        return raw_read_all_fd(socket_fd, buf, len);
    }

    int slot_idx = -1;
    pthread_mutex_t* io = link_io_resolve(conn, ssl, socket_fd, &slot_idx);
    if (io == NULL) {
        return -1;  // socket already retired by another thread
    }

    pthread_mutex_lock(io);
    if (link_io_live(conn, slot_idx, ssl, socket_fd) != 0) {
        pthread_mutex_unlock(io);
        return -1;  // retired while we resolved — don't touch a freed context
    }
    int ret = ssl_read_all_ctx((ssl_context_t*)ssl, buf, len);
    pthread_mutex_unlock(io);
    return ret;
}

// Read a big-endian uint32 from a specific socket.
// Returns 0 on success, SE_SSL_READ_TIMEOUT when no byte arrived (no data yet),
// -1 on error.
static int read_uint32_sock(softether_connection_t* conn,
                            void* ssl, int socket_fd,
                            uint32_t* out) {
    uint8_t buf[4];
    int r = data_read_all_sock(conn, ssl, socket_fd, buf, 4);
    if (r != 0) return r;
    *out = ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16) |
           ((uint32_t)buf[2] << 8) | (uint32_t)buf[3];
    return 0;
}

// Write exactly `len` bytes to a specific socket (SSL or raw).
static int data_write_all_sock(softether_connection_t* conn,
                               void* ssl, int socket_fd,
                               const uint8_t* buf, int len) {
    if (!conn->use_ssl_data) {
        // Same serialization rationale as raw_write_all(): several threads write
        // to this socket (send path, keepalive sweep, ARP responder), and a
        // partial send under load would let their bytes interleave.
        pthread_mutex_t* io = link_io_resolve_fd(conn, socket_fd);
        if (io != NULL) pthread_mutex_lock(io);
        int rc = 0;
        int total = 0;
        while (total < len) {
            int ret = (int)send(socket_fd, buf + total, len - total, 0);
            if (ret <= 0) {
                // EAGAIN = SO_SNDTIMEO expired: report failure instead of
                // spinning forever on a saturated or stalled link.
                if (ret < 0 && errno == EINTR) continue;
                rc = -1;
                break;
            }
            total += ret;
        }
        if (io != NULL) pthread_mutex_unlock(io);
        return rc;
    }

    int slot_idx = -1;
    pthread_mutex_t* io = link_io_resolve(conn, ssl, socket_fd, &slot_idx);
    if (io == NULL) {
        return -1;  // socket already retired by another thread
    }

    pthread_mutex_lock(io);
    if (link_io_live(conn, slot_idx, ssl, socket_fd) != 0) {
        pthread_mutex_unlock(io);
        return -1;  // retired while we resolved — don't touch a freed context
    }
    int total = 0;
    while (total < len) {
        int ret = ssl_write((ssl_context_t*)ssl, buf + total, len - total);
        if (ret <= 0) {
            pthread_mutex_unlock(io);
            return -1;
        }
        total += ret;
    }
    pthread_mutex_unlock(io);
    return 0;
}

// Read `len` bytes using the appropriate channel (SSL or raw TCP).
// Primary-socket variant: locks conn->io_mutex for SSL reads so a concurrent
// SSL_write on the primary (same SSL object) stays exclusive.
static int data_read_all(softether_connection_t* conn, uint8_t* buf, int len) {
    if (!conn->use_ssl_data) {
        return raw_read_all(conn, buf, len);
    }
    pthread_mutex_lock(&conn->io_mutex);
    int ret = ssl_read_all(conn, buf, len);
    pthread_mutex_unlock(&conn->io_mutex);
    return ret;
}

// Write `len` bytes using the appropriate channel (SSL or raw TCP).
// Primary-socket variant: locks conn->io_mutex (same rationale as above).
static int data_write_all(softether_connection_t* conn, const uint8_t* buf, int len) {
    if (!conn->use_ssl_data) {
        return raw_write_all(conn, buf, len);
    }
    pthread_mutex_lock(&conn->io_mutex);
    int ret = ssl_write_all(conn, buf, len);
    pthread_mutex_unlock(&conn->io_mutex);
    return ret;
}

// Read a big-endian uint32 from the data channel.
// Returns 0 on success, SE_SSL_READ_TIMEOUT when no byte arrived (no data yet),
// -1 on error.
static int read_uint32(softether_connection_t* conn, uint32_t* out) {
    uint8_t buf[4];
    int r = data_read_all(conn, buf, 4);
    if (r != 0) return r;
    *out = ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16) |
           ((uint32_t)buf[2] << 8) | (uint32_t)buf[3];
    return 0;
}


// Transmit a fully built block ([block_count][block_size][payload]) over the
// best TCP socket, with failover to healthy sockets. Caller MUST hold
// write_mutex (the Phase 13C send_block staging buffer is built under it).
int softether_transmit_block_nolock(softether_connection_t* conn,
                                    const uint8_t* block, uint32_t block_len) {
    if (conn == NULL || block == NULL || block_len < 8) {
        return -1;
    }

    // Recheck state and SSL inside mutex (disconnect may have freed them)
    if (conn->state == STATE_DISCONNECTING || conn->ssl == NULL) {
        return -1;
    }

    // Select best socket for multi-connection send, with failover: if the
    // selected socket (or any candidate) fails — e.g. the server hung up an
    // additional connection — retire it and retry on the remaining healthy
    // sockets instead of failing the whole session and forcing a reconnect.
    int best = softether_select_send_socket(conn);

    // Build the candidate list (send-capable sockets only), best socket first
    int candidates[MAX_SE_CONNECTIONS + 1];
    int count = 0;
    if (best != 0) candidates[count++] = best;
    {
        int pd = conn->primary_direction;
        if (conn->socket_fd >= 0 && conn->ssl != NULL &&
            (pd == TCP_DIRECTION_BOTH || pd == TCP_DIRECTION_CLIENT_TO_SERVER)) {
            candidates[count++] = 0;
        }
        for (int i = 0; i < MAX_SE_CONNECTIONS; i++) {
            softether_tcp_sock_t* ts = &conn->additional[i];
            if (!ts->active) continue;
            int d = ts->direction;
            if (d != TCP_DIRECTION_BOTH && d != TCP_DIRECTION_CLIENT_TO_SERVER) continue;
            int idx = i + 1;
            if (idx == best) continue;
            candidates[count++] = idx;
        }
    }

    for (int ci = 0; ci < count; ci++) {
        int send_idx = candidates[ci];
        void* send_ssl;
        int send_fd;
        softether_tcp_sock_t* ts = NULL;

        if (send_idx == 0) {
            if (conn->ssl == NULL || conn->socket_fd < 0) continue;
            send_ssl = conn->ssl;
            send_fd = conn->socket_fd;
        } else {
            ts = &conn->additional[send_idx - 1];
            __sync_synchronize();  // load-acquire: ensure ssl/fd reads are ordered
            if (!ts->active || ts->ssl == NULL || ts->socket_fd < 0) continue;
            send_ssl = ts->ssl;
            send_fd = ts->socket_fd;
        }

        {
            int wr = (send_idx == 0)
                ? data_write_all(conn, block, (int)block_len)
                : data_write_all_sock(conn, send_ssl, send_fd, block, (int)block_len);
            if (wr == 0) {
                // Attribute the bytes to the link that actually carried them, so
                // the app log can show how traffic spreads across the session.
                conn->link_tx_bytes[send_idx] += block_len;
                return 0;
            }
        }

        // Write failed on this socket. Retire failed additional sockets so
        // later sends skip them; a dead primary falls through to others.
        // The retire runs under the link's io_mutex: a concurrent reader or
        // keepalive may be mid-IO on this same socket (those paths take only
        // the per-link lock), so NULLing the slot + destroying the SSL must be
        // exclusive with them too.
        LOGW("transmit_block: write failed on %s socket fd=%d, failing over",
             send_idx == 0 ? "primary" : "additional", send_fd);
        if (ts != NULL) {
            pthread_mutex_lock(&ts->io_mutex);
            // Re-check: the slot is still this socket (a reader's CLOSE_FAILED
            // path may have retired it while we waited for the io lock).
            if (ts->ssl == send_ssl && ts->socket_fd == send_fd) {
                int saved_fd = ts->socket_fd;
                ts->active = 0;
                ts->ssl = NULL;
                ts->socket_fd = -1;
                __sync_synchronize();
                if (saved_fd >= 0) close(saved_fd);
                ssl_context_t* doomed = (ssl_context_t*)take_ssl_ctx_slot(&ts->ssl_ctx);
                if (doomed != NULL) {
                    ssl_shutdown(doomed);
                    ssl_destroy(doomed);
                }
                conn->num_additional--;
            }
            pthread_mutex_unlock(&ts->io_mutex);
        }
    }

    LOGE("Failed to send data block: no healthy send socket");
    return -1;
}

// Public wrapper: locks write_mutex around the failover transmit.
int softether_transmit_block(softether_connection_t* conn,
                             const uint8_t* block, uint32_t block_len) {
    if (conn == NULL) return -1;
    pthread_mutex_lock(&conn->write_mutex);
    int ret = softether_transmit_block_nolock(conn, block, block_len);
    pthread_mutex_unlock(&conn->write_mutex);
    return ret;
}

// Send one data block (Ethernet frame) using the real SoftEther block format.
// Format: [block_count=1][block_size][block_data]
// Thread-safe: locks write_mutex around building into the shared send_block
// staging buffer AND transmitting it, so the control path (ARP replies, DHCP)
// can't interleave into a half-built data block from the TUN send thread. The
// socket write itself then takes only the target link's io_mutex (inside the
// data_write helpers), so a read on another link proceeds concurrently.
// Phase 13C: builds into the preallocated conn->send_block scratch — no heap
// allocation in steady state. The zlib branch only runs when session
// compression was negotiated (off by default since Phase 13B).
int softether_send_packet(softether_connection_t* conn, uint16_t command,
                          const uint8_t* payload, uint32_t payload_len) {
    if (conn == NULL) {
        LOGE("Connection is NULL");
        return -1;
    }
    if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) {
        LOGE("Cannot send: connection state %d", conn->state);
        return -1;
    }

    // For keepalive, use the magic format (has its own mutex lock)
    if (command == CMD_KEEPALIVE || command == CMD_KEEPALIVE_ACK) {
        return softether_send_keepalive(conn);
    }

    pthread_mutex_lock(&conn->write_mutex);

    // Recheck state and SSL inside mutex (disconnect may have freed them)
    if (conn->state == STATE_DISCONNECTING || conn->ssl == NULL) {
        pthread_mutex_unlock(&conn->write_mutex);
        return -1;
    }

    // TCP path: session-level compression (raw zlib, no magic prefix)
    // VPN Gate server fork sends/receives raw zlib when use_compress is set.
    const uint8_t* send_payload = payload;
    uint32_t send_len = payload_len;
    uint8_t* comp_buf = NULL;

    if (conn->server_use_compress && payload_len > 1) {
        uint32_t comp_bound = calc_compress_bound(payload_len);
        comp_buf = (uint8_t*)malloc(comp_bound);
        if (comp_buf != NULL) {
            uint32_t comp_len = comp_bound;
            if (compress_data(payload, payload_len, comp_buf, &comp_len) == 0) {
                send_payload = comp_buf;
                send_len = comp_len;
            } else {
                free(comp_buf);
                comp_buf = NULL;
            }
        }
    }

    // Build entire data block in the preallocated staging buffer
    // Format: [block_count=1(4)][block_size(4)][block_data(send_len)]
    uint32_t total_size = 4 + 4 + send_len;
    if (conn->send_block == NULL || total_size > conn->send_block_cap) {
        LOGE("No preallocated send buffer (need %u bytes)", total_size);
        free(comp_buf);
        pthread_mutex_unlock(&conn->write_mutex);
        return -1;
    }
    uint8_t* buf = conn->send_block;

    // block_count = 1 in big-endian
    buf[0] = 0; buf[1] = 0; buf[2] = 0; buf[3] = 1;
    // block_size in big-endian
    buf[4] = (send_len >> 24) & 0xFF;
    buf[5] = (send_len >> 16) & 0xFF;
    buf[6] = (send_len >> 8) & 0xFF;
    buf[7] = send_len & 0xFF;
    // block data
    if (send_len > 0 && send_payload != NULL) {
        memcpy(buf + 8, send_payload, send_len);
    }

    // Select best socket for multi-connection send
    int send_idx = softether_select_send_socket(conn);
    void* send_ssl = (send_idx == 0) ? conn->ssl : conn->additional[send_idx - 1].ssl;
    int send_fd = (send_idx == 0) ? conn->socket_fd : conn->additional[send_idx - 1].socket_fd;
    __sync_synchronize();  // load-acquire: ensure ssl/fd reads are ordered

    // Revalidate: if using additional, verify it's still active
    if (send_idx > 0) {
        softether_tcp_sock_t* ts = &conn->additional[send_idx - 1];
        if (!ts->active || send_ssl == NULL) {
            free(comp_buf);
            pthread_mutex_unlock(&conn->write_mutex);
            return -1;
        }
    }

    int ret;
    if (send_idx > 0 && send_ssl != NULL) {
        // Send via additional connection
        ret = data_write_all_sock(conn, send_ssl, send_fd, buf, (int)total_size);
    } else {
        // Send via primary connection
        ret = data_write_all(conn, buf, (int)total_size);
    }

    free(comp_buf);

    pthread_mutex_unlock(&conn->write_mutex);

    if (ret != 0) {
        LOGE("Failed to send data block");
        return -1;
    }

    LOGT("Sent 1 data block (%u bytes, compressed=%d)", payload_len, (send_len < payload_len) ? 1 : 0);
    return (int)total_size;
}

// Receive data blocks from the connection.
// Returns the first data block in `payload`, sets `payload_len`.
// Sets `command` to CMD_DATA for data blocks, CMD_KEEPALIVE for keepalive.
// Returns total bytes read on success, 0 when the socket timed out with no data
// (nothing to read), -1 on error.
int softether_receive_packet(softether_connection_t* conn, uint16_t* command,
                             uint8_t* payload, uint32_t* payload_len, uint32_t max_payload) {
    if (conn == NULL || command == NULL) {
        LOGE("Invalid parameters");
        return -1;
    }
    if (conn->ssl == NULL || conn->socket_fd < 0) {
        LOGE("Socket/SSL not connected");
        return -1;
    }
    if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) {
        return -1;
    }

    // Read block count (or keepalive magic)
    uint32_t block_count = 0;
    int bc = read_uint32(conn, &block_count);
    if (bc == SE_SSL_READ_TIMEOUT) {
        // Socket timed out with no data — report "nothing to read" rather than
        // an error, so a poll-style status check keeps the link alive.
        return 0;
    }
    if (bc != 0) {
        LOGE("Failed to read block count");
        return -1;
    }

    if (block_count == KEEP_ALIVE_MAGIC) {
        // Keepalive: read size + data
        uint32_t ka_size = 0;
        if (read_uint32(conn, &ka_size) != 0) {
            LOGE("Failed to read keepalive size");
            return -1;
        }
        if (ka_size > 512) ka_size = 512;  // safety cap
        if (ka_size > 0) {
            uint8_t ka_buf[512];
            if (data_read_all(conn, ka_buf, (int)ka_size) != 0) {
                LOGE("Failed to read keepalive data");
                return -1;
            }
        }
        *command = CMD_KEEPALIVE;
        if (payload_len) *payload_len = 0;
        LOGT("Received keepalive (%u bytes)", ka_size);
        return (int)(8 + ka_size);
    }

    // Regular data blocks
    if (block_count == 0) {
        *command = CMD_DATA;
        if (payload_len) *payload_len = 0;
        return 4;
    }

    LOGT("Receiving %u data block(s)", block_count);

    int total_read = 4;
    int first_block_stored = 0;

    for (uint32_t i = 0; i < block_count; i++) {
        uint32_t block_size = 0;
        if (read_uint32(conn, &block_size) != 0) {
            LOGE("Failed to read block %u size", i);
            return -1;
        }
        total_read += 4;

        if (block_size > MAX_BLOCK_SIZE) {
            LOGE("Block %u size too large: %u", i, block_size);
            return -1;
        }

        if (!first_block_stored && payload != NULL && payload_len != NULL && block_size <= max_payload) {
            // Store first block in caller's buffer
            if (block_size > 0) {
                uint8_t* tmp_block = (uint8_t*)malloc(block_size);
                if (tmp_block == NULL) {
                    LOGE("Failed to allocate block buffer");
                    return -1;
                }
                if (data_read_all(conn, tmp_block, (int)block_size) != 0) {
                    LOGE("Failed to read block %u data", i);
                    free(tmp_block);
                    return -1;
                }

                int compressed_ok = 0;
                if (conn->server_use_compress) {
                    // Session-level compression: VPN Gate server sends raw zlib
                    uint32_t raw_len = max_payload;
                    if (uncompress_data(tmp_block, block_size,
                                        payload, &raw_len) == 0) {
                        *payload_len = raw_len;
                        free(tmp_block);
                        first_block_stored = 1;
                        total_read += (int)block_size;
                        compressed_ok = 1;
                        LOGT("receive_packet: session-decompressed block %u: %u -> %u bytes",
                             i, block_size, raw_len);
                        continue;
                    }
                    // Fallback: check for CONNECTION_BULK_COMPRESS_SIGNATURE
                    if (block_size > 8) {
                        uint64_t sig = 0;
                        for (int b = 0; b < 8; b++) {
                            sig = (sig << 8) | tmp_block[b];
                        }
                        if (sig == COMPRESS_MAGIC) {
                            raw_len = max_payload;
                            if (uncompress_data(tmp_block + 8, block_size - 8,
                                                payload, &raw_len) == 0) {
                                *payload_len = raw_len;
                                free(tmp_block);
                                first_block_stored = 1;
                                total_read += (int)block_size;
                                compressed_ok = 1;
                                LOGT("receive_packet: magic-decompressed block %u: %u -> %u bytes",
                                     i, block_size - 8, raw_len);
                                continue;
                            }
                        }
                    }
                    LOGT("receive_packet: block %u not compressed (%u bytes, first8: %02X %02X %02X %02X %02X %02X %02X %02X)",
                         i, block_size,
                         block_size > 0 ? tmp_block[0] : 0,
                         block_size > 1 ? tmp_block[1] : 0,
                         block_size > 2 ? tmp_block[2] : 0,
                         block_size > 3 ? tmp_block[3] : 0,
                         block_size > 4 ? tmp_block[4] : 0,
                         block_size > 5 ? tmp_block[5] : 0,
                         block_size > 6 ? tmp_block[6] : 0,
                         block_size > 7 ? tmp_block[7] : 0);
                }

                if (!compressed_ok) {
                    // Uncompressed — copy as-is
                    memcpy(payload, tmp_block, block_size);
                    free(tmp_block);
                }
            }
            *payload_len = block_size;
            first_block_stored = 1;
        } else {
            // Skip subsequent blocks (or if buffer too small)
            uint8_t skip_buf[2048];
            uint32_t remaining = block_size;
            while (remaining > 0) {
                uint32_t chunk = remaining > sizeof(skip_buf) ? sizeof(skip_buf) : remaining;
                if (data_read_all(conn, skip_buf, (int)chunk) != 0) {
                    LOGE("Failed to skip block %u data", i);
                    return -1;
                }
                remaining -= chunk;
            }
        }
        total_read += (int)block_size;
    }

    *command = CMD_DATA;
    LOGT("Received %u block(s), first block %u bytes, total %d bytes",
         block_count, payload_len ? *payload_len : 0, total_read);
    return total_read;
}

// Send keepalive over a specific TCP socket using the real SoftEther format:
// [0xFFFFFFFF][size][random_data]. Thread-safe: does NOT take write_mutex — the
// packet is built in a stack-local buffer (no shared send_block staging), and
// data_write_all_sock takes only the target link's io_mutex. A slow SSL_write
// on another link therefore never stalls this keepalive.
static int softether_send_keepalive_sock(softether_connection_t* conn,
                                         void* ssl, int socket_fd) {
    if (conn == NULL || ssl == NULL) {
        return -1;
    }
    if (conn->state != STATE_CONNECTED) {
        return -1;
    }

    // Build keepalive as a single buffer (matching reference ConnectionSend behavior)
    // Format: [KEEP_ALIVE_MAGIC(4)][ka_size(4)][random_data(ka_size)]
    uint32_t ka_size = (uint32_t)(rand() % 64);
    uint32_t total_size = 4 + 4 + ka_size;
    uint8_t ka_buf[136]; // 4+4+64 max

    // KEEP_ALIVE_MAGIC in big-endian
    uint32_t magic = KEEP_ALIVE_MAGIC;
    ka_buf[0] = (magic >> 24) & 0xFF;
    ka_buf[1] = (magic >> 16) & 0xFF;
    ka_buf[2] = (magic >> 8) & 0xFF;
    ka_buf[3] = magic & 0xFF;

    // ka_size in big-endian
    ka_buf[4] = (ka_size >> 24) & 0xFF;
    ka_buf[5] = (ka_size >> 16) & 0xFF;
    ka_buf[6] = (ka_size >> 8) & 0xFF;
    ka_buf[7] = ka_size & 0xFF;

    // Random payload
    for (uint32_t i = 0; i < ka_size; i++) {
        ka_buf[8 + i] = (uint8_t)(rand() & 0xFF);
    }

    // Send entire keepalive as one SSL_write (single TLS record) under the
    // per-link io_mutex (taken inside data_write_all_sock).
    int ret = data_write_all_sock(conn, ssl, socket_fd, ka_buf, (int)total_size);

    if (ret != 0) {
        LOGE("Failed to send keepalive");
        return -1;
    }
    LOGT("Sent keepalive (%u bytes payload)", ka_size);
    return (int)total_size;
}

// Send keepalive over the primary socket.
// Thread-safe: serialized per-link via the primary io_mutex (see keepalive_sock)
int softether_send_keepalive(softether_connection_t* conn) {
    if (conn == NULL || conn->ssl == NULL) {
        return -1;
    }
    return softether_send_keepalive_sock(conn, conn->ssl, conn->socket_fd);
}

// Periodically send keepalives over ALL send-capable TCP sockets (primary +
// additional C2S/BOTH). Mirrors the reference client, which sends keepalives over
// every IS_SEND_TCP_SOCK socket every GenNextKeepAliveSpan().
//
// Without this, when UDP acceleration (RUDP) carries all VPN data, the client
// never sends anything over additional uplink (C2S) TCP sockets. The server
// expects to receive on those sockets and times them out after s->Timeout
// (default 30s), which is the "lost uplink connection" symptom. The server's
// keepalives can't keep them alive because the server cannot send on C2S sockets.
int softether_send_keepalive_all(softether_connection_t* conn) {
    if (conn == NULL || conn->ssl == NULL) {
        return -1;
    }
    if (conn->state != STATE_CONNECTED) {
        return -1;
    }

    uint64_t now = softether_tick_ms();

    if (conn->next_tcp_keepalive_time == 0) {
        conn->next_tcp_keepalive_time = now;
        return 0;
    }
    if (now < conn->next_tcp_keepalive_time) {
        return 0;
    }

    // Keepalive must arrive more often than the server's per-socket timeout.
    // The server advertises its session timeout ("timeout") in the Welcome PACK.
    uint32_t timeout = conn->server_timeout != 0 ? conn->server_timeout : 30000;
    uint32_t interval = timeout / 3;
    if (interval < 2000) interval = 2000;
    if (interval > 12000) interval = 12000;

    conn->next_tcp_keepalive_time = now + interval;

    // Primary socket: send-capable if direction is BOTH or C2S
    if (conn->socket_fd >= 0 && conn->ssl != NULL) {
        int pd = conn->primary_direction;
        if (pd == TCP_DIRECTION_BOTH || pd == TCP_DIRECTION_CLIENT_TO_SERVER) {
            softether_send_keepalive_sock(conn, conn->ssl, conn->socket_fd);
        }
    }

    // Additional sockets: send-capable if direction is BOTH or C2S
    for (int i = 0; i < MAX_SE_CONNECTIONS; i++) {
        softether_tcp_sock_t* ts = &conn->additional[i];
        if (!ts->active) continue;
        int d = ts->direction;
        if (d != TCP_DIRECTION_BOTH && d != TCP_DIRECTION_CLIENT_TO_SERVER) continue;

        // Capture SSL and fd atomically — recheck active after capture
        void* cap_ssl = ts->ssl;
        int cap_fd = ts->socket_fd;
        __sync_synchronize();  // load-acquire: ensure ssl/fd stores are visible
        if (!ts->active || cap_ssl == NULL || cap_fd < 0) continue;

        softether_send_keepalive_sock(conn, cap_ssl, cap_fd);
    }

    return 0;
}

// Process keepalive — receive and handle if the next message is a keepalive.
int softether_process_keepalive(softether_connection_t* conn) {
    if (conn == NULL) return -1;

    uint16_t command;
    uint8_t buffer[256];
    uint32_t payload_len = 0;

    int result = softether_receive_packet(conn, &command, buffer, &payload_len, sizeof(buffer));
    // result 0 = socket timeout (no data, `command` not set), < 0 = error.
    if (result <= 0) return result;

    if (command == CMD_KEEPALIVE) {
        // Respond with our own keepalive
        softether_send_keepalive(conn);
        LOGD("Keepalive exchanged");
        return 0;
    }

    LOGD("Expected keepalive, got command 0x%04X", command);
    return 0;
}

// ---- Receive queue helpers ----

// Phase 14: drain as many decoded RUDP frames as possible into the connection
// receive queue (previously one frame per fill_recv_queue call, which capped
// the batched RX path at one JNI crossing per UDP datagram).
// Returns the number of frames moved.
static int drain_rudp_into_recv_queue(softether_connection_t* conn, int max_frames) {
    int moved = 0;
    if (conn == NULL || !(conn->rudp && conn->rudp_enabled)) return 0;

    rudp_poll(conn->rudp);

    uint8_t rudp_buf[MAX_QUEUED_FRAME];
    while (moved < max_frames && conn->recv_queue_count < RECV_QUEUE_SIZE) {
        uint32_t rudp_len = 0;
        int r = rudp_recv(conn->rudp, rudp_buf, &rudp_len, sizeof(rudp_buf));
        if (r <= 0 || rudp_len == 0) break;

        queued_frame_t* entry = &conn->recv_queue[conn->recv_queue_tail];
        uint32_t copy_len = rudp_len < MAX_QUEUED_FRAME ? rudp_len : MAX_QUEUED_FRAME;
        memcpy(entry->data, rudp_buf, copy_len);
        entry->len = copy_len;
        conn->recv_queue_tail = (conn->recv_queue_tail + 1) % RECV_QUEUE_SIZE;
        conn->recv_queue_count++;
        moved++;
    }
    if (moved > 0) {
        LOGT("fill_recv_queue: drained %d frame(s) from RUDP", moved);
    }
    return moved;
}

// Read one protocol message and queue ALL blocks into the receive queue.
// Supports multi-connection: polls all active TCP sockets + optional UDP socket.
// Returns: 1 = data queued, 0 = keepalive/no data/timeout, -1 = error
static int softether_fill_recv_queue_locked(softether_connection_t* conn) {
    if (conn == NULL || conn->ssl == NULL) return -1;
    if (conn->state == STATE_DISCONNECTED || conn->state == STATE_DISCONNECTING) return -1;

    // Check for queued RUDP data first (non-blocking)
    if (drain_rudp_into_recv_queue(conn, RECV_QUEUE_SIZE) > 0) {
        return 1;
    }

    // Build pollfd array: [UDP (optional)] + [primary TCP] + [additional TCP 0..N-1]
    // Max: 1 (UDP) + 1 (primary) + MAX_SE_CONNECTIONS (additional) = 10
    struct pollfd fds[1 + 1 + MAX_SE_CONNECTIONS];
    nfds_t nfds = 0;

    // Track which pollfd index maps to which TCP socket
    // TCP_SOCK_INFO: maps a pollfd index to ssl/fd pair
    typedef struct { void* ssl; int fd; int is_additional; int additional_idx; } tcp_sock_info_t;
    tcp_sock_info_t tcp_info[1 + MAX_SE_CONNECTIONS];
    int tcp_count = 0;

    int udp_fd = -1;

    // UDP socket (RUDP)
    if (conn->rudp && conn->rudp_enabled) {
        udp_fd = rudp_get_udp_fd(conn->rudp);
        if (udp_fd >= 0) {
            fds[nfds].fd = udp_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            nfds++;
        }
    }

    // Primary TCP socket — only add if direction allows receiving (client: BOTH or S2C)
    if (conn->socket_fd >= 0) {
        int pd = conn->primary_direction;
        if (pd == TCP_DIRECTION_BOTH || pd == TCP_DIRECTION_SERVER_TO_CLIENT) {
            fds[nfds].fd = conn->socket_fd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            tcp_info[tcp_count].ssl = conn->ssl;
            tcp_info[tcp_count].fd = conn->socket_fd;
            tcp_info[tcp_count].is_additional = 0;
            tcp_info[tcp_count].additional_idx = -1;
            tcp_count++;
            nfds++;
        }
    }

    // Additional TCP sockets — only add if direction allows receiving (client: BOTH or S2C)
    for (int i = 0; i < MAX_SE_CONNECTIONS; i++) {
        softether_tcp_sock_t* ts = &conn->additional[i];
        if (!ts->active || ts->socket_fd < 0) continue;

        // Client mode: can receive on TCP_BOTH or TCP_SERVER_TO_CLIENT
        int d = ts->direction;
        if (d != TCP_DIRECTION_BOTH && d != TCP_DIRECTION_SERVER_TO_CLIENT) continue;

        // Capture SSL and fd atomically — recheck active after capture
        void* cap_ssl = ts->ssl;
        int cap_fd = ts->socket_fd;
        __sync_synchronize();  // load-acquire: ensure ssl/fd stores are visible
        if (!ts->active || cap_ssl == NULL || cap_fd < 0) continue;

        fds[nfds].fd = cap_fd;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        tcp_info[tcp_count].ssl = cap_ssl;
        tcp_info[tcp_count].fd = cap_fd;
        tcp_info[tcp_count].is_additional = 1;
        tcp_info[tcp_count].additional_idx = i;
        tcp_count++;
        nfds++;
    }

    if (nfds == 0) return -1;

    // Memory barrier + state check before using any captured SSL pointers
    __sync_synchronize();
    if (conn->state == STATE_DISCONNECTING) return -1;

    // Check for SSL-buffered data on any TCP socket before polling.
    // Each captured ssl pointer is only safe to dereference while that link's
    // io_mutex is held: a transmit failover retires a dead additional socket and
    // destroys its SSL context under the SAME per-link lock (NOT under
    // ssl_lifetime_lock WRITE), so the captured pointer must be re-validated and
    // dereferenced under that mutex to avoid a use-after-free. Re-validating via
    // link_io_live also skips entries retired between capture and this check.
    int ssl_pending_idx = -1;
    for (int t = 0; t < tcp_count; t++) {
        if (!conn->use_ssl_data) break;
        void* cap_ssl = tcp_info[t].ssl;
        int cap_fd = tcp_info[t].fd;
        int slot_idx = tcp_info[t].additional_idx;
        pthread_mutex_t* io = (slot_idx < 0) ? &conn->io_mutex
                                             : &conn->additional[slot_idx].io_mutex;
        pthread_mutex_lock(io);
        int pending = 0;
        if (link_io_live(conn, slot_idx, cap_ssl, cap_fd) == 0) {
            pending = ssl_has_pending((ssl_context_t*)cap_ssl);
        }
        pthread_mutex_unlock(io);
        if (pending) {
            ssl_pending_idx = t;
            break;
        }
    }

    // Phase 21: resume an in-flight multi-block message before polling. When the
    // receive queue filled up mid-message a previous call stopped reading and
    // remembered the unread remainder plus the socket it belongs to; continue
    // exactly there so no block is dropped. Setting ssl_pending_idx also skips
    // the poll below — the remaining blocks are already in flight on that link
    // (read_uint32_sock reports "no data yet" and keeps the state if not).
    if (conn->recv_msg_pending_blocks > 0) {
        int resume_idx = -1;
        for (int t = 0; t < tcp_count; t++) {
            if (tcp_info[t].fd == conn->recv_msg_fd &&
                tcp_info[t].ssl == conn->recv_msg_ssl) {
                resume_idx = t;
                break;
            }
        }
        if (resume_idx < 0) {
            // The socket carrying the partial message was retired (server hung
            // up / failover): drop the remainder and start clean on the next
            // message. The rest of the session is unaffected.
            LOGW("fill_recv_queue: in-flight message socket fd=%d gone, dropping %u partial block(s)",
                 conn->recv_msg_fd, conn->recv_msg_pending_blocks);
            conn->recv_msg_pending_blocks = 0;
            conn->recv_msg_ssl = NULL;
            conn->recv_msg_fd = -1;
            return 0;
        }
        ssl_pending_idx = resume_idx;
    }

    if (ssl_pending_idx < 0) {
        // No SSL-buffered data — poll all sockets
        // 100ms idle wait in both paths: poll wakes immediately on data, so
        // this only bounds how often an idle connection wakes the Java RX
        // loop (Phase 13E: < 10 wakeups/s at idle).
        int poll_timeout_ms = 100;
        int poll_ret = poll(fds, nfds, poll_timeout_ms);

        if (poll_ret == 0) {
            return 0;  // No data available
        }
        if (poll_ret < 0) {
            LOGE("fill_recv_queue: poll error: %d", errno);
            char _why[64];
            snprintf(_why, sizeof(_why), "poll failed errno=%d", errno);
            record_link_error(conn, -1, _why);
            return -1;
        }

        // After blocking poll, recheck state — disconnect may have freed SSL pointers
        __sync_synchronize();
        if (conn->state == STATE_DISCONNECTING) return -1;

        // Link-health bookkeeping for the send path: the link that just became
        // readable is reset to zero, every other link records one more poll
        // without data. This mirrors the reference client's TCPSOCK::LateCount
        // (Connection.c:1370 increments it on SOCK_LATER, :1396 clears it when
        // data arrives) and lets softether_select_send_socket() stop handing
        // blocks to a link the server has quietly stopped using.
        for (int t = 0; t < tcp_count; t++) {
            int pfd_idx = -1;
            for (nfds_t i = 0; i < nfds; i++) {
                if (fds[i].fd == tcp_info[t].fd) { pfd_idx = (int)i; break; }
            }
            int has_data = (pfd_idx >= 0) && (fds[pfd_idx].revents & POLLIN);
            if (tcp_info[t].is_additional) {
                int idx = tcp_info[t].additional_idx;
                if (idx >= 0) {
                    conn->additional[idx].late_count =
                        has_data ? 0 : conn->additional[idx].late_count + 1;
                }
            } else {
                conn->primary_late_count = has_data ? 0 : conn->primary_late_count + 1;
            }
        }

        // Check for UDP data first
        for (nfds_t i = 0; i < nfds; i++) {
            if (fds[i].revents & POLLNVAL) {
                LOGE("fill_recv_queue: socket fd=%d invalid", fds[i].fd);
                char _why[64];
                snprintf(_why, sizeof(_why), "fd=%d POLLNVAL", fds[i].fd);
                record_link_error(conn, -1, _why);
                return -1;
            }
            if ((fds[i].revents & (POLLERR | POLLHUP)) && !(fds[i].revents & POLLIN)) {
                LOGE("fill_recv_queue: socket fd=%d error (revents=0x%x)", fds[i].fd, fds[i].revents);
                // If this is an additional socket, mark it as failed but don't kill the connection
                int is_additional = 0;
                int add_idx = -1;
                for (int t = 0; t < tcp_count; t++) {
                    if (tcp_info[t].fd == fds[i].fd && tcp_info[t].is_additional) {
                        is_additional = 1;
                        add_idx = tcp_info[t].additional_idx;
                        break;
                    }
                }
                if (is_additional) {
                    LOGW("Additional socket fd=%d failed, will be cleaned up", fds[i].fd);
                    continue;  // Don't return error for additional socket failures
                }
                char _why[80];
                snprintf(_why, sizeof(_why), "fd=%d revents=0x%x (%s%s)",
                         fds[i].fd, fds[i].revents,
                         (fds[i].revents & POLLHUP) ? "POLLHUP" : "",
                         (fds[i].revents & POLLERR) ? "POLLERR" : "");
                record_link_error(conn, -1, _why);
                return -1;
            }
        }

        // Process UDP data if available
        if (conn->rudp && conn->rudp_enabled && udp_fd >= 0) {
            for (nfds_t i = 0; i < nfds; i++) {
                if (fds[i].fd == udp_fd && (fds[i].revents & POLLIN)) {
                    if (drain_rudp_into_recv_queue(conn, RECV_QUEUE_SIZE) > 0) {
                        return 1;
                    }
                }
            }
        }

        // Find which TCP socket has data
        ssl_pending_idx = -1;
        for (int t = 0; t < tcp_count; t++) {
            // Map tcp_info[t] back to the pollfd index
            int pfd_idx = -1;
            for (nfds_t i = 0; i < nfds; i++) {
                if (fds[i].fd == tcp_info[t].fd) {
                    pfd_idx = (int)i;
                    break;
                }
            }
            if (pfd_idx >= 0 && (fds[pfd_idx].revents & POLLIN)) {
                ssl_pending_idx = t;
                break;
            }
        }

        if (ssl_pending_idx < 0) {
            return 0;  // No TCP data
        }
    }

    // Read from the selected TCP socket
    {
        void* sel_ssl = tcp_info[ssl_pending_idx].ssl;
        int sel_fd = tcp_info[ssl_pending_idx].fd;
        int sel_is_additional = tcp_info[ssl_pending_idx].is_additional;
        int sel_add_idx = tcp_info[ssl_pending_idx].additional_idx;
        // Which link this read is charged to: 0 = primary, i+1 = additional slot i.
        int link_index = (sel_is_additional && sel_add_idx >= 0) ? (sel_add_idx + 1) : 0;
        if (link_index > MAX_SE_CONNECTIONS) link_index = 0;
        // Bytes delivered to the queue during this call; committed to the link's
        // counter at the end so a paused/partial message is not double-counted.
        uint64_t rx_bytes_here = 0;

        // Revalidate: check that the SSL pointer is still valid (not freed by disconnect).
        // This guards against concurrent softether_disconnect / softether_close_additional
        // destroying the SSL context between our poll() wake-up and the upcoming SSL_read.
        if (sel_ssl == NULL) {
            LOGW("fill_recv_queue: SSL became NULL for fd=%d, skipping", sel_fd);
            return 0;
        }
        if (!sel_is_additional) {
            // Primary socket: verify conn->ssl wasn't swapped out by disconnect
            if (conn->ssl != sel_ssl || conn->socket_fd != sel_fd) {
                LOGW("fill_recv_queue: primary socket ssl/fd mismatch, skipping");
                return 0;
            }
        } else if (sel_add_idx >= 0) {
            softether_tcp_sock_t* check_ts = &conn->additional[sel_add_idx];
            if (!check_ts->active || check_ts->ssl != sel_ssl) {
                LOGW("fill_recv_queue: additional socket [%d] ssl mismatch, skipping", sel_add_idx);
                return 0;
            }
        }
        if (conn->state == STATE_DISCONNECTING) {
            LOGW("fill_recv_queue: state DISCONNECTING after poll, skipping");
            return -1;
        }
        __sync_synchronize();  // load-acquire before using sel_ssl

        // Helper macro to close and deactivate a failed additional socket.
        // Mark inactive BEFORE destroying to prevent use-after-free by other threads.
        // Close fd FIRST so any in-flight SSL_write returns error instead of crashing.
        // The retirement runs under the slot's io_mutex so it is exclusive with
        // a concurrent keepalive/data-write on the same link (tx failover takes
        // the same lock), preventing a use-after-free on a mid-IO context.
        // Returns 0 to keep connection alive, or propagates -1 for primary socket failures
        #define CLOSE_FAILED_ADDITIONAL_SOCKET(_reason) do { \
            conn->recv_msg_pending_blocks = 0; \
            conn->recv_msg_ssl = NULL; \
            conn->recv_msg_fd = -1; \
            { \
                char _why[96]; \
                snprintf(_why, sizeof(_why), "fd=%d %s", sel_fd, (_reason)); \
                record_link_error(conn, sel_is_additional ? sel_add_idx : -1, _why); \
            } \
            if (sel_is_additional && sel_add_idx >= 0) { \
                LOGW("Additional socket fd=%d read failure, marking inactive", sel_fd); \
                softether_tcp_sock_t* _ts = &conn->additional[sel_add_idx]; \
                pthread_mutex_lock(&_ts->io_mutex); \
                int _saved_fd = _ts->socket_fd; \
                _ts->active = 0; \
                _ts->ssl = NULL; \
                _ts->socket_fd = -1; \
                __sync_synchronize(); \
                if (_saved_fd >= 0) close(_saved_fd); \
                ssl_context_t* _doomed = (ssl_context_t*)take_ssl_ctx_slot(&_ts->ssl_ctx); \
                if (_doomed != NULL) { \
                    ssl_shutdown(_doomed); \
                    ssl_destroy(_doomed); \
                } \
                pthread_mutex_unlock(&_ts->io_mutex); \
                conn->num_additional--; \
                return 0; \
            } \
            return -1; \
        } while(0)

        // Update last_recv timestamp for additional sockets
        if (sel_is_additional && sel_add_idx >= 0) {
            conn->additional[sel_add_idx].last_recv = softether_tick_ms();
        }

        LOGT("fill_recv_queue: reading from %s socket fd=%d",
             sel_is_additional ? "additional" : "primary", sel_fd);

        // Read block count (or keepalive magic). When resuming a message paused
        // on a previous call the count was already consumed then, so pick up the
        // remembered remainder instead of reading a fresh header.
        int resuming = (conn->recv_msg_pending_blocks > 0);
        uint32_t block_count = 0;

        if (resuming) {
            block_count = conn->recv_msg_pending_blocks;
        } else {
            int bc = read_uint32_sock(conn, sel_ssl, sel_fd, &block_count);
            if (bc == SE_SSL_READ_TIMEOUT) {
                // The socket timed out with nothing to read. Treat it as "no data"
                // instead of a dead link, so a transient stall never tears the
                // tunnel down (mirrors the reference client's SOCK_LATER handling).
                LOGT("fill_recv_queue: no data on fd=%d (socket timeout)", sel_fd);
                return 0;
            }
            if (bc != 0) {
                LOGE("fill_recv_queue: failed to read block count from fd=%d", sel_fd);
                CLOSE_FAILED_ADDITIONAL_SOCKET("block count read failed");
            }

            if (block_count == KEEP_ALIVE_MAGIC) {
                // Keepalive: read size + data, respond
                uint32_t ka_size = 0;
                if (read_uint32_sock(conn, sel_ssl, sel_fd, &ka_size) != 0) {
                    CLOSE_FAILED_ADDITIONAL_SOCKET("keepalive size read failed");
                }
                if (ka_size > 512) ka_size = 512;
                if (ka_size > 0) {
                    uint8_t ka_buf[512];
                    if (data_read_all_sock(conn, sel_ssl, sel_fd, ka_buf, (int)ka_size) != 0) {
                        CLOSE_FAILED_ADDITIONAL_SOCKET("keepalive body read failed");
                    }
                }
                LOGT("fill_recv_queue: keepalive (%u bytes) from fd=%d", ka_size, sel_fd);
                // Answer on the SAME link the keepalive arrived on. Replying only
                // over the primary socket leaves the additional link with no
                // traffic of its own, so the server times it out (s->Timeout) and
                // drops it — which is why extra links died every ~30-70 s.
                if (sel_is_additional && sel_add_idx >= 0) {
                    softether_send_keepalive_sock(conn, sel_ssl, sel_fd);
                } else {
                    softether_send_keepalive(conn);
                }
                return 0;
            }

            if (block_count == 0) {
                return 0; // Empty message
            }
        }

        LOGT("fill_recv_queue: reading %u block(s) from fd=%d", block_count, sel_fd);

        // Read the blocks of this message. Stop as soon as the receive queue is
        // full: remember how many blocks are left plus the socket, and resume on
        // the next call once the consumer has drained. A server message that
        // packs more blocks than the queue holds (observed: 128 blocks vs
        // RECV_QUEUE_SIZE=64) is thus delivered in full instead of discarding
        // the overflow (the old rx_skipped_blocks path).
        uint32_t blocks_left = block_count;
        uint32_t i = 0;
        while (blocks_left > 0) {
            if (conn->recv_queue_count >= RECV_QUEUE_SIZE) {
                conn->recv_msg_pending_blocks = blocks_left;
                conn->recv_msg_ssl = sel_ssl;
                conn->recv_msg_fd = sel_fd;
                conn->recv_msg_is_additional = sel_is_additional;
                conn->recv_msg_slot = sel_add_idx;
                // Blocks already queued in this call are real traffic; charge
                // them before pausing so the per-link split stays accurate.
                if (rx_bytes_here > 0) conn->link_rx_bytes[link_index] += rx_bytes_here;
                LOGD("fill_recv_queue: queue full, pausing message with %u block(s) left (fd=%d)",
                     blocks_left, sel_fd);
                return 1;
            }

            uint32_t block_size = 0;
            int bs_rc = read_uint32_sock(conn, sel_ssl, sel_fd, &block_size);
            if (bs_rc == SE_SSL_READ_TIMEOUT) {
                // The rest of this message has not arrived yet (socket timeout,
                // not a failure). Keep the resume state and report "no data" so
                // the caller retries instead of tearing the link down.
                conn->recv_msg_pending_blocks = blocks_left;
                conn->recv_msg_ssl = sel_ssl;
                conn->recv_msg_fd = sel_fd;
                conn->recv_msg_is_additional = sel_is_additional;
                conn->recv_msg_slot = sel_add_idx;
                if (rx_bytes_here > 0) conn->link_rx_bytes[link_index] += rx_bytes_here;
                LOGT("fill_recv_queue: partial message, %u block(s) still pending (fd=%d)",
                     blocks_left, sel_fd);
                return 0;
            }
            if (bs_rc != 0) {
                LOGE("fill_recv_queue: failed to read block %u size from fd=%d", i, sel_fd);
                CLOSE_FAILED_ADDITIONAL_SOCKET("block size read failed");
            }

            if (block_size > MAX_BLOCK_SIZE) {
                LOGE("fill_recv_queue: block %u too large: %u", i, block_size);
                char _why[64];
                snprintf(_why, sizeof(_why), "fd=%d block too large: %u", sel_fd, block_size);
                record_link_error(conn, sel_is_additional ? sel_add_idx : -1, _why);
                return -1;
            }

            if (block_size == 0) {
                goto block_done;
            }

            if (block_size <= MAX_QUEUED_FRAME) {
                queued_frame_t* entry = &conn->recv_queue[conn->recv_queue_tail];

                if (!conn->server_use_compress) {
                    // Phase 13D: session compression off (default since 13B) —
                    // the server sends raw frames, so read straight into the
                    // queue slot. One copy total (SSL -> slot).
                    if (data_read_all_sock(conn, sel_ssl, sel_fd, entry->data, (int)block_size) != 0) {
                        LOGE("fill_recv_queue: failed to read block %u from fd=%d", i, sel_fd);
                        CLOSE_FAILED_ADDITIONAL_SOCKET("block read failed");
                    }
                    entry->len = block_size;
                    conn->recv_queue_tail = (conn->recv_queue_tail + 1) % RECV_QUEUE_SIZE;
                    conn->recv_queue_count++;
                    goto block_done;
                }

                // Compressed session: read into temp buffer to check for
                // compression magic before decompressing into the slot.
                uint8_t* tmp_block = (uint8_t*)malloc(block_size);
                if (tmp_block == NULL) {
                    LOGE("fill_recv_queue: allocation failed for block %u", i);
                    return -1;
                }
                if (data_read_all_sock(conn, sel_ssl, sel_fd, tmp_block, (int)block_size) != 0) {
                    LOGE("fill_recv_queue: failed to read block %u from fd=%d", i, sel_fd);
                    free(tmp_block);
                    CLOSE_FAILED_ADDITIONAL_SOCKET("block read failed (compressed)");
                }

                int compressed_ok = 0;
                // Session-level compression: VPN Gate server sends raw zlib
                uint32_t raw_len = MAX_QUEUED_FRAME;
                if (uncompress_data(tmp_block, block_size,
                                    entry->data, &raw_len) == 0) {
                    entry->len = raw_len;
                    LOGT("fill_recv_queue: session-decompressed block %u: %u -> %u bytes", i, block_size, raw_len);
                    free(tmp_block);
                    conn->recv_queue_tail = (conn->recv_queue_tail + 1) % RECV_QUEUE_SIZE;
                    conn->recv_queue_count++;
                    compressed_ok = 1;
                    goto block_done;
                }
                // Fallback: check for CONNECTION_BULK_COMPRESS_SIGNATURE
                if (block_size > 8) {
                    uint64_t sig = 0;
                    for (int b = 0; b < 8; b++) {
                        sig = (sig << 8) | tmp_block[b];
                    }
                    if (sig == COMPRESS_MAGIC) {
                        raw_len = MAX_QUEUED_FRAME;
                        if (uncompress_data(tmp_block + 8, block_size - 8,
                                            entry->data, &raw_len) == 0) {
                            entry->len = raw_len;
                            LOGT("fill_recv_queue: magic-decompressed block %u: %u -> %u bytes",
                                 i, block_size - 8, raw_len);
                            free(tmp_block);
                            conn->recv_queue_tail = (conn->recv_queue_tail + 1) % RECV_QUEUE_SIZE;
                            conn->recv_queue_count++;
                            compressed_ok = 1;
                            goto block_done;
                        }
                    }
                }

                if (!compressed_ok) {
                    // Uncompressed — copy as-is
                    memcpy(entry->data, tmp_block, block_size);
                    free(tmp_block);
                    entry->len = block_size;
                    conn->recv_queue_tail = (conn->recv_queue_tail + 1) % RECV_QUEUE_SIZE;
                    conn->recv_queue_count++;
                }
            } else {
                // Frame larger than a queue slot — cannot be delivered at all.
                // Rare (MAX_QUEUED_FRAME is 2048); counted separately from the
                // queue-full case so the two are distinguishable in the log.
                uint8_t skip_buf[2048];
                uint32_t skip_left = block_size;
                while (skip_left > 0) {
                    uint32_t chunk = skip_left > sizeof(skip_buf) ? sizeof(skip_buf) : skip_left;
                    if (data_read_all_sock(conn, sel_ssl, sel_fd, skip_buf, (int)chunk) != 0) {
                        CLOSE_FAILED_ADDITIONAL_SOCKET("oversized block read failed");
                    }
                    skip_left -= chunk;
                }
                conn->rx_skipped_blocks++;
                LOGD("fill_recv_queue: skipped oversized block %u (%u bytes), total skipped=%u",
                     i, block_size, conn->rx_skipped_blocks);
            }

        block_done:
            // Charge successfully queued blocks to the link they arrived on.
            // block_size is 0 for empty blocks and > MAX_QUEUED_FRAME for the
            // skipped-oversized path, so neither is counted here.
            if (block_size > 0 && block_size <= MAX_QUEUED_FRAME) {
                rx_bytes_here += block_size;
            }
            blocks_left--;
            i++;
        }

        // Message fully consumed — clear any resume state.
        conn->recv_msg_pending_blocks = 0;
        conn->recv_msg_ssl = NULL;
        conn->recv_msg_fd = -1;
        if (rx_bytes_here > 0) conn->link_rx_bytes[link_index] += rx_bytes_here;
    }

    #undef CLOSE_FAILED_ADDITIONAL_SOCKET

    LOGT("fill_recv_queue: queued %d frames total", conn->recv_queue_count);
    return (conn->recv_queue_count > 0) ? 1 : 0;
}

// Public entry point. Holds the SSL-lifetime READ lock across the whole receive
// pass so that the ssl_context_t* pointers captured inside (conn->ssl and the
// additional[i].ssl slots) cannot be freed concurrently by
// softether_disconnect / softether_close_additional, which take the same lock
// in WRITE mode before CAS-claiming a slot to NULL and calling ssl_destroy().
// This closes the use-after-free where the old code captured conn->ssl into
// tcp_info[] and later deref'd it in ssl_has_pending()/SSL_read() after teardown
// had already freed the context.
int softether_fill_recv_queue(softether_connection_t* conn) {
    if (conn == NULL) return -1;
    pthread_rwlock_rdlock(&conn->ssl_lifetime_lock);
    int rc = softether_fill_recv_queue_locked(conn);
    pthread_rwlock_unlock(&conn->ssl_lifetime_lock);
    return rc;
}
