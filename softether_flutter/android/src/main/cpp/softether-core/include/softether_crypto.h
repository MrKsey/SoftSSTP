#ifndef SOFTETHER_CRYPTO_H
#define SOFTETHER_CRYPTO_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// AES encryption context
typedef struct aes_context aes_context_t;

// AES key sizes
#define AES_128_KEY_SIZE    16
#define AES_256_KEY_SIZE    32
#define AES_BLOCK_SIZE      16

// AES modes
#define AES_MODE_CBC        0
#define AES_MODE_GCM        1

// Function prototypes

// AES operations
aes_context_t* aes_create(int mode, const uint8_t* key, size_t key_len, 
                          const uint8_t* iv, size_t iv_len);
void aes_destroy(aes_context_t* ctx);
int aes_encrypt(aes_context_t* ctx, const uint8_t* plaintext, size_t plaintext_len,
                uint8_t* ciphertext, size_t* ciphertext_len);
int aes_decrypt(aes_context_t* ctx, const uint8_t* ciphertext, size_t ciphertext_len,
                uint8_t* plaintext, size_t* plaintext_len);

// MD5 hashing (16 bytes output)
#define MD5_HASH_SIZE 16
void md5_hash(const uint8_t* data, size_t data_len, uint8_t* hash);

// SHA1 hashing (20 bytes output)
#define SHA1_HASH_SIZE 20
void sha1_hash(const uint8_t* data, size_t data_len, uint8_t* hash);

// SHA0 hashing (20 bytes output) - SoftEther's Hash() uses the original SHA-0
// for the password authentication chain (HashPassword / SecurePassword).
#define SHA0_HASH_SIZE 20
void sha0_hash(const uint8_t* data, size_t data_len, uint8_t* hash);

// RC4 stream cipher (in-place) - OpenSSL 3.x gates RC4 behind the legacy
// provider, so a native implementation is used for portability.
void rc4_crypt(const uint8_t* key, size_t key_len, uint8_t* data, size_t data_len);

// SHA256 hashing
void sha256_hash(const uint8_t* data, size_t data_len, uint8_t* hash);

// HMAC-SHA256
void hmac_sha256(const uint8_t* key, size_t key_len,
                 const uint8_t* data, size_t data_len,
                 uint8_t* mac);

// Random number generation
int generate_random_bytes(uint8_t* buffer, size_t len);

// TLS/SSL operations
typedef struct ssl_context ssl_context_t;

// Returned by ssl_read() (and propagated by the packet-layer read helpers) when
// the peer sent nothing within the socket timeout (SO_RCVTIMEO). It means "no
// data yet" and must NOT be treated as a broken link: the reference
// SoftEtherVPN client surfaces the same condition as SOCK_LATER and simply
// polls again. Anything else non-positive is a real error.
#define SE_SSL_READ_TIMEOUT (-2)

ssl_context_t* ssl_create_client(void);
void ssl_destroy(ssl_context_t* ctx);
int ssl_connect(ssl_context_t* ctx, int socket_fd, const char* hostname);
// Human-readable reason of the most recent ssl_connect() failure on THIS thread
// (empty string if the last handshake succeeded). Lets a caller surface why a
// handshake died without turning ssl_connect into an out-parameter API.
const char* ssl_last_error(void);
// Returns: >0 bytes read, 0 on clean peer shutdown, SE_SSL_READ_TIMEOUT when
// the socket timed out with no data, -1 on error.
int ssl_read(ssl_context_t* ctx, uint8_t* buffer, size_t len);
int ssl_write(ssl_context_t* ctx, const uint8_t* data, size_t len);
int ssl_has_pending(ssl_context_t* ctx);
void ssl_shutdown(ssl_context_t* ctx);

#ifdef __cplusplus
}
#endif

#endif // SOFTETHER_CRYPTO_H
