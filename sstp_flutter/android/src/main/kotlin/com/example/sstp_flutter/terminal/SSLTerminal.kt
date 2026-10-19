package com.example.sstp_flutter.terminal

import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.client.ClientBridge
import com.example.sstp_flutter.client.ControlMessage
import com.example.sstp_flutter.client.Result
import com.example.sstp_flutter.client.Where
import com.example.sstp_flutter.extension.capacityAfterLimit
import com.example.sstp_flutter.extension.slide
import com.example.sstp_flutter.extension.toIntAsUByte
import com.example.sstp_flutter.preference.OscPrefKey
import com.example.sstp_flutter.preference.accessor.*
import com.example.sstp_flutter.unit.DataUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.security.SecureRandom
import javax.net.ssl.X509TrustManager


private const val HTTP_DELIMITER = "\r\n"
private const val HTTP_SUFFIX = "\r\n\r\n"

// protect() of the tunnel socket can fail transiently: the right to call it is
// silently revoked when another VPN app calls VpnService.prepare(). The socket
// is then re-protected after our own prepare() takes the right back, up to
// PROTECT_ATTEMPTS times (see ensureProtectRight and the connect() retry).
private const val PROTECT_ATTEMPTS = 3
private const val PROTECT_RETRY_DELAY_MS = 200L

internal const val SSL_REQUEST_INTERVAL = 10_000L

// Socket read timeout of the idle read loop. With the screen on the loop has to
// come back quickly so the UI stays responsive (and the 1 s tick is what keeps
// the SSTP/PPP echo timers on schedule). With the screen off nothing is visible,
// so the loop is allowed to block far longer: the same echo checks still run on
// every wake-up, and the deadline (2 x 20 s) stays comfortably inside them.
private const val READ_TIMEOUT_SCREEN_ON_MS = 1_000
private const val READ_TIMEOUT_SCREEN_OFF_MS = 10_000

internal class SSLTerminal(private val bridge: ClientBridge) {
    private val mutex = Mutex()

    private var socket: Socket? = null
    private lateinit var socketInputStream: InputStream
    private lateinit var socketOutputStream: OutputStream
    private lateinit var inboundBuffer: ByteBuffer
    private lateinit var outboundBuffer: ByteBuffer

    private lateinit var engine: SSLEngine

    private var jobInitialize: Job? = null

    private var loggedClientHello = false
    private var loggedServerData = false

    // Current socket read timeout, kept screen-aware (see onScreenStateChanged).
    // Seeded from the screen state at construction so a tunnel started with the
    // screen already off gets the relaxed timeout from the first read.
    @Volatile
    private var readTimeoutMs: Int =
        if (bridge.service.isScreenInteractive) READ_TIMEOUT_SCREEN_ON_MS else READ_TIMEOUT_SCREEN_OFF_MS

    private val doUseProxy = getBooleanPrefValue(OscPrefKey.PROXY_DO_USE_PROXY, bridge.prefs)
    private val sslHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, bridge.prefs)
    private val sslPort = getIntPrefValue(OscPrefKey.SSL_PORT, bridge.prefs)
    private val selectedVersion = getStringPrefValue(OscPrefKey.SSL_VERSION, bridge.prefs)
    private val enabledSuites = getSetPrefValue(OscPrefKey.SSL_SUITES, bridge.prefs)

    internal suspend fun initialize() {
        jobInitialize = bridge.service.scope.launch(bridge.handler) {
            if (!startHandshake()) return@launch

            if (!establishHttps()) return@launch

            bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.PROCEEDED))
        }
    }

    /**
     * Trust managers that accept a chain when EITHER the imported CA set or the
     * system trust store accepts it: the profile's "Add Certificate" list is
     * meant to make a private/self-signed server verifiable, not to lock out
     * publicly trusted servers. Returns null when no usable certificates were
     * imported, so the caller falls back to the system default context.
     */
    private fun createTrustManagers(): Array<TrustManager>? {
        val dirUri = getURIPrefValue(OscPrefKey.SSL_CERT_DIR, bridge.prefs) ?: return null
        val document = DocumentFile.fromTreeUri(bridge.service, dirUri) ?: return null

        val certFactory = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getDefaultType().let {
            KeyStore.getInstance(it)
        }
        keyStore.load(null, null)

        var imported = 0
        for (file in document.listFiles()) {
            if (!file.isFile) continue
            try {
                bridge.service.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val ca = certFactory.generateCertificate(stream) as X509Certificate
                    keyStore.setCertificateEntry(file.name, ca)
                    imported++
                }
            } catch (e: Exception) {
                ConnectionLog.append("SSL", "Skipping certificate ${file.name}: ${e.message}")
            }
        }
        if (imported == 0) return null

        val customFactory = TrustManagerFactory.getDefaultAlgorithm().let {
            TrustManagerFactory.getInstance(it)
        }
        customFactory.init(keyStore)
        val custom = customFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: return null

        val systemFactory = TrustManagerFactory.getDefaultAlgorithm().let {
            TrustManagerFactory.getInstance(it)
        }
        systemFactory.init(null as KeyStore?)
        val system = systemFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: return arrayOf(custom)

        val composite = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + custom.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                custom.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    custom.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    system.checkServerTrusted(chain, authType)
                }
            }
        }
        return arrayOf(composite)
    }

    private suspend fun startHandshake(): Boolean {
        val sslContext: SSLContext

        val verifyCert = getBooleanPrefValue(OscPrefKey.SSL_DO_VERIFY_CERT, bridge.prefs)
        val addCert = getBooleanPrefValue(OscPrefKey.SSL_DO_ADD_CERT, bridge.prefs)
        val verifyHost = getBooleanPrefValue(OscPrefKey.SSL_DO_VERIFY, bridge.prefs)
        ConnectionLog.append(
            "SSL",
            "Handshake start: host=$sslHostname port=$sslPort version=$selectedVersion " +
                "verifyCert=$verifyCert addCert=$addCert verifyHost=$verifyHost proxy=$doUseProxy"
        )

        if (verifyCert) {
            val customTrust = if (addCert) createTrustManagers() else null
            sslContext = if (customTrust != null) {
                // "TLS" instead of a version-specific algorithm: a generic
                // context exposes every protocol version (incl. TLS 1.3) and
                // its cipher suites, while the negotiated version is limited
                // below through enabledProtocols.
                SSLContext.getInstance("TLS").also {
                    it.init(null, customTrust, null)
                }
            } else {
                if (addCert) {
                    ConnectionLog.append(
                        "SSL",
                        "No usable imported certificates; verifying with the system trust store"
                    )
                }
                SSLContext.getDefault()
            }
        } else {
            // Trust-all context. A version-specific algorithm such as
            // "TLSv1.3" may be unavailable or may not expose the TLS 1.3
            // cipher suites on some Android versions, which silently breaks
            // the handshake against TLS 1.3-only servers. Use the generic
            // "TLS" algorithm and restrict the version afterwards instead.
            sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return arrayOf()
                }

                override fun checkClientTrusted(
                    certs: Array<X509Certificate>,
                    authType: String
                ) {
                }

                override fun checkServerTrusted(
                    certs: Array<X509Certificate>,
                    authType: String
                ) {
                }
            }), SecureRandom())
        }
        engine = sslContext.createSSLEngine(sslHostname, sslPort)
        engine.useClientMode = true

        if (selectedVersion != "DEFAULT") {
            engine.enabledProtocols = arrayOf(selectedVersion)
        }

        ConnectionLog.append(
            "SSL",
            "Engine ready: protocols=${engine.enabledProtocols.joinToString()} " +
                "enabledSuites=${engine.enabledCipherSuites.size} " +
                "provider=${sslContext.provider.name}"
        )

        if (getBooleanPrefValue(OscPrefKey.SSL_DO_SELECT_SUITES, bridge.prefs)) {
            val sortedSuites = engine.supportedCipherSuites.filter {
                enabledSuites.contains(it)
            }

            engine.enabledCipherSuites = sortedSuites.toTypedArray()
        }

        // Some servers (e.g. behind a reverse proxy / CDN) route TLS by SNI and
        // reset the connection when the requested server name does not match.
        // Allow overriding the SNI independently of the connect hostname.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            getBooleanPrefValue(OscPrefKey.SSL_DO_USE_CUSTOM_SNI, bridge.prefs)
        ) {
            val sni = getStringPrefValue(OscPrefKey.SSL_CUSTOM_SNI, bridge.prefs)
            if (sni.isNotEmpty()) {
                try {
                    engine.sslParameters = engine.sslParameters.also {
                        it.serverNames = listOf(SNIHostName(sni))
                    }
                    ConnectionLog.append("SSL", "Custom SNI applied: $sni")
                } catch (e: IllegalArgumentException) {
                    ConnectionLog.append("SSL", "Invalid custom SNI '$sni': ${e.message}")
                }
            }
        }

        val socketHostname = if (doUseProxy) getStringPrefValue(OscPrefKey.PROXY_HOSTNAME, bridge.prefs) else sslHostname
        val socketPort = if (doUseProxy) getIntPrefValue(OscPrefKey.PROXY_PORT, bridge.prefs) else sslPort

        // The tunnel's own connection must be created on a real (non-VPN)
        // network. With another VPN up (e.g. AdGuard) a plain socket is created
        // on that VPN's interface instead, and protect() does not always fix it —
        // it returns false in that state (see the retry below).
        val underlying = bridge.service.findUnderlyingNetwork()
        socket = if (underlying != null) {
            underlying.socketFactory.createSocket(socketHostname, socketPort) as Socket
        } else {
            Socket(socketHostname, socketPort)
        }.also {
            socketInputStream = it.getInputStream()
            socketOutputStream = it.getOutputStream()
        }
        // Also protect it, so a VPN started later cannot capture the socket.
        var protected = bridge.service.protect(socket)
        var protectAttempts = 0
        while (!protected && protectAttempts < PROTECT_ATTEMPTS) {
            protectAttempts++
            // protect() fails when the right has been revoked: the platform
            // grants it to the app that last called VpnService.prepare(), and
            // another VPN doing that (e.g. AdGuard bringing its protection
            // back) takes it away from us. An unprotected tunnel socket is
            // captured by our own TUN the moment it comes up, which leaves a
            // session that reports "connected" but carries no traffic. Our own
            // prepare() (the same call that claims the VPN slot) takes the
            // right back, so retry after it.
            if (!bridge.service.ensureProtectRight()) break
            protected = bridge.service.protect(socket)
            if (!protected) delay(PROTECT_RETRY_DELAY_MS)
        }
        ConnectionLog.append(
            "SSL",
            "TCP connected: ${socket!!.remoteSocketAddress} local=${socket!!.localSocketAddress}" +
                " protected=$protected onUnderlyingNetwork=${underlying != null}" +
                (if (protectAttempts > 0) " protectRetries=$protectAttempts" else "")
        )
        if (!protected) {
            ConnectionLog.append(
                "SSL",
                "WARNING: tunnel socket left unprotected — the TUN may capture it"
            )
        }
        bridge.service.logNetworkSnapshot("Socket created")

        if (doUseProxy) {
            if (!establishProxy()) {
                return false
            }
        }

        inboundBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).also { it.limit(0) }
        outboundBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
        val tempBuffer = ByteBuffer.allocate(0)

        engine.beginHandshake()

        try {
            while (true) {
                yield()

                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val result = send(tempBuffer)
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                            break
                        }
                    }

                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        val result = receive(tempBuffer)
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                            break
                        }
                    }

                    else -> {
                        throw NotImplementedError(engine.handshakeStatus.name)
                    }
                }
            }
        } catch (e: Exception) {
            ConnectionLog.append(
                "SSL",
                "Handshake failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
        }

        ConnectionLog.append(
            "SSL",
            "Handshake finished: protocol=${engine.session.protocol} suite=${engine.session.cipherSuite}"
        )

        if (verifyHost) {
            HttpsURLConnection.getDefaultHostnameVerifier().also {
                if (!it.verify(sslHostname, engine.session)) {
                    ConnectionLog.append("SSL", "Hostname verification failed for $sslHostname")
                    bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.ERR_VERIFICATION_FAILED))
                    return false
                }
            }
        }

        return true
    }

    private suspend fun establishProxy(): Boolean {
        val username = getStringPrefValue(OscPrefKey.PROXY_USERNAME, bridge.prefs)
        val password = getStringPrefValue(OscPrefKey.PROXY_PASSWORD, bridge.prefs)

        val request = mutableListOf(
            "CONNECT ${sslHostname}:${sslPort} HTTP/1.1",
            "Host: ${sslHostname}:${sslPort}",
            "SSTPVERSION: 1.0"
        ).also {
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val encoded = Base64.encode(
                    "$username:$password".toByteArray(Charsets.US_ASCII),
                    Base64.NO_WRAP
                ).toString(Charsets.US_ASCII)

                it.add("Proxy-Authorization: Basic $encoded")
            }
        }.joinToString(separator = HTTP_DELIMITER, postfix = HTTP_SUFFIX).toByteArray(Charsets.US_ASCII)

        socketOutputStream.write(request)
        socketOutputStream.flush()

        var response = ""
        while (true) {
            response += socketInputStream.read().toChar()

            if (response.endsWith(HTTP_SUFFIX)) {
                break
            }
        }

        val responseHeader = response.split(HTTP_DELIMITER)[0]

        if (responseHeader.contains("403")) {
            bridge.controlMailbox.send(ControlMessage(Where.PROXY, Result.ERR_AUTHENTICATION_FAILED))
            return false
        }

        if (!responseHeader.contains("200")) {
            bridge.controlMailbox.send(ControlMessage(Where.PROXY, Result.ERR_UNEXPECTED_MESSAGE))
            return false
        }

        return true
    }

    private suspend fun establishHttps(): Boolean {
        val buffer = ByteBuffer.allocate(getApplicationBufferSize())

        val request = arrayOf(
            "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
            "Content-Length: 18446744073709551615",
            "Host: $sslHostname",
            "SSTPCORRELATIONID: {${bridge.guid}}"
        ).joinToString(separator = HTTP_DELIMITER, postfix = HTTP_SUFFIX).toByteArray(Charsets.US_ASCII)

        buffer.put(request)
        buffer.flip()

        send(buffer)

        buffer.position(0)
        buffer.limit(0)

        var response = ""
        outer@ while (true) {
            receive(buffer)

            for (i in 0 until buffer.remaining()) {
                response += buffer.get().toIntAsUByte().toChar()

                if (response.endsWith(HTTP_SUFFIX)) {
                    break@outer
                }
            }
        }

        if (!response.split(HTTP_DELIMITER)[0].contains("200")) {
            ConnectionLog.append("SSL", "SSTP HTTP response not 200: ${response.split(HTTP_DELIMITER)[0]}")
            bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.ERR_UNEXPECTED_MESSAGE))
            return false
        }

        ConnectionLog.append("SSL", "SSTP HTTP 200 OK: ${response.split(HTTP_DELIMITER)[0]}")
        socket!!.soTimeout = readTimeoutMs
        bridge.service.protect(socket)
        return true
    }

    /**
     * Applies a new socket read timeout when the screen turns on or off. The read
     * already in flight keeps the old timeout, so the change takes effect on the
     * next idle wake-up.
     */
    internal fun onScreenStateChanged(interactive: Boolean) {
        val timeout = if (interactive) READ_TIMEOUT_SCREEN_ON_MS else READ_TIMEOUT_SCREEN_OFF_MS
        readTimeoutMs = timeout
        try {
            socket?.soTimeout = timeout
        } catch (_: Exception) {
            // A socket that is already closing must not break the transition.
        }
    }

    internal fun getSession(): SSLSession {
        return engine.session
    }

    internal fun getServerCertificate(): ByteArray {
        return engine.session.peerCertificates[0].encoded
    }

    internal fun getApplicationBufferSize(): Int {
        return engine.session.applicationBufferSize
    }

    internal fun receive(buffer: ByteBuffer): SSLEngineResult {
        var startPayload: Int
        var result: SSLEngineResult

        while (true) {
            startPayload = buffer.position()
            buffer.position(buffer.limit())
            buffer.limit(buffer.capacity())

            result = engine.unwrap(inboundBuffer, buffer)

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    break
                }

                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    buffer.limit(buffer.position())
                    buffer.position(startPayload)

                    buffer.slide()
                }

                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    buffer.limit(buffer.position())
                    buffer.position(startPayload)

                    inboundBuffer.slide()

                    try {
                        val readSize = socketInputStream.read(
                            inboundBuffer.array(),
                            inboundBuffer.limit(),
                            inboundBuffer.capacityAfterLimit
                        )

                        if (readSize < 0) {
                            // Peer closed the connection (e.g. server rejected
                            // the TLS handshake because of a mismatched SNI).
                            throw SSLException("Connection closed by the server during handshake")
                        }

                        if (readSize > 0 && !loggedServerData) {
                            loggedServerData = true
                            val start = inboundBuffer.limit()
                            val bytes = inboundBuffer.array().copyOfRange(start, start + readSize)
                            ConnectionLog.append(
                                "SSL",
                                "Server response ($readSize bytes): ${bytes.joinToString("") { "%02x".format(it) }}"
                            )
                        }

                        if (readSize > 0) {
                            // Any byte from the server proves the session is still
                            // alive; the service's watchdog uses this timestamp to
                            // tell a silent tunnel from a working one.
                            bridge.lastIncomingAtMs = SystemClock.elapsedRealtime()
                        }

                        inboundBuffer.limit(inboundBuffer.limit() + readSize)
                    } catch (_: SocketTimeoutException) { }
                }

                else -> {
                    throw NotImplementedError(result.status.name)
                }
            }
        }

        buffer.limit(buffer.position())
        buffer.position(startPayload)

        return result
    }

    internal suspend fun send(buffer: ByteBuffer): SSLEngineResult {
        mutex.withLock {
            var result: SSLEngineResult

            while (true) {
                outboundBuffer.clear()

                result = engine.wrap(buffer, outboundBuffer)
                if (result.status != SSLEngineResult.Status.OK) {
                    throw NotImplementedError(result.status.name)
                }

                socketOutputStream.write(
                    outboundBuffer.array(),
                    0,
                    outboundBuffer.position()
                )

                if (!loggedClientHello) {
                    loggedClientHello = true
                    val bytes = outboundBuffer.array().copyOfRange(0, outboundBuffer.position())
                    ConnectionLog.append(
                        "SSL",
                        "ClientHello (${bytes.size} bytes): ${bytes.joinToString("") { "%02x".format(it) }}"
                    )
                }

                if (!buffer.hasRemaining()) {
                    socketOutputStream.flush()

                    break
                }
            }

            return result
        }
    }

    internal suspend fun sendDataUnit(unit: DataUnit) {
        ByteBuffer.allocate(unit.length).also {
            unit.write(it)
            it.flip()
            send(it)
        }
    }

    internal fun close() {
        jobInitialize?.cancel()
        try {
            ConnectionLog.append(
                "SSL",
                "Closing socket ${socket?.localSocketAddress} -> ${socket?.remoteSocketAddress}"
            )
        } catch (_: Exception) {
        }
        socket?.close()
    }
}
