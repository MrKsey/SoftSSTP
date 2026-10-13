#ifndef SOFTETHER_SOCKET_H
#define SOFTETHER_SOCKET_H

#include <stdint.h>
#include <stddef.h>
#include <sys/socket.h>
#include <netinet/in.h>

#ifdef __cplusplus
extern "C" {
#endif

// Socket types
#define SOCKET_TYPE_TCP     0
#define SOCKET_TYPE_UDP     1

// Socket timeout options
#define SOCKET_TIMEOUT_MS   30000

// Socket structure
typedef struct {
    int fd;
    int type;
    int family;                // AF_INET or AF_INET6 (domain of sock->fd)
    struct sockaddr_storage addr;
    socklen_t addr_len;
    int connected;
    int timeout_ms;
    // Optional platform hook, called after the fd is created and before
    // connect(). Android uses it to exclude the socket from the VPN tunnel
    // (VpnService.protect): additional SoftEther TCP connections are opened
    // while the tunnel is already up, so an unprotected connect() would be
    // routed into the tunnel itself and hang until the connect timeout.
    int (*protect_fn)(void* userdata, int fd);
    void* protect_userdata;
    // Result of the most recent protect_fn call for the current fd: 1 = the
    // socket was excluded, -1 = the hook ran and failed, 0 = no hook installed.
    // Read by the connect caller so a socket that silently stayed inside the
    // tunnel is not mistaken for a healthy link.
    int protect_result;
} softether_socket_t;

// Function prototypes

// Socket creation and management
softether_socket_t* socket_create(int type);
void socket_destroy(softether_socket_t* sock);

// Connection
int socket_connect(softether_socket_t* sock, const char* host, int port);
int socket_connect_timeout(softether_socket_t* sock, const char* host, int port, int timeout_ms);
int socket_disconnect(softether_socket_t* sock);

// I/O operations
int socket_send(softether_socket_t* sock, const uint8_t* data, size_t len);
int socket_send_all(softether_socket_t* sock, const uint8_t* data, size_t len, int timeout_ms);
int socket_recv(softether_socket_t* sock, uint8_t* buffer, size_t max_len);
int socket_recv_all(softether_socket_t* sock, uint8_t* buffer, size_t len, int timeout_ms);

// Socket options
int socket_set_timeout(softether_socket_t* sock, int timeout_ms);
int socket_set_nodelay(softether_socket_t* sock, int enable);
int socket_set_keepalive(softether_socket_t* sock, int enable);

// Utility functions
int socket_is_connected(softether_socket_t* sock);
int socket_get_error(softether_socket_t* sock);
const char* socket_error_string(int error);

// Address resolution
int resolve_hostname(const char* hostname, char* ip_buffer, size_t buffer_size);
int resolve_hostname_family(const char* hostname, int family, char* ip_buffer, size_t buffer_size);

#ifdef __cplusplus
}
#endif

#endif // SOFTETHER_SOCKET_H
