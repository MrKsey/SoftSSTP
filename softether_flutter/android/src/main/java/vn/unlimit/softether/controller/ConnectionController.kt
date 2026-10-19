package vn.unlimit.softether.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.SoftEtherLog
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.client.SoftEtherClient
import vn.unlimit.softether.client.protocol.KeepAliveManager
import vn.unlimit.softether.model.ClientInfo
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.ConnectionState
import vn.unlimit.softether.model.SoftEtherError
import vn.unlimit.softether.terminal.TunTerminal
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ConnectionController - Manages the SoftEther VPN connection lifecycle
 * Implements complete data forwarding between TUN interface and SoftEther connection
 */
class ConnectionController(
    private val service: SoftEtherVpnService,
    private val config: ConnectionConfig,
    private val onStateChange: (ConnectionState) -> Unit,
    private val onError: (String) -> Unit,
    private val onTrafficUpdate: (SoftEtherTrafficSnapshot) -> Unit,
    // Told when the controller parks the tunnel because the device has no
    // network at all, and when it resumes. The service uses it to say so in the
    // notification instead of looking like a stuck connect.
    private val onWaitingForNetwork: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "ConnectionController"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 3000L
        private const val STATS_INTERVAL_MS = 1000L
        private const val LINK_STATS_LOG_INTERVAL_MS = 5000L
        private const val RX_BATCH_MAX_PACKETS = 32  // Phase 13D: frames per receiveBatch call
        private const val NATIVE_TEARDOWN_TIMEOUT_MS = 500L
        // Energy saving: idle wait of the native receive loop, and the stretch
        // applied to the per-second housekeeping cycles while it is on.
        private const val IDLE_POLL_MS = 100
        private const val IDLE_POLL_POWER_SAVE_MS = 500
        // Screen off: nothing is visible, so the native receive loop may sleep
        // much longer between idle wake-ups. Data still arrives immediately —
        // poll() returns on the first byte.
        private const val IDLE_POLL_SCREEN_OFF_MS = 3_000
        private const val POWER_SAVE_CYCLE_FACTOR = 3
        // Screen off: the per-second housekeeping cycles are stretched this much.
        // Nothing is visible, and the screen-on event wakes the snapshot loop
        // immediately, so a longer gap costs nothing but CPU wake-ups.
        private const val SCREEN_OFF_CYCLE_FACTOR = 30
        // Screen off: the keepalive watchdog check cadence. The timeout itself is
        // 30 s, so checking every 10 s still catches a dead session in time.
        private const val WATCHDOG_INTERVAL_SCREEN_OFF_MS = 10_000L
        // Reconnect after a network switch: how long the reconnect flow waits
        // before re-checking whether an underlying network came back. Waiting is
        // event-driven (the service's network callback wakes it immediately); this
        // is only the fallback, so a missed callback cannot park the tunnel
        // forever. It is deliberately long — a parked tunnel must not wake the CPU
        // often, and a late reconnect costs nothing.
        private const val NETWORK_WAIT_FALLBACK_MS = 30_000L
    }

    /**
     * Stretch applied to the periodic housekeeping cycles (1 when off). Read
     * live from the shared energy-saving flag, so toggling the switch takes
     * effect without reconnecting.
     */
    private fun powerSaveFactor(): Long =
        if (SoftEtherVpnService.powerSavingLive) POWER_SAVE_CYCLE_FACTOR.toLong() else 1L

    // Wakes the snapshot loop as soon as the screen comes back, so the app and
    // the notification get the current figures without waiting for the next
    // (long) screen-off tick. Conflated: one pending wake-up is enough.
    private val snapshotWakeUp = Channel<Unit>(Channel.CONFLATED)


    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = SoftEtherClient()
    private val isCancelled = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    // True while a connect flow owns the native handle (performConnect alive).
    // Deliberately NOT derived from currentState: disconnect() resets the state
    // to DISCONNECTED immediately, but the blocked nativeConnectWithHub JNI
    // call may still hold connect_mutex long after that.
    private val connectInFlight = AtomicBoolean(false)
    // Latches the moment a connect flow finishes owning the native handle.
    // destroyResources() awaits it (bounded) instead of a fixed Thread.sleep
    // heuristic before calling nativeDestroy, so a slow TLS read in the connect
    // flow can't race the destroy. Fresh instance per connect flow.
    private var nativeTeardownGate = CountDownLatch(1)
    private val reconnectAttempts = AtomicInteger(0)
    private val connectionMutex = Mutex()
    private var stateMonitorJob: Job? = null

    // The per-second statistics/traffic loop of the current session.
    private var statsJob: Job? = null

    // Keepalive bookkeeping and its watchdog of the current session. Both live
    // as fields, not as locals of startDataForwarding(): a reconnect creates a
    // new forwarding pair, and the previous watchdog must be cancelled. A
    // surviving one keeps checking a timestamp that only the dead receive loop
    // refreshed, so it declares the fresh session dead ~35 s after it came up.
    private val keepAlive = KeepAliveManager(client)
    private var keepAliveWatchdogJob: Job? = null

    // Statistics
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private var lastPublishedSentBytes = 0L
    private var lastPublishedReceivedBytes = 0L
    private var lastPublishedAtMs = 0L

    // Written by the service's network callback (its own HandlerThread) and read
    // by the reconnect flow on an IO thread.
    @Volatile
    private var isNetworkAvailable = true

    // Wakes the parked reconnect flow the moment the service reports a network,
    // instead of it polling. Conflated: one pending signal is enough.
    private val networkWakeup = Channel<Unit>(Channel.CONFLATED)

    // True while the tunnel is parked waiting for a network. The statistics loop
    // stops ticking meanwhile: there is no session to report on and nobody to
    // report to, and a tick per second is exactly the kind of wake-up a paused
    // tunnel must not do.
    private val isParked = AtomicBoolean(false)

    // Wall-clock of the last return from the native receive loop. The watchdog
    // logs (and the UI can show) how long the data path has been silent, which
    // distinguishes "no traffic" from a blocked native receive.
    private val lastReceiveActivityMs = AtomicLong(System.currentTimeMillis())

    // Last TCP connection count written to the app log, so the periodic check
    // logs only when additional connections come up or drop.
    private var lastLoggedConnectionCount = -1
    // Last additional-connection attempt counter written to the app log: a failed
    // attempt keeps the connection count at 1, so the attempt counter (and its
    // stage) is what changes while multi-connection is being retried.
    private var lastLoggedAdditionalAttempts = -1

    // Last time the per-link traffic split was written to the app log. Throttled
    // separately from the session snapshot so a multi-connection session gets a
    // periodic, low-noise view of how the load spreads across its TCP links.
    private var lastLinkStatsLogAtMs = 0L

    private var currentState: ConnectionState = ConnectionState.DISCONNECTED
        set(value) {
            field = value
            onStateChange(value)
        }

    private var nativeHandle: Long = 0
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunTerminal: TunTerminal? = null

    /**
     * Handle network connectivity changes.
     * On network loss, do NOT destroy the connection — the data forwarding loops will
     * detect send/receive errors and trigger attemptReconnect() automatically.
     * Calling disconnect() or onError() here kills the scope permanently and makes
     * reconnect impossible.
     */
    fun onNetworkChanged(isConnected: Boolean) {
        Log.d(TAG, "Network state changed: connected=$isConnected")
        isNetworkAvailable = isConnected

        if (isConnected) {
            // A reconnect flow may be parked waiting for exactly this.
            networkWakeup.trySend(Unit)
        } else {
            Log.w(TAG, "Network lost — data loops will detect and attempt reconnect")
        }
    }

    private fun interruptNativeConnection() {
        val handle = nativeHandle
        if (handle != 0L) {
            try {
                client.nativeDisconnect(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error interrupting native connection", e)
            }
        }
    }

    /** DHCP-assigned local IP address (available after successful connect) */
    var assignedLocalIp: String? = null
        private set

    /**
     * Initiates VPN connection with automatic retry logic
     */
    suspend fun connect() {
        // Check if connection is already active before acquiring lock to avoid waiting
        if (currentState != ConnectionState.DISCONNECTED && currentState != ConnectionState.ERROR) {
            Log.w(TAG, "Connection already in progress or established")
            return
        }

        connectionMutex.withLock {
            if (isCancelled.get()) {
                Log.w(TAG, "Connection cancelled, not starting")
                return
            }
            
            if (currentState != ConnectionState.DISCONNECTED && currentState != ConnectionState.ERROR) {
                Log.w(TAG, "Connection already in progress or established")
                return
            }

            reconnectAttempts.set(0)

            // Iterative connection loop
            while (true) {
                // Switching the VPN on with no network at all parks it instead of
                // failing with "network unavailable": the tunnel comes up by itself
                // once a network appears.
                waitForNetwork()
                if (isCancelled.get()) return

                try {
                    performConnect()
                    // If performConnect returns, it means success (currentState == CONNECTED)
                    return
                } catch (e: Exception) {
                    if (isCancelled.get()) {
                        Log.d(TAG, "Connection cancelled, not retrying")
                        return
                    }

                    if (reconnectAttempts.incrementAndGet() < MAX_RECONNECT_ATTEMPTS) {
                        Log.w(TAG, "Connection failed, attempting retry ${reconnectAttempts.get()}/$MAX_RECONNECT_ATTEMPTS")
                        delay(RECONNECT_DELAY_MS)
                        // Loop continues to next attempt
                    } else {
                        Log.e(TAG, "Connection failed after ${reconnectAttempts.get()} attempts", e)
                        currentState = ConnectionState.DISCONNECTED
                        onError(e.message ?: "Unknown error")
                        // Stop loop
                        return
                    }
                }
            }
        }
    }

    /**
     * Build client info for reporting to server
     */
    private fun buildClientInfo(rudpPort: Int): ClientInfo {
        // Get local non-loopback IP
        var clientIp = "0.0.0.0"
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress &&
                            !addr.isLinkLocalAddress &&
                            (addr is java.net.Inet4Address || addr is java.net.Inet6Address)
                        ) {
                            clientIp = addr.hostAddress ?: "0.0.0.0"
                            break
                        }
                    }
                    if (clientIp != "0.0.0.0") break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP", e)
        }
        
        // Get hostname
        var hostName = ""
        try {
            hostName = java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hostname", e)
        }
        
        // Resolve server IP
        var serverIp = "0.0.0.0"
        try {
            serverIp = java.net.InetAddress.getByName(config.serverHost).hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve server IP for ${config.serverHost}", e)
        }
        
        Log.d(TAG, "Client IP: $clientIp, Server IP: $serverIp, Port: ${config.serverPort}")
        
        return ClientInfo(
            productName = config.clientProductName,
            productVersion = config.clientVersion,
            productBuild = config.clientBuild,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE,
            osProductId = Build.FINGERPRINT,
            hostName = hostName,
            clientIpAddress = clientIp,
            clientPort = rudpPort,
            serverHostName = config.serverHost,
            serverIpAddress = serverIp,
            serverPort = config.serverPort
        )
    }

    /**
     * Applies the client MAC to the active native handle.
     *
     * Uses the profile's pinned MAC when it parses to six bytes; otherwise
     * derives a stable one from the server identity so local-bridge ARP entries
     * survive reconnects.
     */
    private fun applyClientMac() {
        runCatching {
            val mac = parseClientMac(config.clientMac) ?: deriveClientMac()
            client.setClientMac(nativeHandle, mac)
            val formatted = mac.joinToString(":") { String.format("%02X", it) }
            Log.d(TAG, "Client MAC: $formatted")
            SoftEtherLog.append("MAC", "Client MAC: $formatted (${if (parseClientMac(config.clientMac) != null) "profile" else "derived"})")
        }.onFailure {
            Log.w(TAG, "Failed to set client MAC", it)
            SoftEtherLog.append("MAC", "Failed to set client MAC: ${it.message}")
        }
    }

    /**
     * Applies the idle wait to the active native handle. poll() still returns on
     * the first byte, so this only changes how often an idle tunnel wakes the
     * CPU. Both the energy-saving switch and the screen state stretch it; the
     * screen state wins while the screen is off.
     */
    private fun applyPowerSaving() {
        val idlePollMs = idlePollMs()
        runCatching {
            client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_IDLE_POLL_MS, idlePollMs)
            Log.d(
                TAG,
                "Idle poll: ${idlePollMs}ms (power saving=${SoftEtherVpnService.powerSavingLive}, " +
                    "screen interactive=${service.isScreenInteractive})"
            )
        }.onFailure {
            Log.w(TAG, "Failed to set the idle poll", it)
        }
    }

    /** Idle wait of the native receive loop for the current screen/power state. */
    private fun idlePollMs(): Long = when {
        !service.isScreenInteractive -> IDLE_POLL_SCREEN_OFF_MS.toLong()
        SoftEtherVpnService.powerSavingLive -> IDLE_POLL_POWER_SAVE_MS.toLong()
        else -> IDLE_POLL_MS.toLong()
    }

    /**
     * Called by the service on every screen on/off transition: re-applies the
     * idle poll to the live handle and, when the screen comes back, wakes the
     * snapshot loop so the figures appear at once.
     */
    fun onScreenStateChanged(interactive: Boolean) {
        if (nativeHandle != 0L) {
            applyPowerSaving()
        }
        if (interactive) {
            snapshotWakeUp.trySend(Unit)
        }
    }

    /**
     * Called when the energy-saving switch is toggled: re-applies the idle poll
     * to the live handle and wakes the statistics loop, so the new cadence (and
     * with it the app's refresh rate) takes effect at once, without a reconnect.
     */
    fun onPowerSavingChanged() {
        if (nativeHandle != 0L) {
            applyPowerSaving()
        }
        snapshotWakeUp.trySend(Unit)
    }

    /** Stable locally-administered unicast MAC derived from the server identity. */
    private fun deriveClientMac(): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("vpngate-mac:${config.serverHost}:${config.serverPort}".toByteArray())
        val mac = ByteArray(6)
        mac[0] = 0x5E  // SoftEther-style locally administered unicast prefix
        for (i in 1..5) mac[i] = digest[i]
        mac[0] = (mac[0].toInt() and 0xFE).toByte()  // clear multicast bit
        return mac
    }

    /**
     * Parses "AA:BB:CC:DD:EE:FF" (also accepting '-' or '.' separators) into six
     * bytes. Returns null when the value is empty or not a valid unicast MAC, so
     * the caller falls back to the derived address.
     */
    private fun parseClientMac(value: String): ByteArray? {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(':', '-', '.')
        if (parts.size != 6) return null
        val mac = ByteArray(6)
        for (i in 0 until 6) {
            val octet = parts[i].toIntOrNull(16) ?: return null
            if (octet !in 0..0xFF) return null
            mac[i] = octet.toByte()
        }
        // Reject multicast addresses (lowest bit of the first octet) — they are
        // not valid client unicast addresses.
        if ((mac[0].toInt() and 0x01) != 0) return null
        return mac
    }

    /**
     * Perform actual connection
     */
    private suspend fun performConnect() {
        Log.d(TAG, "Starting connection to ${config.serverHost}:${config.serverPort}")
        SoftEtherLog.startSession(
            "server=${config.serverHost}:${config.serverPort} hub=${config.virtualHub} " +
                "user=${config.username} " +
                "udpAcceleration=${if (config.useUdp) "on" else "off"} " +
                "clientMac=${config.clientMac.ifBlank { "<derived>" }}"
        )
        // Requested SoftEther session options (Windows-client equivalents). The
        // server decides the final values; the negotiated result is logged once
        // the session is up (see logSessionInfo()). config.useUdp is the profile's
        // "UDP acceleration enabled" (the inverse of "Disable UDP Acceleration");
        // the label used to print the inverse of that flag, so an enabled option
        // read as disabled and vice versa.
        SoftEtherLog.append(
            "SESSION",
            "Requested: tcpConnections=${config.maxConnections} " +
                "establishInterval=${config.establishIntervalMs}ms " +
                "sslEncrypt=${config.useEncrypt} " +
                "udpAcceleration=${if (config.useUdp) "on" else "off"} " +
                "qos=${if (config.qos) "on" else "off"} udpPort=${config.udpPort}"
        )
        // Fresh session: forget the previous counters so the first check always logs.
        lastLoggedConnectionCount = -1
        lastLoggedAdditionalAttempts = -1
        lastLinkStatsLogAtMs = 0L
        currentState = ConnectionState.CONNECTING
        isCancelled.set(false)

        // Single-owner teardown: while this connect flow is alive, ONLY this
        // function destroys the native handle. disconnect()/destroyResources()
        // defer to it (see connectInFlight) so a user cancel during the
        // blocking nativeConnectWithHub can never free the connection out
        // from under the connect thread (zombie connect + stuck connect_mutex).
        //
        // Raise connectInFlight BEFORE nativeCreate(): if a cancel lands in
        // the window between handle creation and the flag being set, teardown
        // paths see nativeHandle != 0 && !connectInFlight and destroy the
        // fresh handle under the connect thread (use-after-free crash inside
        // softether_protocol_login / ssl_write).
        connectInFlight.set(true)
        nativeTeardownGate = CountDownLatch(1)
        try {
            nativeHandle = client.nativeCreate()
            if (nativeHandle == 0L) {
                throw Exception("Failed to create native connection")
            }
            // Publish the handle so the client's pre-connect setters (timeout,
            // session options) and getAllSocketFds() target this connection
            // instead of handle 0.
            client.externalHandle = nativeHandle

            performConnectInner()
        } catch (e: Throwable) {
            Log.d(TAG, "Connect flow ended (${e.javaClass.simpleName}), tearing down native handle")
            teardownNativeConnection()
            throw e
        } finally {
            nativeTeardownGate.countDown()
            connectInFlight.set(false)
        }
    }

    private fun isConnectInFlight(): Boolean = connectInFlight.get()

    /**
     * Gracefully disconnect and free the native handle (idempotent).
     * Only called from the connect flow itself or when no connect is in flight.
     */
    private fun teardownNativeConnection() {
        client.externalHandle = 0
        protectedFds.clear()
        val handle = nativeHandle
        nativeHandle = 0
        if (handle == 0L) return

        try {
            Log.d(TAG, "Tearing down native handle $handle")
            client.nativeDisconnect(handle)
            client.nativeDestroy(handle)
            Log.d(TAG, "Native handle $handle torn down")
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down native handle", e)
        }
    }

    private suspend fun performConnectInner() {
        // Check if already cancelled before proceeding
        if (isCancelled.get()) {
            Log.d(TAG, "Connection cancelled before starting")
            throw CancellationException("Connection cancelled by user")
        }

        // Set timeout
        client.setTimeout(config.connectTimeoutMs)
        applyPowerSaving()


        // Stable client MAC. When the profile pins one, use it verbatim;
        // otherwise derive it from the server identity. On local-bridge servers a
        // random MAC per reconnect makes the upstream router's ARP entry flap
        // between zombie and live sessions holding the same DHCP IP ("connected
        // but no network" after heavy use).
        applyClientMac()

        // Enable the direct R-UDP stage (UDP-only servers reachable without relay)
        if (config.udpPort > 0) {
            client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_UDP_PORT, config.udpPort.toLong())
            Log.d(TAG, "Direct R-UDP stage enabled (udpPort=${config.udpPort})")
        }

        // UDP-only servers have no TCP port — skip the doomed direct-TCP attempt
        if (config.udpOnly) {
            client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_UDP_ONLY, 1L)
            Log.d(TAG, "UDP-only server: skipping direct TCP attempt")
        }

        currentState = ConnectionState.TLS_HANDSHAKE

        // Connect to server with hub name (includes TLS handshake, protocol handshake, auth, session setup)
        // Use virtualHub from config, default to "VPN" if not set
        val hubName = config.virtualHub.ifEmpty { "VPN" }
        Log.d(TAG, "Connecting with hub: $hubName")
        SoftEtherLog.append("PHASE", "Connecting (TLS/handshake/auth) to hub '$hubName'…")
        if (config.authMethod != vn.unlimit.softether.model.AuthMethod.AUTO) {
            val authTypeInt = when (config.authMethod) {
                vn.unlimit.softether.model.AuthMethod.ANONYMOUS -> 0
                vn.unlimit.softether.model.AuthMethod.PASSWORD -> 1
                vn.unlimit.softether.model.AuthMethod.PLAIN_PASSWORD -> 2
                vn.unlimit.softether.model.AuthMethod.AUTO -> 0
            }
            client.nativeSetAuthType(nativeHandle, authTypeInt)
        }
        startNativeStateMonitor()
        // Protect additional TCP sockets from the tunnel before they connect.
        // They are opened while the VPN is already up, so without this the
        // connect() is routed into the tunnel and hangs until the timeout
        // (the "addlStage=tcp addlLastMs=30000" symptom).
        val protectOk = client.setProtectCallback(service)
        SoftEtherLog.append(
            "SESSION",
            if (protectOk) "Socket protect hook installed (VpnService.protect)"
            else "Socket protect hook NOT installed — additional links may route into the tunnel"
        )
        // SoftEther session options (Windows-client equivalents) from the profile.
        // Compression is always off: the app no longer exposes it (the server-side
        // zlib path never worked reliably and tunnel data is mostly incompressible).
        client.setSessionOptions(
            config.maxConnections,
            config.establishIntervalMs,
            config.useEncrypt,
            false,
            config.qos
        )
        Log.d(TAG, "Session options: conns=${config.maxConnections} interval=${config.establishIntervalMs}ms " +
                "encrypt=${config.useEncrypt} qos=${config.qos}")
        // Build client info (rudpPort will be filled in by native code during RUDP init)
        val clientInfo = buildClientInfo(0)
        val result = try {
            client.nativeConnectWithHub(
                nativeHandle,
                config.serverHost,
                config.serverPort,
                config.username,
                config.password,
                hubName,
                // The native "use_tcp" flag means "TCP-only, no UDP
                // acceleration". UDP acceleration is on only when the profile
                // leaves "Disable UDP Acceleration" unchecked.
                !config.useUdp,
                clientInfo.productName,
                clientInfo.productVersion,
                clientInfo.productBuild,
                clientInfo.osName,
                clientInfo.osVersion,
                clientInfo.osProductId,
                clientInfo.hostName,
                clientInfo.clientIpAddress,
                clientInfo.clientPort,
                clientInfo.serverHostName,
                clientInfo.serverIpAddress,
                clientInfo.serverPort
            )
        } finally {
            stopNativeStateMonitor()
        }

        // Check if cancelled during connection
        if (isCancelled.get()) {
            Log.d(TAG, "Connection was cancelled during connect")
            throw CancellationException("Connection cancelled by user")
        }

        if (result != 0) {
            currentState = ConnectionState.ERROR
            val detail = "Connection failed: ${SoftEtherError.getErrorString(result)} ($result)"
            SoftEtherLog.append("ERROR", detail)
            throw Exception(detail)
        }

        // Connection established at protocol level — run DHCP before announcing CONNECTED
        // Keep internal state as SESSION_SETUP (DHCP phase) until we have an IP
        if (currentState != ConnectionState.CONNECTED) {
            currentState = ConnectionState.SESSION_SETUP
        }
        reconnectAttempts.set(0) // Reset on successful connection
        // Set external handle so send/receive work through ConnectionController
        client.externalHandle = nativeHandle
        Log.d(TAG, "VPN connection established successfully")
        SoftEtherLog.append("PHASE", "Session established (auth OK)")

        // Check if cancelled before establishing VPN interface
        if (isCancelled.get()) {
            Log.d(TAG, "Connection cancelled after successful connect, tearing down")
            throw CancellationException("Connection cancelled by user")
        }

        // Protect VPN socket from routing through TUN (prevents routing loop)
        val socketFd = client.nativeGetSocketFd(nativeHandle)
        if (socketFd >= 0) {
            if (!protectFd(socketFd)) {
                Log.e(TAG, "Failed to protect VPN socket fd=$socketFd")
            } else {
                Log.d(TAG, "VPN socket fd=$socketFd protected from TUN routing")
            }
        } else {
            Log.e(TAG, "Invalid socket fd, cannot protect")
        }

        // Also protect RUDP UDP socket from routing through TUN (prevents RUDP routing loop)
        val rudpFd = client.nativeGetRudpSocketFd(nativeHandle)
        if (rudpFd >= 0) {
            if (!protectFd(rudpFd)) {
                Log.e(TAG, "Failed to protect RUDP UDP socket fd=$rudpFd")
            } else {
                Log.d(TAG, "RUDP UDP socket fd=$rudpFd protected from TUN routing")
            }
        }

        // Protect NAT-T RUDP transport UDP socket (used when the primary
        // connection runs over the NAT-T fallback; -1 otherwise)
        val natTUdpFd = client.nativeGetNatTUdpSocketFd(nativeHandle)
        if (natTUdpFd >= 0) {
            if (!protectFd(natTUdpFd)) {
                Log.e(TAG, "Failed to protect NAT-T UDP socket fd=$natTUdpFd")
            } else {
                Log.d(TAG, "NAT-T UDP socket fd=$natTUdpFd protected from TUN routing")
            }
        }

        // Perform DHCP over the SoftEther tunnel to get IP configuration
        Log.d(TAG, "Starting DHCP over SoftEther tunnel...")
        SoftEtherLog.append("DHCP", "Requesting lease over the tunnel…")
        val dhcpResult = client.doDhcp(nativeHandle)

        // Re-check after DHCP: if the user cancelled while doDhcp() was
        // blocking, stop here — never establish the system VPN interface
        // for a cancelled session (that is what left the VPN key icon up).
        if (isCancelled.get()) {
            Log.d(TAG, "Connection cancelled during DHCP, tearing down")
            throw CancellationException("Connection cancelled by user")
        }

        val dhcpConfig: ConnectionConfig?
        if (dhcpResult != null) {
            Log.d(TAG, "DHCP success: IP=${dhcpResult.assignedIp}/${dhcpResult.prefixLength} " +
                    "GW=${dhcpResult.gateway} DNS=${dhcpResult.dnsServer} DNS2=${dhcpResult.dnsServer2}")
            SoftEtherLog.append(
                "DHCP",
                "Lease: IP=${dhcpResult.assignedIp}/${dhcpResult.prefixLength} " +
                    "GW=${dhcpResult.gateway} DNS=${dhcpResult.dnsServer} DNS2=${dhcpResult.dnsServer2}"
            )
            assignedLocalIp = dhcpResult.assignedIp
            // Update config with DHCP-assigned values
            dhcpConfig = config.copy(
                localAddress = dhcpResult.assignedIp,
                prefixLength = dhcpResult.prefixLength,
                dnsServer = if (dhcpResult.dnsServer != "0.0.0.0") dhcpResult.dnsServer else config.dnsServer,
                secondaryDnsServer = if (dhcpResult.dnsServer2 != "0.0.0.0") dhcpResult.dnsServer2 else config.secondaryDnsServer
            )
            vpnInterface = service.establishVpnInterface(dhcpConfig)
                ?: throw Exception("Failed to establish VPN interface")
        } else {
            Log.w(TAG, "DHCP failed, falling back to hardcoded IP config")
            SoftEtherLog.append("DHCP", "No lease received — falling back to static config ${config.localAddress}")
            assignedLocalIp = config.localAddress
            vpnInterface = service.establishVpnInterface(config)
                ?: throw Exception("Failed to establish VPN interface")
        }

        // Now that we have an IP and VPN interface, transition to CONNECTED
        currentState = ConnectionState.CONNECTED
        resetTrafficPublishing(publishSnapshot = true)
        SoftEtherLog.append("PHASE", "Connected — data path started (assigned IP $assignedLocalIp)")
        // Log the negotiated session state (connection count, TLS, server flags).
        logSessionInfo(force = true)

        // Start data forwarding loops
        startDataForwarding()

        // Start statistics logging
        startStatisticsLogging()
    }

    /**
     * Attempt to reconnect using stored credentials.
     * Fully disconnects, waits, then performs a fresh connect() with full lifecycle.
     */
    suspend fun reconnect(): Boolean {
        if (isReconnecting.getAndSet(true)) {
            Log.w(TAG, "Reconnection already in progress")
            return false
        }

        return try {
            Log.d(TAG, "Attempting to reconnect...")
            disconnect()
            delay(RECONNECT_DELAY_MS)
            // Reset isCancelled so connect() doesn't bail out immediately
            isCancelled.set(false)
            connect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed", e)
            false
        } finally {
            isReconnecting.set(false)
        }
    }

    /**
     * Disconnect VPN gracefully
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting VPN")
        // Capture the end-of-session summary before the native handle is freed.
        logSessionEnd("Disconnected")
        isCancelled.set(true)
        client.externalHandle = 0

        // Use mutex to prevent race with connect(); tryLock keeps disconnect()
        // non-blocking so it stays responsive on the calling thread.
        if (connectionMutex.tryLock()) {
            try {
                // Update state
                if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
                    currentState = ConnectionState.DISCONNECTING
                }

                // Disconnect native connection (this will interrupt any blocking operations).
                // If a connect flow is in flight, DEFER native teardown to it: the
                // connect coroutine is blocked inside nativeConnectWithHub and owns
                // the handle. Freeing here would either deadlock on connect_mutex or
                // free the connection out from under the connect thread (zombie
                // session + poisoned state for every later connect attempt).
                if (isConnectInFlight()) {
                    Log.w(TAG, "Connect in flight - deferring native teardown to connect flow")
                } else if (nativeHandle != 0L) {
                    teardownNativeConnection()
                }
            } finally {
                connectionMutex.unlock()
            }
        } else {
            // connect() holds the mutex; native teardown stays owned by the
            // connect flow via its connectInFlight guarantee.
            Log.w(TAG, "Connect in progress, skipping mutex-protected native teardown")
        }

        // Stop TunTerminal first to avoid reading from closed interface
        try {
            tunTerminal?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TunTerminal", e)
        }
        tunTerminal = null
        
        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        // Don't call scope.cancel() — it permanently kills the scope, making reconnect impossible.
        // The isCancelled flag (set above) causes all data forwarding loops, keepalive,
        // and statistics logging to exit naturally via their while-loop conditions.

        currentState = ConnectionState.DISCONNECTED
        Log.d(TAG, "VPN disconnected. Stats: sent=${bytesSent.get()} bytes (${packetsSent.get()} pkts), " +
                "received=${bytesReceived.get()} bytes (${packetsReceived.get()} pkts)")
    }

    /**
     * Writes the end-of-session summary to the app log: the session totals plus
     * the per-link traffic split. A connect line already exists, so a matching
     * end line is what makes it possible to read how long a tunnel lived, how
     * much it carried and how the load spread across its TCP links — without a
     * logcat and without the periodic per-link lines (which are off by default).
     * Safe to call more than once; the second call finds no handle and returns.
     */
    private fun logSessionEnd(reason: String) {
        if (nativeHandle == 0L) return
        SoftEtherLog.append(
            "PHASE",
            "$reason — session ended (tx=${packetsSent.get()}p/${bytesSent.get()}B " +
                "rx=${packetsReceived.get()}p/${bytesReceived.get()}B)"
        )
        logLinkStats(force = true)
    }

    /**
     * Quickly destroy native resources without graceful disconnect (non-blocking).
     * Sets isCancelled, stops TunTerminal, closes fd, and frees the native handle.
     * Does NOT send state change callbacks — caller is responsible for state updates.
     */
    fun destroyResources() {
        Log.d(TAG, "Destroying resources (fast cleanup)")
        // Teardown on the service path goes through here, not disconnect(), so
        // the end-of-session summary has to be captured before the handle dies —
        // otherwise the log shows a session that starts and never ends.
        logSessionEnd("Session stopped")
        isCancelled.set(true)
        client.externalHandle = 0

        // Stop TunTerminal first to avoid reading from closed interface
        try {
            tunTerminal?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TunTerminal", e)
        }
        tunTerminal = null

        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        // Force-close sockets to interrupt any blocking nativeConnectWithHub
        // that may be holding connect_mutex.  This makes softether_connect_with_hub
        // return with an error so softether_destroy can proceed without deadlock.
        // If a connect flow is in flight, DEFER native teardown entirely: a
        // force-close + 200ms sleep is not enough (the connect retries on new
        // sockets / NAT-T), and destroying here frees the connection under the
        // connect thread. The connect flow tears the handle down itself as soon
        // as nativeConnectWithHub returns.
        if (isConnectInFlight()) {
            Log.w(TAG, "Connect in flight - deferring native destroy to connect flow")
            return
        }

        val handle = nativeHandle
        nativeHandle = 0
        protectedFds.clear()
        if (handle != 0L) {
            try {
                client.nativeForceCloseSocket(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error force-closing socket", e)
            }
            // Bounded wait for the connect flow to finish owning the native
            // handle instead of a fixed Thread.sleep heuristic. If a connect
            // flow was in flight it defers teardown entirely (see connectInFlight
            // guard above), so in the common case this returns immediately; on a
            // slow TLS read it still bounds the wait before nativeDestroy.
            if (!nativeTeardownGate.await(NATIVE_TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for connect flow to release native handle before destroy")
            }

            try {
                client.nativeDestroy(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying native handle", e)
            }
        }
    }

    /**
     * Get current connection state
     */
    fun getState(): ConnectionState = currentState

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean = currentState == ConnectionState.CONNECTED

    /**
     * Get connection statistics
     */
    fun getStatistics(): ConnectionStatistics {
        return ConnectionStatistics(
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            packetsSent = packetsSent.get(),
            packetsReceived = packetsReceived.get(),
            reconnectAttempts = reconnectAttempts.get()
        )
    }

    /**
     * Start data forwarding between TUN interface and SoftEther connection
     * This is the core data tunnel implementation
     */
    private fun startDataForwarding() {
        val tunInterface = vpnInterface
            ?: throw IllegalStateException("VPN interface not established")

        // Store reference to tunTerminal so we can stop it cleanly
        this.tunTerminal = TunTerminal(tunInterface, scope)
        val terminal = this.tunTerminal!!

        // The data path starts fresh, so the "last receive" stamp of the dead
        // session must not leak into this one: the watchdog would log a long
        // silence right after a healthy reconnect.
        lastReceiveActivityMs.set(System.currentTimeMillis())

        // Start TUN interface reading.
        // Phase 13E: packets are sent directly from the TUN read loop as
        // (buffer, offset, length) slices — no per-packet copy, no queue,
        // no dedicated send coroutine. A slow VPN link now applies natural
        // backpressure to the TUN read instead of growing a queue. Native
        // softether_send serializes concurrent writers via its write mutex,
        // so sending here alongside keepalives is safe.
        terminal.start(
            onPacket = { buffer, offset, length ->
                // Packet from TUN (local system) -> send to VPN
                try {
                    val result = client.send(buffer, offset, length)
                    if (result > 0) {
                        bytesSent.addAndGet(result.toLong())
                        packetsSent.incrementAndGet()
                    } else if (result < 0 && isConnected() && !isCancelled.get()) {
                        Log.w(TAG, "Send failed: $result")
                        SoftEtherLog.append("ERROR", "Send to tunnel failed: native=$result")
                        scope.launch { attemptReconnect() }
                    }
                } catch (e: Exception) {
                    if (!isCancelled.get()) {
                        Log.e(TAG, "Send error", e)
                        SoftEtherLog.append("ERROR", "Send exception: ${e.message}")
                        if (isConnected()) {
                            scope.launch { attemptReconnect() }
                        }
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "TUN interface error", error)
                if (!isCancelled.get()) {
                    // Don't call onError() here — it triggers stopVpn() in VpnService
                    // which destroys everything. Instead, let attemptReconnect handle
                    // the full lifecycle (tear down + reconnect + new TUN).
                    scope.launch { attemptReconnect() }
                }
            }
        )

        // Receive loop: VPN -> TUN
        scope.launch {
            val receiveBuffer = ByteArray(65535)
            val batchLengths = IntArray(RX_BATCH_MAX_PACKETS)
            var receiveCount = 0
            while (isConnected() && !isCancelled.get()) {
                try {
                    // Phase 13D: drain multiple frames per JNI crossing
                    val total = client.receiveBatch(receiveBuffer, batchLengths)
                    when {
                        total > 0 -> {
                            // Valid data received — write frames back-to-back
                            var offset = 0
                            var written = 0
                            for (len in batchLengths) {
                                if (len == 0) break
                                val writeResult = terminal.write(receiveBuffer, offset, len)
                                if (writeResult > 0) written++
                                offset += len
                            }
                            if (written > 0) {
                                bytesReceived.addAndGet(total.toLong())
                                packetsReceived.addAndGet(written.toLong())
                            }
                            // Inbound data also proves the link is alive. Without
                            // this the keepalive watchdog only sees "activity" on
                            // idle ticks (total == 0) and falsely declares the
                            // connection dead while traffic is flowing, tearing
                            // down a healthy session every ~33 s.
                            lastReceiveActivityMs.set(System.currentTimeMillis())
                            keepAlive.recordReceived()
                            // Periodically protect additional sockets (multi-connection)
                            receiveCount++
                            if (receiveCount % 50 == 0) {
                                protectAdditionalSockets()
                            }
                        }
                        total == 0 -> {
                            // Keepalive or no data. Phase 13E: no Kotlin-side
                            // delay — native receive blocks up to 100ms in
                            // poll() when idle, so this loop only wakes ~10x/s
                            // at idle and instantly when data arrives.
                            lastReceiveActivityMs.set(System.currentTimeMillis())
                            keepAlive.recordReceived()
                        }
                        else -> {
                            // Error receiving. When a teardown is already under
                            // way (user disconnect, protocol switch, reconnect)
                            // the native layer reports the closed socket as an
                            // error too; that is expected, not a failure, so it
                            // must not be written as an ERROR line — it made a
                            // clean disconnect look like a crash.
                            val detail = client.getLastLinkError()
                            Log.e(TAG, "Receive error: $total ($detail)")
                            if (isConnected() && !isCancelled.get()) {
                                SoftEtherLog.append(
                                    "ERROR",
                                    "Receive loop error: native=$total" +
                                        if (detail != null) " ($detail)" else ""
                                )
                                scope.launch { attemptReconnect() }
                            } else {
                                SoftEtherLog.append(
                                    "DATA",
                                    "Receive loop stopped (session closing): native=$total" +
                                        if (detail != null) " ($detail)" else ""
                                )
                            }
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Throwable) {
                    // Throwable, not Exception: a JNI-layer Error (e.g. a pending
                    // exception surfacing from a native upcall) would otherwise
                    // escape this coroutine and kill the process instead of
                    // triggering a reconnect.
                    Log.e(TAG, "Receive loop error", e)
                    SoftEtherLog.append("ERROR", "Receive loop exception: ${e.message}")
                    if (isConnected() && !isCancelled.get()) {
                        scope.launch { attemptReconnect() }
                    }
                    break
                }
            }
        }

        // Start keepalive
        startKeepalive()
    }

    /**
     * Attempt automatic reconnection with full lifecycle:
     * tear down old TUN/native → reconnect → DHCP → establish VPN interface → restart data forwarding
     *
     * Also called by the service's VPN-slot watchdog: rebuilding is what takes
     * the default route back from another VPN that established after us.
     */
    suspend fun attemptReconnect() {
        if (!config.reconnectionEnabled) {
            // The profile turned automatic reconnection off: report the drop
            // instead of rebuilding the tunnel behind the user's back.
            SoftEtherLog.append("RECONNECT", "Auto reconnection is disabled — giving up")
            currentState = ConnectionState.ERROR
            onError("Connection lost — auto reconnection is disabled")
            disconnect()
            return
        }
        if (isReconnecting.getAndSet(true)) {
            Log.w(TAG, "Reconnection already in progress")
            return
        }

        try {
            // This flow owns the native lifecycle from here on, so raise
            // connectInFlight before the first handle exists: the flow blocks
            // inside nativeConnectWithHub, and without the flag
            // disconnect()/destroyResources() see !isConnectInFlight() +
            // nativeHandle != 0 and destroy the connection under the blocked
            // reconnect thread (use-after-free crash inside
            // softether_protocol_login / ssl_write). Cleared in the finally below.
            isCancelled.set(false)
            connectInFlight.set(true)
            nativeTeardownGate = CountDownLatch(1)

            // Retry the whole rebuild instead of giving up after the first
            // failure. The first attempt after a Wi-Fi <-> mobile switch almost
            // always fails, because it starts before the new default network is
            // usable — and with only one attempt the tunnel stayed down while the
            // service kept running, so the app refused every later connect.
            while (true) {
                if (isCancelled.get()) return

                // Drop everything the dead session owned, before anything else.
                // Doing it here (and not just before the connect below) matters
                // when the device is offline: while parked, an open TUN with the
                // default route pointed into a dead tunnel would blackhole every
                // app's traffic for as long as the pause lasts.
                tearDownForReconnect()

                // The tunnel is not gone from the user's point of view — it is
                // waiting to come back — so the UI stays in "Connecting" and the
                // service keeps running. Leaving the state at CONNECTED would keep
                // the receive/keepalive/statistics loops spinning on a session
                // that no longer exists.
                if (currentState != ConnectionState.CONNECTING) {
                    currentState = ConnectionState.CONNECTING
                }

                // No network means there is nothing to try yet. Waiting here,
                // before the attempt is counted, keeps an offline device from
                // burning the whole budget on attempts that cannot succeed — that
                // is what used to stop the service and leave the user without a
                // tunnel until they reconnected by hand.
                waitForNetwork()
                if (isCancelled.get()) return

                val maxAttempts = config.reconnectionCount.coerceAtLeast(1)
                val attempt = reconnectAttempts.incrementAndGet()
                if (attempt > maxAttempts) {
                    Log.e(TAG, "Max reconnection attempts reached")
                    SoftEtherLog.append("ERROR", "Max reconnection attempts reached — giving up")
                    // Surface as ERROR (not silent DISCONNECTED) so the UI shows
                    // the disconnect-by-error notification and STATE_ERROR
                    // broadcast, and the service stops itself instead of lingering.
                    currentState = ConnectionState.ERROR
                    onError("Connection lost - max reconnection attempts reached")
                    disconnect()
                    return
                }

                Log.w(TAG, "Attempting automatic reconnection ($attempt/$maxAttempts)")
                SoftEtherLog.append("RECONNECT", "Attempt $attempt/$maxAttempts")

                // Wait before reconnecting, so the new network can settle. The
                // profile's interval (seconds) decides how long.
                delay(config.reconnectionInterval.coerceAtLeast(1) * 1000L)

                if (isCancelled.get()) {
                    return
                }

                try {
                    rebuildTunnel()
                } catch (e: Throwable) {
                    // A user disconnect (or the service tearing us down) is not a
                    // failed attempt: stop retrying.
                    if (isCancelled.get()) return
                    Log.e(TAG, "Reconnection attempt $attempt failed", e)
                    SoftEtherLog.append(
                        "ERROR",
                        "Reconnect attempt $attempt/$maxAttempts failed: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                    continue
                }

                reconnectAttempts.set(0)
                Log.d(TAG, "Reconnection successful — data forwarding restarted")
                return
            }
        } finally {
            nativeTeardownGate.countDown()
            connectInFlight.set(false)
            isReconnecting.set(false)
        }
    }

    /**
     * Releases everything the dead session owned — the TUN interface and the
     * native handle — before a reconnect attempt builds a new one.
     */
    private fun tearDownForReconnect() {
        // Stop TunTerminal first, so nothing keeps reading from the stale fd.
        try {
            tunTerminal?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TunTerminal during reconnect", e)
        }
        tunTerminal = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface during reconnect", e)
        }
        vpnInterface = null

        val handle = nativeHandle
        nativeHandle = 0
        protectedFds.clear()
        if (handle != 0L) {
            try {
                client.nativeDisconnect(handle)
                client.nativeDestroy(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting old native handle", e)
            }
        }
    }

    /**
     * Parks the caller until the device has an underlying network again.
     *
     * With no network at all there is nothing to retry, so the reconnect budget is
     * not spent and the service is not stopped: the tunnel simply waits. The wait
     * is event-driven (the service's network callback wakes it) with a long
     * fallback check, so a parked tunnel costs almost no battery — nothing is
     * polled, no native connection exists, and no wake lock is held.
     */
    private suspend fun waitForNetwork() {
        if (hasUnderlyingNetwork()) return

        SoftEtherLog.append("RECONNECT", "No network — pausing until it comes back")
        onWaitingForNetwork(true)
        isParked.set(true)
        // Let the statistics loop notice the pause right away instead of waiting
        // for its next (up to 15 s) tick.
        snapshotWakeUp.trySend(Unit)
        try {
            while (!isCancelled.get()) {
                // Either the callback fires, or the fallback timeout expires and we
                // re-check the network ourselves.
                withTimeoutOrNull(NETWORK_WAIT_FALLBACK_MS) { networkWakeup.receive() }
                if (hasUnderlyingNetwork()) break
            }
        } finally {
            isParked.set(false)
            onWaitingForNetwork(false)
        }
        if (isCancelled.get()) return
        SoftEtherLog.append("RECONNECT", "Network is back — reconnecting")
    }

    /**
     * True when the platform has a usable underlying (non-VPN) network.
     *
     * The callback-driven [isNetworkAvailable] alone is not enough: while our own
     * TUN is up it *is* the default network, so the callbacks keep reporting
     * "available" even after Wi-Fi and mobile data are both gone. A reconnect
     * then ran straight into a dead network instead of waiting for the new one.
     */
    private fun hasUnderlyingNetwork(): Boolean {
        val cm = try {
            service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null
        } ?: return isNetworkAvailable

        return cm.allNetworks.any { network ->
            val caps = try {
                cm.getNetworkCapabilities(network)
            } catch (e: Exception) {
                null
            }
            caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /**
     * Builds a complete session for a reconnect attempt: a fresh native
     * connection (TLS + protocol + auth + session), the DHCP lease, the TUN
     * interface, and finally the data path. Everything it creates is published
     * to this controller's fields, so a failure halfway through is cleaned up by
     * the teardown at the top of the next attempt.
     */
    private suspend fun rebuildTunnel() {
        // Create new native connection
        nativeHandle = client.nativeCreate()
        if (nativeHandle == 0L) {
            throw Exception("Failed to create native connection for reconnect")
        }
        // Publish the handle so the setters below (timeout, session
        // options) target this connection instead of handle 0.
        client.externalHandle = nativeHandle
        val reconnectProtectOk = client.setProtectCallback(service)
        SoftEtherLog.append(
            "SESSION",
            if (reconnectProtectOk) "Socket protect hook installed (VpnService.protect)"
            else "Socket protect hook NOT installed — additional links may route into the tunnel"
        )

        client.setTimeout(config.connectTimeoutMs)

        // Re-apply the client MAC to the fresh reconnect handle.
        applyClientMac()
        applyPowerSaving()


        // Set auth type
        val hubName = config.virtualHub.ifEmpty { "VPN" }
        if (config.authMethod != vn.unlimit.softether.model.AuthMethod.AUTO) {
            val authTypeInt = when (config.authMethod) {
                vn.unlimit.softether.model.AuthMethod.ANONYMOUS -> 0
                vn.unlimit.softether.model.AuthMethod.PASSWORD -> 1
                vn.unlimit.softether.model.AuthMethod.PLAIN_PASSWORD -> 2
                vn.unlimit.softether.model.AuthMethod.AUTO -> 0
            }
            client.nativeSetAuthType(nativeHandle, authTypeInt)
        }

        // Re-apply SoftEther session options to the fresh reconnect handle.
        client.setSessionOptions(
            config.maxConnections,
            config.establishIntervalMs,
            config.useEncrypt,
            false,
            config.qos
        )

        // Connect to server (TLS + protocol + auth + session)
        startNativeStateMonitor()
        val reconnectClientInfo = buildClientInfo(0)
        val result = try {
            client.nativeConnectWithHub(
                nativeHandle,
                config.serverHost,
                config.serverPort,
                config.username,
                config.password,
                hubName,
                // See performConnectInner: use_tcp means "TCP-only".
                !config.useUdp,
                reconnectClientInfo.productName, reconnectClientInfo.productVersion, reconnectClientInfo.productBuild,
                reconnectClientInfo.osName, reconnectClientInfo.osVersion, reconnectClientInfo.osProductId,
                reconnectClientInfo.hostName, reconnectClientInfo.clientIpAddress, reconnectClientInfo.clientPort,
                reconnectClientInfo.serverHostName, reconnectClientInfo.serverIpAddress, reconnectClientInfo.serverPort
            )
        } finally {
            stopNativeStateMonitor()
        }

        if (result != 0) {
            throw Exception("Reconnection failed with error code: $result")
        }

        if (isCancelled.get()) {
            val handle = nativeHandle
            nativeHandle = 0
            client.nativeDisconnect(handle)
            client.nativeDestroy(handle)
            // Not a failure: the user (or the service) tore the session down
            // while this attempt was connecting. The caller checks the cancelled
            // flag and stops retrying instead of reporting a failed attempt.
            throw CancellationException("Reconnect cancelled")
        }

        // Protect VPN socket from routing through TUN
        val socketFd = client.nativeGetSocketFd(nativeHandle)
        if (socketFd >= 0) {
            if (!protectFd(socketFd)) {
                Log.e(TAG, "Failed to protect VPN socket fd=$socketFd during reconnect")
            } else {
                Log.d(TAG, "VPN socket fd=$socketFd protected during reconnect")
            }
        }

        // Protect RUDP UDP socket
        val rudpFd = client.nativeGetRudpSocketFd(nativeHandle)
        if (rudpFd >= 0) {
            if (!protectFd(rudpFd)) {
                Log.e(TAG, "Failed to protect RUDP UDP socket fd=$rudpFd during reconnect")
            } else {
                Log.d(TAG, "RUDP UDP socket fd=$rudpFd protected during reconnect")
            }
        }

        // Protect NAT-T RUDP transport UDP socket during reconnect
        val natTUdpFd = client.nativeGetNatTUdpSocketFd(nativeHandle)
        if (natTUdpFd >= 0) {
            if (!protectFd(natTUdpFd)) {
                Log.e(TAG, "Failed to protect NAT-T UDP socket fd=$natTUdpFd during reconnect")
            } else {
                Log.d(TAG, "NAT-T UDP socket fd=$natTUdpFd protected during reconnect")
            }
        }

        client.externalHandle = nativeHandle

        // Perform DHCP over the new tunnel
        Log.d(TAG, "Starting DHCP over reconnected tunnel...")
        val dhcpResult = client.doDhcp(nativeHandle)
        if (dhcpResult != null) {
            Log.d(TAG, "DHCP success on reconnect: IP=${dhcpResult.assignedIp}/${dhcpResult.prefixLength}")
            assignedLocalIp = dhcpResult.assignedIp
            val dhcpConfig = config.copy(
                localAddress = dhcpResult.assignedIp,
                prefixLength = dhcpResult.prefixLength,
                dnsServer = if (dhcpResult.dnsServer != "0.0.0.0") dhcpResult.dnsServer else config.dnsServer,
                secondaryDnsServer = if (dhcpResult.dnsServer2 != "0.0.0.0") dhcpResult.dnsServer2 else config.secondaryDnsServer
            )
            vpnInterface = service.establishVpnInterface(dhcpConfig)
                ?: throw Exception("Failed to establish VPN interface during reconnect")
        } else {
            Log.w(TAG, "DHCP failed on reconnect, falling back to hardcoded config")
            assignedLocalIp = config.localAddress
            vpnInterface = service.establishVpnInterface(config)
                ?: throw Exception("Failed to establish VPN interface during reconnect")
        }

        // Protect the sockets again now that the VPN interface exists. The
        // protect call made while connecting returned false: with the previous
        // interface already torn down the app owns no VPN session yet, so
        // VpnService.protect() has nothing to bind the socket to. An unprotected
        // socket falls into the VPN routing table and dies as soon as a packet
        // of its own lands in the tunnel — which is what made a freshly
        // reconnected session drop again seconds later, and then again on every
        // following rebuild.
        protectAdditionalSockets()

        // Transition to CONNECTED and restart data forwarding
        currentState = ConnectionState.CONNECTED
        resetTrafficPublishing(publishSnapshot = true)
        startDataForwarding()
        startStatisticsLogging()
    }

    /**
     * Start keepalive monitoring for the session that just came up. The
     * watchdog of the previous session is cancelled first: it survives a
     * reconnect (its loop only notices a disconnect while the state is
     * CONNECTING, which the fast rebuild can outrun) and then counts its own
     * stale "last keepalive received" up to the 30 s timeout, tearing down a
     * perfectly healthy tunnel.
     */
    private fun startKeepalive() {
        keepAliveWatchdogJob?.cancel()
        keepAlive.setInterval(config.keepAliveIntervalMs.toLong())
        keepAlive.setTimeout(30000L) // 30 second timeout
        keepAlive.start()

        keepAliveWatchdogJob = scope.launch {
            var lastWatchdogLog = 0L
            while (isConnected() && !isCancelled.get()) {
                try {
                    delay(watchdogIntervalMs()) // Check periodically (stretched by energy saving and screen state)

                    if (keepAlive.shouldSendKeepAlive()) {
                        // Keepalive is handled in native layer
                        keepAlive.recordSent()
                    }

                    // Diagnose a silent data path: the native receive loop returns
                    // at least every ~100 ms while it runs, so a gap means either
                    // no traffic or a blocked native read. Skipped while logging
                    // is off — the JNI stats call exists only for this line.
                    val silentMs = System.currentTimeMillis() - lastReceiveActivityMs.get()
                    if (SoftEtherLog.enabled &&
                        silentMs >= 5000 && System.currentTimeMillis() - lastWatchdogLog >= 5000
                    ) {
                        lastWatchdogLog = System.currentTimeMillis()
                        val stats = client.getStats()
                        val activeConns =
                            if (nativeHandle != 0L) client.nativeGetNumConnections(nativeHandle) else -1
                        SoftEtherLog.append(
                            "DATA",
                            "No receive for ${silentMs / 1000}s " +
                                "(tx=${stats?.txPackets ?: -1L}p rx=${stats?.rxPackets ?: -1L}p " +
                                "rudp=${stats?.rudpRxPackets ?: -1L}p susp=${stats?.rudpDataSuspended} " +
                                "conns=$activeConns)"
                        )
                    }

                    if (keepAlive.isConnectionDead()) {
                        Log.e(TAG, "Connection appears dead (keepalive timeout)")
                        SoftEtherLog.append(
                            "ERROR",
                            "Keepalive timeout: no inbound data for ${silentMs / 1000}s — reconnecting"
                        )
                        scope.launch { attemptReconnect() }
                        break
                    }
                } catch (e: CancellationException) {
                    break
                }
            }
            // Only the watchdog that is still the current one may stop the
            // manager: a cancelled predecessor must not flip the flag of the
            // session that replaced it.
            if (keepAliveWatchdogJob === coroutineContext[Job]) {
                keepAlive.stop()
                keepAliveWatchdogJob = null
            }
        }
    }

    /**
     * Keepalive watchdog cadence. Stretched by the energy-saving switch, and
     * stretched further while the screen is off — the timeout it enforces is
     * 30 s, so a slower check is still well inside it.
     */
    private fun watchdogIntervalMs(): Long {
        if (!service.isScreenInteractive) {
            return WATCHDOG_INTERVAL_SCREEN_OFF_MS
        }
        return 1000L * powerSaveFactor()
    }

    /**
     * Writes the negotiated session state to the app log: how many TCP
     * connections are actually up, what the server allowed, and whether the data
     * channel runs over TLS. Called once after the tunnel is up, then again
     * whenever the connection count changes, so the profile's SoftEther options
     * are verifiable on-device.
     */
    private fun logSessionInfo(force: Boolean = false) {
        if (nativeHandle == 0L) return
        val info = client.getSessionInfo() ?: return
        if (!force &&
            info.numConnections == lastLoggedConnectionCount &&
            info.additionalAttempts == lastLoggedAdditionalAttempts
        ) return
        lastLoggedConnectionCount = info.numConnections
        lastLoggedAdditionalAttempts = info.additionalAttempts
        val additional = buildString {
            append("addlAttempts=${info.additionalAttempts} ")
            append("addlFailed=${info.additionalFailedCount} ")
            append("addlStage=${info.additionalLastStageName} ")
            append("addlLastMs=${info.additionalLastAttemptMs} ")
            // Visibility for the socket-protect hook. It was silently missing in
            // an earlier build, and the only symptom was extra links that the
            // server never counted, so it is logged on every snapshot.
            append("protect=${if (info.protectAvailable) info.protectStateName else "MISSING"}")
            if (info.additionalFailedCount > 0) {
                append(" addlFailStage=${info.additionalLastFailStageName}")
            }
            if (info.additionalLastError != 0) {
                append(" addlServerError=${info.additionalLastError}")
            }
        }
        SoftEtherLog.append(
            "SESSION",
            "Active: tcpConnections=${info.numConnections} " +
                "(requested=${info.requestedMaxConnections} " +
                "serverMax=${info.serverMaxConnections}) " +
                "dataChannel=${if (info.useSslData) "TLS" else "raw-TCP"} " +
                "sslSessionEncrypt=${info.serverUseEncrypt} " +
                "compress=${info.serverUseCompress} " +
                "natT=${info.usingNatT} " +
                // The profile's UDP-acceleration and QoS choices, repeated here so
                // the negotiated snapshot also shows which options were requested
                // (the server does not echo either flag back).
                "udpAccel=${if (config.useUdp) "on" else "off"} " +
                "qos=${if (config.qos) "on" else "off"} " +
                additional
        )
        // Native specifics of the last finished additional-connect attempt.
        // Only logged when an attempt has failed, so a healthy session stays quiet.
        if (info.additionalFailedCount > 0) {
            client.getSessionDetail()?.let { SoftEtherLog.append("SESSION", "Addl detail: $it") }
        }
    }

    /**
     * Writes the per-link traffic split to the app log, throttled to
     * LINK_STATS_LOG_INTERVAL_MS and only when more than one TCP link is up.
     * The session-wide counters cannot tell whether the extra connections carry
     * any traffic; this line splits the bytes by the socket that moved them, so
     * the distribution across the profile's TCP connections is visible without
     * a logcat.
     *
     * The periodic call is off unless [SoftEtherLog.perLinkStatsEnabled] is set:
     * during an idle session the line is identical every 5 s and buries the rest
     * of the log. The end-of-session call passes force = true so the final split
     * is always recorded — that is the line worth reading, and it is the only
     * one left when the toggle is off.
     */
    private fun logLinkStats(force: Boolean = false) {
        if (nativeHandle == 0L) return
        if (!force && !SoftEtherLog.perLinkStatsEnabled) return
        val info = client.getSessionInfo() ?: return
        if (info.numConnections <= 1) return
        val now = System.currentTimeMillis()
        if (!force && now - lastLinkStatsLogAtMs < LINK_STATS_LOG_INTERVAL_MS) return
        lastLinkStatsLogAtMs = now
        val (tx, rx) = client.getLinkStats() ?: return
        val parts = ArrayList<String>(tx.size)
        for (i in tx.indices) {
            // Slots that never moved a byte stay out of the line (an active link
            // with zero traffic is indistinguishable from a dead one here).
            if (i > 0 && tx[i] == 0L && rx[i] == 0L) continue
            val name = if (i == 0) "primary" else "addl$i"
            parts += "$name tx=${formatBytes(tx[i])} rx=${formatBytes(rx[i])}"
        }
        if (parts.isEmpty()) return
        SoftEtherLog.append("DATA", "Link split: " + parts.joinToString(" | "))
    }

    /** Compact byte formatter for the app log (B / K / M). */
    private fun formatBytes(v: Long): String = when {
        v >= 1_048_576L -> String.format(java.util.Locale.US, "%.1fM", v / 1_048_576.0)
        v >= 1024L -> String.format(java.util.Locale.US, "%.1fK", v / 1024.0)
        else -> "${v}B"
    }

    /**
     * Start periodic statistics logging
     */
    private fun startStatisticsLogging() {
        // Only one loop may run: every reconnect used to start another one, and
        // the older ones never exited (the tunnel is not DISCONNECTED during a
        // reconnect), so the number of per-second timers grew with each switch.
        statsJob?.cancel()
        statsJob = scope.launch {
            while (!isCancelled.get() &&
                !isParked.get() &&
                currentState != ConnectionState.DISCONNECTED &&
                currentState != ConnectionState.ERROR
            ) {
                try {
                    // Sleep until the next tick, or wake up at once when the
                    // screen comes back — the screen-off interval is long and must
                    // not delay the first update the user sees after unlocking.
                    withTimeoutOrNull(statsIntervalMs()) {
                        snapshotWakeUp.receive()
                    }
                    if (isConnected()) {
                        // Publish the speed snapshot ONLY here, once per tick.
                        // It used to also be published from the hot send/receive
                        // paths, whose races with this timer made the interval
                        // between snapshots jump around — and since the speed is
                        // diff/interval, the notification's up/down figures
                        // flickered (and looked like per-link speeds). The byte
                        // counters already sum every TCP link, so a single
                        // snapshot per tick is both correct and stable.
                        //
                        // With the screen off nobody can see the app or the
                        // notification, so nothing is published: the counters are
                        // only advanced, which keeps the first snapshot after
                        // unlocking free of the whole idle gap. The screen-on
                        // event wakes this loop immediately (snapshotWakeUp), so
                        // the figures are current the moment the screen returns.
                        if (service.isScreenInteractive) {
                            publishTrafficSnapshot()
                        } else {
                            resetTrafficPublishing(publishSnapshot = false)
                            continue
                        }
                        if (!SoftEtherLog.enabled) continue
                        // Surface additional connections coming up or dropping.
                        logSessionInfo()
                        // Periodically show how the load spreads across the links.
                        logLinkStats()
                        Log.d(TAG, "Stats: sent=${bytesSent.get()} bytes (${packetsSent.get()} pkts), " +
                                "received=${bytesReceived.get()} bytes (${packetsReceived.get()} pkts)")
                        // Phase 13G: surface native health counters alongside the
                        // traffic snapshot (same cadence, debug-level only).
                        client.getStats()?.let { st ->
                            Log.d(TAG,
                                "stats: tx=${st.txPackets}p/${st.txBytes}B rx=${st.rxPackets}p/${st.rxBytes}B " +
                                "skipped=${st.rxSkippedBlocks} rudp(rx=${st.rudpRxPackets} ovf=${st.rudpOverflowCount} " +
                                "gaps=${st.rudpTickGaps} susp=${st.rudpDataSuspended})")
                        }
                    }
                } catch (e: CancellationException) {
                    break
                }
            }
        }
    }

    /**
     * Snapshot cadence. The energy-saving switch stretches it; the screen state
     * stretches it much further, since with the screen off nothing is visible.
     */
    private fun statsIntervalMs(): Long {
        if (!service.isScreenInteractive) {
            return STATS_INTERVAL_MS * SCREEN_OFF_CYCLE_FACTOR
        }
        return STATS_INTERVAL_MS * powerSaveFactor()
    }

    private fun resetTrafficPublishing(publishSnapshot: Boolean) {
        lastPublishedSentBytes = bytesSent.get()
        lastPublishedReceivedBytes = bytesReceived.get()
        lastPublishedAtMs = System.currentTimeMillis()
        if (publishSnapshot) {
            onTrafficUpdate(
                SoftEtherTrafficSnapshot(
                    inBytes = lastPublishedReceivedBytes,
                    outBytes = lastPublishedSentBytes,
                    diffInBytes = 0L,
                    diffOutBytes = 0L,
                    packetsIn = packetsReceived.get(),
                    packetsOut = packetsSent.get(),
                    intervalMs = STATS_INTERVAL_MS,
                    timestampMs = lastPublishedAtMs
                )
            )
        }
    }

    private fun publishTrafficSnapshot() {
        val now = System.currentTimeMillis()
        val currentSentBytes = bytesSent.get()
        val currentReceivedBytes = bytesReceived.get()
        val interval = (now - lastPublishedAtMs).coerceAtLeast(1L)
        val snapshot = SoftEtherTrafficSnapshot(
            inBytes = currentReceivedBytes,
            outBytes = currentSentBytes,
            diffInBytes = (currentReceivedBytes - lastPublishedReceivedBytes).coerceAtLeast(0L),
            diffOutBytes = (currentSentBytes - lastPublishedSentBytes).coerceAtLeast(0L),
            packetsIn = packetsReceived.get(),
            packetsOut = packetsSent.get(),
            intervalMs = interval,
            timestampMs = now
        )
        lastPublishedSentBytes = currentSentBytes
        lastPublishedReceivedBytes = currentReceivedBytes
        lastPublishedAtMs = now
        onTrafficUpdate(snapshot)
    }

    private fun startNativeStateMonitor() {
        stateMonitorJob?.cancel()
        stateMonitorJob = scope.launch {
            var lastBroadcastTime = 0L
            while (!isCancelled.get() && nativeHandle != 0L) {
                try {
                    val mapped = mapNativeState(client.nativeGetState(nativeHandle))
                    if (mapped != null &&
                        mapped != ConnectionState.DISCONNECTED &&
                        mapped != ConnectionState.CONNECTED &&
                        mapped != currentState
                    ) {
                        val now = System.currentTimeMillis()
                        // Ensure minimum 100ms between state updates
                        if (now - lastBroadcastTime >= 100) {
                            currentState = mapped
                            lastBroadcastTime = now
                        }
                    }

                    if (currentState == ConnectionState.CONNECTED ||
                        currentState == ConnectionState.DISCONNECTING ||
                        currentState == ConnectionState.DISCONNECTED
                    ) {
                        break
                    }
                    delay(50) // Reduced delay for better responsiveness
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private suspend fun stopNativeStateMonitor() {
        stateMonitorJob?.cancel()
        stateMonitorJob = null
    }

    private fun mapNativeState(nativeState: Int): ConnectionState? {
        return when (nativeState) {
            0 -> ConnectionState.DISCONNECTED
            1 -> ConnectionState.CONNECTING
            2 -> ConnectionState.TLS_HANDSHAKE
            3 -> ConnectionState.PROTOCOL_HANDSHAKE
            4 -> ConnectionState.AUTHENTICATING
            5 -> ConnectionState.SESSION_SETUP
            6 -> ConnectionState.CONNECTED
            7 -> ConnectionState.DISCONNECTING
            else -> null
        }
    }

    // Track FDs we've already protected to avoid redundant protect() calls.
    // Synchronized because teardown (connect/disconnect/reconnect threads) clears
    // it while the receive loop may be calling contain/add concurrently.
    // Cleared on every native teardown so stale FDs from a previous session can
    // never suppress protect() for a recycled fd number in a fresh connection.
    private val protectedFds = Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Protect one tunnel socket, taking the protect() right back if it was
     * revoked.
     *
     * The right to call protect() belongs to the app that last called
     * VpnService.prepare(): another VPN calling it (e.g. AdGuard bringing its
     * protection back) silently revokes ours and protect() then returns false.
     * An unprotected tunnel socket is captured by our own TUN the moment it
     * comes up, leaving a session that reports "connected" but carries no
     * traffic. prepare() takes the right back; the establish that follows
     * claims the slot anyway.
     */
    private fun protectFd(fd: Int): Boolean {
        if (fd < 0) return false
        if (service.protect(fd)) return true
        return try {
            if (VpnService.prepare(service) == null) {
                val ok = service.protect(fd)
                if (!ok) Log.e(TAG, "protect() still failing for fd=$fd after re-claim")
                ok
            } else {
                Log.w(TAG, "Protect right re-claim: consent required")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Protect right re-claim failed: ${e.message}")
            false
        }
    }

    /**
     * Protect any new additional TCP sockets from routing through TUN.
     * Called periodically from the receive loop after additional connections are established.
     */
    private fun protectAdditionalSockets() {
        val allFds = client.getAllSocketFds() ?: return
        val natTUdpFd = client.nativeGetNatTUdpSocketFd(nativeHandle)
        for (fd in allFds) {
            if (fd >= 0 && fd !in protectedFds) {
                if (protectFd(fd)) {
                    protectedFds.add(fd)
                    Log.d(TAG, "Additional socket fd=$fd protected from TUN routing")
                } else {
                    Log.e(TAG, "Failed to protect additional socket fd=$fd")
                }
            }
        }
        if (natTUdpFd >= 0 && natTUdpFd !in protectedFds) {
            if (protectFd(natTUdpFd)) {
                protectedFds.add(natTUdpFd)
                Log.d(TAG, "NAT-T UDP socket fd=$natTUdpFd protected from TUN routing")
            } else {
                Log.e(TAG, "Failed to protect NAT-T UDP socket fd=$natTUdpFd")
            }
        }
    }
}

/**
 * Connection statistics data class
 */
data class ConnectionStatistics(
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
    val reconnectAttempts: Int
) {
    fun getTotalBytes(): Long = bytesSent + bytesReceived
    fun getTotalPackets(): Long = packetsSent + packetsReceived
}
