#ifndef SOFTETHER_PROTOCOL_H
#define SOFTETHER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include <time.h>
#include <pthread.h>
#include "softether_rudp.h"
#include "rudp_transport.h"

#ifdef __cplusplus
extern "C" {
#endif

// Client authentication types (matching SoftEther Cedar.h)
#define CLIENT_AUTHTYPE_ANONYMOUS       0
#define CLIENT_AUTHTYPE_PASSWORD        1
#define CLIENT_AUTHTYPE_PLAIN_PASSWORD  2

// Real SoftEther data channel constants
#define KEEP_ALIVE_MAGIC        0xFFFFFFFF
#define SOFTETHER_MAX_BLOCK     (1600 * 1600)

// Multi-connection constants (matching Cedar.h)
#define MAX_SE_CONNECTIONS                  8
#define MAX_SEND_SOCKET_QUEUE_SIZE          (1600 * 1600)
#define MIN_SEND_SOCKET_QUEUE_SIZE          (1600 * 200)
#define ADDITIONAL_CONNECT_INTERVAL_MS      1000
// Upper bound for the exponential backoff between failed additional-connection
// attempts. Without it a server that keeps refusing extra links is retried once
// per second forever, which hammers the server and the battery.
#define SE_ADDL_RETRY_DELAY_MAX_MS          30000
#define TCP_DIRECTION_BOTH                  0
#define TCP_DIRECTION_SERVER_TO_CLIENT      1
#define TCP_DIRECTION_CLIENT_TO_SERVER      2

// Monotonic time in milliseconds (for multi-connection timing)
static inline uint64_t softether_tick_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

// Legacy command types (used internally for dispatch, not on wire)
#define CMD_DATA                0x000C
#define CMD_KEEPALIVE           0x000D
#define CMD_KEEPALIVE_ACK       0x000E
#define CMD_DISCONNECT          0x000F

// Error codes
#define ERR_NONE                0
#define ERR_TCP_CONNECT         1
#define ERR_TLS_HANDSHAKE       2
#define ERR_PROTOCOL_VERSION    3
#define ERR_AUTHENTICATION      4
#define ERR_SESSION             5
#define ERR_DATA_TRANSMISSION   6
#define ERR_TIMEOUT             7
#define ERR_UNKNOWN             99

// Connection state
typedef enum {
    STATE_DISCONNECTED = 0,
    STATE_CONNECTING,
    STATE_TLS_HANDSHAKE,
    STATE_PROTOCOL_HANDSHAKE,
    STATE_AUTHENTICATING,
    STATE_SESSION_SETUP,
    STATE_CONNECTED,
    STATE_DISCONNECTING
} softether_state_t;

// Transport type identifiers for the parallel connect race (Phase 12B).
// Mirrors the official client's ConnectEx4 4-way race:
//   p1 = TCP direct, p2 = NAT-T relay, p3 = ICMP, p4 = DNS.
// We add RUDP_DIRECT (direct R-UDP to seUdpPort) between TCP and NAT-T.
#define TRANSPORT_TCP           0
#define TRANSPORT_RUDP_DIRECT   1
#define TRANSPORT_NATT          2
#define TRANSPORT_DNS           3
#define TRANSPORT_ICMP          4
#define NUM_TRANSPORTS          5

// Staggered delays before each transport attempt (milliseconds).
// Matches the official client's ConnectEx4 delays (Network.c:16487-16493).
#define TRANSPORT_DELAY_TCP_MS      0
#define TRANSPORT_DELAY_RUDP_MS     0
#define TRANSPORT_DELAY_NATT_MS    30
#define TRANSPORT_DELAY_DNS_MS    100
#define TRANSPORT_DELAY_ICMP_MS   200

// Maximum time to wait for any transport to succeed (the "give up" deadline).
// Shorter than the sequential sum because parallel attempts run concurrently.
#define TRANSPORT_RACE_TIMEOUT_MS  15000

// Result from a single transport attempt in the parallel race.
typedef struct {
    int transport_type;         // TRANSPORT_*
    int socket_fd;              // Won transport's fd (promoted into conn on win)
    void* ssl_ctx;              // ssl_context_t* (won transport)
    void* ssl;                  // ssl_context_t* (won transport)
    rudp_transport_t* transport; // For R-UDP-based transports (NATT/DNS/ICMP/RUDP_DIRECT)
    int error;                  // ERR_NONE on success, error code on failure
    int finished;               // atomic: 1 when this attempt completed
    int ok;                     // atomic: 1 on success (only set by winner)
} transport_result_t;

// Receive queue for multi-block messages
#define RECV_QUEUE_SIZE 64
#define MAX_QUEUED_FRAME 2048  // Phase 13D: was 1600 — larger server frames were silently dropped

typedef struct {
    uint8_t data[MAX_QUEUED_FRAME];
    uint32_t len;
} queued_frame_t;

// Stage codes for softether_connection_t::additional_last_stage. Multi-connection
// failures are diagnosed on-device through the app log (the native LOGE output
// only reaches logcat), so the last attempt's stage is published in
// softether_session_info_t and logged by the Kotlin side.
#define SE_ADDL_STAGE_NONE       0  // no attempt yet / last attempt succeeded
#define SE_ADDL_STAGE_NO_SLOT    1  // no free additional-connection slot
#define SE_ADDL_STAGE_TCP        2  // opening the additional TCP socket
#define SE_ADDL_STAGE_TLS        3  // TLS handshake on the additional socket
#define SE_ADDL_STAGE_WATERMARK  4  // VPNCONNECT watermark + connect.cgi response
#define SE_ADDL_STAGE_AUTH       5  // sending the additional_connect PACK
#define SE_ADDL_STAGE_AUTH_RESP  6  // reading the vpn.cgi response
#define SE_ADDL_STAGE_SERVER_ERR 7  // server answered error=<n>
#define SE_ADDL_STAGE_HTTP_ERR   8  // vpn.cgi answered a non-200 HTTP status
#define SE_ADDL_STAGE_BAD_BODY   9  // vpn.cgi body was not a valid error=0 PACK

// Connect timeout for an additional TCP socket. The primary connect uses the
// session timeout (30 s), but a hung additional connect blocks the single
// background thread for that whole time, so bound it much tighter: the
// reference client also caps the additional-connect phase at CONNECTING_TIMEOUT.
#define SE_ADDL_CONNECT_TIMEOUT_MS 10000

// Optional platform hook invoked on a freshly created socket fd, before it is
// connected. Android sets this to VpnService.protect so that additional TCP
// connections opened after the tunnel is up bypass the VPN routing table
// instead of being routed into the tunnel and hanging. Returns 0 on success.
typedef int (*softether_protect_fn)(void* userdata, int fd);

// Additional TCP socket for multi-connection support
typedef struct {
    int socket_fd;
    void* ssl_ctx;       // ssl_context_t*
    void* ssl;           // ssl_context_t* (same pointer, used for I/O)
    int direction;       // TCP_DIRECTION_*
    uint64_t last_recv;  // monotonic timestamp of last recv (ms)
    uint32_t late_count; // number of times this socket had no data when polled
    int active;          // 1 if slot is in use, 0 if free
    pthread_mutex_t io_mutex;  // per-link I/O lock (see softether_connection_t comment)
} softether_tcp_sock_t;

// Connection context
typedef struct softether_connection {
    int socket_fd;
    void* ssl_ctx;
    void* ssl;
    softether_state_t state;
    uint32_t session_id;
    uint32_t sequence_num;
    char server_ip[64];
    int server_port;
    char username[256];
    char password[256];
    char hub_name[256];  // Virtual Hub name (required for CONNECT)
    int timeout_ms;
    // Idle wait of the receive loop, in ms. The app's energy-saving switch raises
    // it so an idle tunnel wakes the CPU less often; poll() still returns at once
    // when data arrives, so throughput is unaffected. Default 100.
    int idle_poll_ms;

    // Server Hello data (from /vpnsvc/connect.cgi response)
    uint8_t server_random[20];  // 20-byte random from server Hello PACK
    int has_server_random;
    // Session data (from Welcome PACK after authentication)
    char session_name[128];
    char connection_name[128];
    uint8_t session_key[20];    // SHA1_SIZE
    uint32_t session_key_32;
    uint32_t server_max_connection;
    uint32_t server_use_encrypt;
    uint32_t server_use_compress;  // 1 if server accepted compression
    uint32_t server_use_fast_rc4;
    uint32_t server_timeout;
    // Client-requested session options, echoed in the login PACK. The server
    // decides the final values (server_use_* above); these mirror the SoftEther
    // Windows client's "Encrypt VPN Session with SSL" / "Use Data Compression" /
    // "Disable VoIP / QoS Functions" checkboxes.
    uint32_t req_use_encrypt;   // 1 = wrap the data channel in SSL (default 1)
    uint32_t req_use_compress;  // 1 = ask for data compression (default 0)
    uint32_t req_qos;           // 1 = keep VoIP / QoS functions (default 1)
    int use_ssl_data;  // 1 = SSL for data, 0 = raw TCP
    int session_established;    // 1 if Welcome PACK was parsed successfully
    int forced_auth_type;  // 0=auto-detect, 1=hashed password, 2=plain password (RADIUS)
    // Client MAC address (for Ethernet L2 encapsulation)
    uint8_t client_mac[6];     // Locally-administered random MAC
    // Gateway MAC address (resolved via ARP after DHCP)
    uint8_t gateway_mac[6];    // Destination MAC for outgoing IP packets
    int gateway_mac_resolved;  // 1 if gateway MAC has been resolved via ARP
    uint32_t gateway_ip;       // Gateway IP in host byte order (from DHCP)
    uint32_t assigned_ip;      // Our assigned IP in host byte order (from DHCP)
    // Receive frame queue (for multi-block messages)
    queued_frame_t recv_queue[RECV_QUEUE_SIZE];
    int recv_queue_head;       // read position
    int recv_queue_tail;       // write position
    int recv_queue_count;      // number of queued frames
    uint32_t rx_skipped_blocks;  // Phase 13D: blocks dropped (too large / queue full)
    // Phase 21: in-flight multi-block message state. The server can pack more
    // blocks into one message than the receive queue holds (seen: 128 blocks vs
    // RECV_QUEUE_SIZE=64). Instead of reading the whole message and dropping the
    // overflow, fill_recv_queue stops when the queue is full and remembers how
    // many blocks of the current message are still unread plus the socket they
    // come from. The next call resumes exactly there, so no block is ever
    // dropped. (The reference client streams blocks through a per-socket FIFO
    // for the same reason.)
    uint32_t recv_msg_pending_blocks;  // blocks left to read in the current message
    void*    recv_msg_ssl;             // socket carrying the in-flight message
    int      recv_msg_fd;
    int      recv_msg_is_additional;
    int      recv_msg_slot;
    // RUDP (UDP acceleration)
    rudp_context_t* rudp;
    int rudp_enabled;
    // NAT-T + RUDP transport fallback (used when the direct TCP/TLS connect
    // fails — reaches servers behind NAT/CGNAT via the SoftEther relay).
    rudp_transport_t* nat_t_transport;
    int using_nat_t;  // 1 if the primary connection runs over the transport
    int udp_port;     // advertised SoftEther UDP port (seUdpPort), 0 if none;
                      // when >0 a direct R-UDP attempt precedes the NAT-T relay
    int udp_only;     // 1 if the server advertises no TCP port (seTcpPort <= 0);
                      // skips the doomed direct-TCP attempt
    uint32_t rudp_server_cookie;
    uint32_t rudp_client_cookie;
    uint8_t rudp_server_key[128];
    int rudp_server_key_size;
    uint8_t rudp_server_key_v2[128];
    int rudp_server_key_v2_size;
    char rudp_server_ip[64];
    uint16_t rudp_server_port;
    int rudp_version;
    // Client info for server session list
    char client_product_name[128];
    char client_product_version[32];
    int client_product_build;
    char client_os_name[128];
    char client_os_version[64];
    char client_os_product_id[128];
    char client_host_name[128];
    char client_ip_address[64];
    int client_port;
    char server_host_name[128];
    char server_ip_address[64];
    int server_port_reported;
    // IPv6 fields (dual-stack support)
    char client_ip_v6[128];   // Client IPv6 address (reported in login PACK)
    char server_ip_v6[128];   // Server IPv6 address (from resolved host)
    int is_ipv6;              // 1 if the connection is over IPv6
    // Thread safety for concurrent send/receive
    // write_mutex protects ONLY the shared send_block staging buffer (build +
    // transmit are one critical section so ARP replies can't interleave into a
    // half-built data block) and serializes SSL-context teardown against the
    // send path. It does NOT serialize SSL I/O anymore: each live TCP link has
    // its own io_mutex below, so a read on link A no longer blocks writes on
    // link B and a slow SSL_write on one link cannot stall staging for the rest.
    // Per-link io_mutex: every SSL read/write/keepalive on a link takes that
    // link's io_mutex (BoringSSL SSL objects forbid concurrent operations on the
    // SAME context, but different links are independent). io_mutex is a strict
    // leaf lock: it is never held while acquiring write_mutex / connect_mutex /
    // ssl_lifetime_lock, keeping a single global order:
    //   receive path : ssl_lifetime(R) -> io
    //   send path    : write_mutex     -> io
    //   teardown     : ssl_lifetime(W) -> write_mutex -> io(primary + all slots)
    pthread_mutex_t write_mutex;  // send_block staging + teardown serialization
    pthread_mutex_t io_mutex;     // per-link I/O lock for the primary TCP socket
    pthread_mutex_t connect_mutex;  // serializes connect/disconnect (recursive; connect calls disconnect internally)
    // Protects the LIFETIME of ssl_context_t* pointers (conn->ssl and the
    // additional[i].ssl slots). Readers (the receive path) hold it in READ mode
    // across their whole pass while capturing and using an SSL pointer; teardown
    // holds it in WRITE mode while CAS-claiming a slot to NULL and freeing it,
    // so the receive path can never deref a freed context. Ordering: teardown
    // acquires ssl_lifetime(W) BEFORE write_mutex; every other lock user takes
    // io_mutex only as a leaf, so the single global order is
    // ssl_lifetime(R|W) -> write_mutex -> io.
    pthread_rwlock_t ssl_lifetime_lock;  // protects ssl_context_t* pointer lifetime
    // Multi-connection support
    softether_tcp_sock_t additional[MAX_SE_CONNECTIONS];  // additional TCP sockets (index 0 unused; primary is in socket_fd/ssl)
    int num_additional;          // number of active additional connections
    int max_connection;          // target max connections (sent in login PACK)
    int half_connection;         // always 0 (half-duplex mode removed); every link is bidirectional
    int primary_direction;       // TCP_DIRECTION_* for primary socket (always BOTH now)
    uint32_t additional_connect_interval_ms;  // delay between additional-connect attempts (login-negotiated)
    uint32_t additional_retry_delay_ms;  // Phase 21: current backoff delay after a failed attempt
    uint64_t next_connect_time;  // monotonic timestamp for next additional connect attempt (ms)
    int additional_failed_count; // serial failure counter for additional connects
    int additional_attempts;     // total additional-connect attempts (diagnostics)
    int additional_last_stage;   // SE_ADDL_STAGE_* of the last attempt (diagnostics)
    int additional_last_error;   // server "error" value of the last attempt, 0 if none
    int additional_last_attempt_ms;  // wall-clock duration of the last attempt (ms)
    // Stage the last FINISHED attempt died at. `additional_last_stage` above is
    // overwritten as soon as the next attempt starts, so reading it during a
    // retry storm reports the in-flight attempt, not the failure. This one is
    // written once, at the moment the attempt returns.
    int additional_last_fail_stage;
    // Human-readable specifics of the last finished attempt (target, errno,
    // OpenSSL reason, HTTP status, local socket address). Surfaced in the app
    // log so a failed multi-connection setup is diagnosable without logcat.
    char additional_last_detail[192];
    // Why the last data-link read failed, e.g. "primary fd=42 revents=0x10
    // (POLLHUP)". The receive loop only sees a bare -1 from the native layer, so
    // without this a dropped session could not be attributed to a link or a
    // cause. Empty until a link has failed.
    char last_link_error[160];
    // Local address of the primary TCP socket, captured once it is connected.
    // Additional links must leave from the same source address (the reference
    // client binds them to it explicitly), so both ends are logged together.
    char primary_local_ip[64];
    int primary_local_port;
    // Result of the platform protect hook on the last additional-connect
    // attempt: 1 = the socket was excluded from the tunnel, -1 = the hook ran
    // and failed, 0 = no hook installed. A tunnel-routed extra socket connects
    // and handshakes fine but then stalls, so this is the difference between a
    // real link and a phantom one.
    int additional_last_protect;
    // Background additional connection thread
    pthread_t additional_thread; // background thread for non-blocking additional connect
    int additional_connecting;   // 1 if background thread is running
    int additional_connect_slot; // which slot the background thread is targeting
    int additional_connect_result; // result from background thread (0=success, -1=fail)
    int send_rr_idx;              // round-robin index for send socket selection
    uint32_t primary_late_count;  // primary link: consecutive polls with no data
    uint64_t next_tcp_keepalive_time;  // monotonic timestamp for next periodic TCP keepalive sweep (ms)
    // Per-link traffic distribution. Index 0 is the primary link, index i+1 is
    // additional slot i. The send path adds the block length to the socket it
    // actually chose (softether_select_send_socket, with failover), and the
    // receive path adds it to the socket the message arrived on. The app log
    // prints these so the session-wide totals can be split per TCP link —
    // otherwise it is impossible to tell whether the extra connections carry any
    // traffic or are just sitting there.
    uint64_t link_tx_bytes[MAX_SE_CONNECTIONS + 1];
    uint64_t link_rx_bytes[MAX_SE_CONNECTIONS + 1];
    // Platform hook to exclude new sockets from the VPN tunnel (see
    // softether_protect_fn). NULL when unset; copied into each additional
    // socket's protect_fn before connect.
    softether_protect_fn protect_fn;
    void* protect_userdata;
    // Phase 13C: preallocated TX staging buffer laid out as
    // "[block_count(4)][block_size(4)][ethernet frame]". Built and transmitted
    // under write_mutex (shared by softether_send / softether_send_packet). 
    // Allocated in softether_create(), freed in softether_destroy().
    uint8_t* send_block;
    uint32_t send_block_cap;
    // Phase 13G: permanent traffic counters (updated on the data paths)
    uint64_t stats_tx_packets;
    uint64_t stats_tx_bytes;
    uint64_t stats_rx_packets;
    uint64_t stats_rx_bytes;
} softether_connection_t;

// Phase 13G: snapshot of connection health for nativeGetStats.
// RUDP fields are zero when UDP acceleration is not active.
typedef struct {
    uint64_t tx_packets;
    uint64_t tx_bytes;
    uint64_t rx_packets;
    uint64_t rx_bytes;
    uint32_t rx_skipped_blocks;      // frames dropped at fill_recv_queue (13D)
    uint64_t rudp_overflow_count;    // recv-queue overflows (13F)
    uint64_t rudp_rx_packets;        // valid inbound RUDP datagrams
    uint64_t rudp_tick_gaps;         // peer-tick gap events (13F)
    int32_t  rudp_data_suspended;    // 1 = data currently routed via TCP (13F)
} softether_stats_t;

// Function prototypes

// Connection management
softether_connection_t* softether_create(void);
void softether_destroy(softether_connection_t* conn);
int softether_connect(softether_connection_t* conn, const char* host, int port,
                      const char* username, const char* password);
int softether_connect_with_hub(softether_connection_t* conn, const char* host, int port,
                               const char* username, const char* password, const char* hub_name,
                               int use_tcp,
                               const char* client_product_name, const char* client_product_version, int client_product_build,
                               const char* client_os_name, const char* client_os_version, const char* client_os_product_id,
                               const char* client_host_name, const char* client_ip_address, int client_port,
                               const char* server_host_name, const char* server_ip_address, int server_port);
void softether_disconnect(softether_connection_t* conn);

// State management
softether_state_t softether_get_state(softether_connection_t* conn);
const char* softether_state_string(softether_state_t state);

// Data I/O (wraps IP packets in Ethernet frames for L2 tunnel)
int softether_send(softether_connection_t* conn, const uint8_t* data, size_t len);
int softether_receive(softether_connection_t* conn, uint8_t* buffer, size_t max_len);

// Raw L2 I/O (sends/receives raw Ethernet frames — used by DHCP)
int softether_send_raw(softether_connection_t* conn, const uint8_t* frame, size_t len);
int softether_receive_raw(softether_connection_t* conn, uint8_t* frame, size_t max_len, uint32_t* frame_len);

// Phase 13D: drain up to max_packets queued frames into `buffer` contiguously.
// Per-frame sizes are written to lengths[0..count-1]; *out_count receives the
// frame count. Fills the queue from the wire when empty. Returns total bytes
// copied (0 = nothing available), -1 on error.
int softether_receive_batch(softether_connection_t* conn,
                            uint8_t* buffer, uint32_t max_len,
                            uint32_t* lengths, uint32_t max_packets,
                            uint32_t* out_count);

// ARP resolution — resolves gateway MAC after DHCP
int softether_resolve_gateway(softether_connection_t* conn, uint32_t gateway_ip_host);

// Phase 13G: snapshot traffic/health counters into *out
void softether_get_stats(softether_connection_t* conn, softether_stats_t* out);

// Protocol operations
int softether_send_packet(softether_connection_t* conn, uint16_t command,
                          const uint8_t* payload, uint32_t payload_len);
// Phase 13C: transmit a fully built block ([block_count][block_size][payload])
// over the best TCP socket. No copies, no allocation. 0 on success, -1 on error.
int softether_transmit_block(softether_connection_t* conn,
                             const uint8_t* block, uint32_t block_len);
// Same, but caller MUST already hold write_mutex (send_block staging is
// built under it — see the softether_send / send_packet race fix).
int softether_transmit_block_nolock(softether_connection_t* conn,
                                    const uint8_t* block, uint32_t block_len);
int softether_receive_packet(softether_connection_t* conn, uint16_t* command,
                             uint8_t* payload, uint32_t* payload_len, uint32_t max_payload);

// Data tunnel operations
int softether_send_data(softether_connection_t* conn, const uint8_t* data, uint32_t data_len);
int softether_receive_data(softether_connection_t* conn, uint8_t* buffer, uint32_t max_len,
                           uint32_t* received_len, uint16_t* command);

// Keepalive and connection monitoring
int softether_send_keepalive(softether_connection_t* conn);
int softether_send_keepalive_all(softether_connection_t* conn);
int softether_check_connection(softether_connection_t* conn);
int softether_process_keepalive(softether_connection_t* conn);

// Multi-block receive queue (fills queue from one protocol message)
int softether_fill_recv_queue(softether_connection_t* conn);

// Multi-connection support
int softether_additional_connect(softether_connection_t* conn);
void softether_close_additional(softether_connection_t* conn);
int softether_select_send_socket(softether_connection_t* conn);
int softether_get_num_connections(softether_connection_t* conn);
int softether_get_active_socket_fds(softether_connection_t* conn, int* fds, int max_fds);
void softether_additional_thread_wait(softether_connection_t* conn);

// Force-close sockets without mutex — used to interrupt a blocking connect
// held by softether_connect_with_hub so softether_destroy can proceed.
void softether_force_close_socket(softether_connection_t* conn);

// Reconnection support
void softether_set_reconnect_enabled(softether_connection_t* conn, int enabled);
int softether_reconnect(softether_connection_t* conn);

// Set authentication type explicitly (use CLIENT_AUTHTYPE_* constants; 0=auto)
void softether_set_auth_type(softether_connection_t* conn, int auth_type);

// Install a platform hook that is invoked on every newly created additional
// socket before connect(), so the platform can protect it from the VPN tunnel.
// Pass fn=NULL to clear. See softether_protect_fn.
void softether_set_protect_callback(softether_connection_t* conn,
                                    softether_protect_fn fn, void* userdata);
// Override the auto-generated random client MAC (6 bytes, used for ARP/DHCP
// identity). Use a MAC derived from the server identity so reconnects keep a
// stable L2 address on local-bridge servers.
void softether_set_client_mac(softether_connection_t* conn, const uint8_t mac[6]);

// Session / multi-connection options from the profile (SoftEther Windows client
// equivalents). max_connection is clamped to [1, MAX_SE_CONNECTIONS]; the
// interval is the delay between additional-connection attempts. use_encrypt,
// use_compress and qos are the login-PACK request flags (see req_* above).
void softether_set_session_options(softether_connection_t* conn,
                                   int max_connection,
                                   int additional_connect_interval_ms,
                                   int use_encrypt,
                                   int use_compress,
                                   int qos);

// Snapshot of the session state for diagnostics: how many TCP connections are
// actually up, what the server allowed, and whether the data channel is TLS.
// Fills `out` with zeroes when `conn` is NULL.
typedef struct {
    int num_connections;             // primary + established additional sockets
    int requested_max_connection;    // what the profile asked for (post-clamp)
    int server_max_connection;       // what the server's Welcome PACK allows
    int use_ssl_data;                // 1 = data channel runs over TLS
    int server_use_encrypt;          // server-accepted session encryption flag
    int server_use_compress;         // server-accepted session compression flag
    int using_nat_t;                 // 1 = primary runs over the NAT-T transport
    int additional_attempts;         // total additional-connect attempts
    int additional_failed_count;     // consecutive additional-connect failures
    int additional_last_stage;       // SE_ADDL_STAGE_* of the last attempt
    int additional_last_error;       // server "error" of the last attempt (0 = none)
    int additional_last_attempt_ms;  // duration of the last attempt (ms)
    int additional_last_fail_stage;  // SE_ADDL_STAGE_* the last finished attempt died at
    char additional_last_detail[192];// specifics of the last finished attempt
    char primary_local_ip[64];       // primary TCP socket's local IP
    int primary_local_port;          // primary TCP socket's local port
    int protect_available;           // 1 = the platform protect hook is installed
    int additional_last_protect;     // 1 = excluded from tunnel, -1 = hook failed, 0 = no hook
} softether_session_info_t;

void softether_get_session_info(softether_connection_t* conn,
                                softether_session_info_t* out);

// Copies the reason of the last data-link read failure (see last_link_error)
// into `out`, always NUL-terminated. Empty when no link has failed yet.
void softether_get_last_link_error(softether_connection_t* conn,
                                   char* out, int max);

// Per-link byte counters: index 0 is the primary link, index i+1 is additional
// slot i. Copies up to `max` entries into tx_bytes/rx_bytes and returns the
// number of slots written (MAX_SE_CONNECTIONS + 1). Lets the app log show how
// the session's traffic spreads across its TCP links.
int softether_get_link_stats(softether_connection_t* conn,
                             uint64_t* tx_bytes,
                             uint64_t* rx_bytes,
                             int max);

// DHCP result
typedef struct {
    uint32_t assigned_ip;
    uint32_t subnet_mask;
    uint32_t gateway;
    uint32_t dns_server;
    uint32_t dns_server2;
    uint32_t lease_time;
    int success;
} dhcp_result_t;

// DHCP over SoftEther tunnel
int softether_do_dhcp(softether_connection_t* conn, dhcp_result_t* result);

// Utility
const char* softether_error_string(int error_code);

#ifdef __cplusplus
}
#endif

#endif // SOFTETHER_PROTOCOL_H
