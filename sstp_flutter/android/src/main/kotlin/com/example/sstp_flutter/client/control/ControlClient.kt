package com.example.sstp_flutter.client.control

import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.FlutterCaller
import com.example.sstp_flutter.client.ClientBridge
import com.example.sstp_flutter.connectionErrorCode
import com.example.sstp_flutter.connectionErrorMessage
import com.example.sstp_flutter.client.ControlMessage
import com.example.sstp_flutter.client.OutgoingClient
import com.example.sstp_flutter.client.Result
import com.example.sstp_flutter.client.SSTP_REQUEST_TIMEOUT
import com.example.sstp_flutter.client.SstpClient
import com.example.sstp_flutter.client.Where
import com.example.sstp_flutter.client.incoming.IncomingClient
import com.example.sstp_flutter.client.ppp.ChapClient
import com.example.sstp_flutter.client.ppp.IpcpClient
import com.example.sstp_flutter.client.ppp.Ipv6cpClient
import com.example.sstp_flutter.client.ppp.LCPClient
import com.example.sstp_flutter.client.ppp.PAPClient
import com.example.sstp_flutter.client.ppp.PPPClient
import com.example.sstp_flutter.client.ppp.PPP_NEGOTIATION_TIMEOUT
import com.example.sstp_flutter.debug.assertAlways
import com.example.sstp_flutter.preference.OscPrefKey
import com.example.sstp_flutter.preference.accessor.getBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.getIntPrefValue
import com.example.sstp_flutter.preference.accessor.resetReconnectionLife
import com.example.sstp_flutter.service.CONNECTION_STATUS_CONNECTED
import com.example.sstp_flutter.service.NOTIFICATION_ERROR_ID
import com.example.sstp_flutter.terminal.SSL_REQUEST_INTERVAL
import com.example.sstp_flutter.unit.ppp.option.AuthOptionMSChapv2
import com.example.sstp_flutter.unit.ppp.option.AuthOptionPAP
import com.example.sstp_flutter.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import com.example.sstp_flutter.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import com.example.sstp_flutter.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull


internal class ControlClient(internal val bridge: ClientBridge) {
    private var observer: NetworkObserver? = null

//    lateinit var _channel: MethodChannel
    private var sstpClient: SstpClient? = null
    private var pppClient: PPPClient? = null
    private var incomingClient: IncomingClient? = null
    private var outgoingClient: OutgoingClient? = null

    private var lcpClient: LCPClient? = null
    private var papClient: PAPClient? = null
    private var chapClient: ChapClient? = null
    private var ipcpClient: IpcpClient? = null
    private var ipv6cpClient: Ipv6cpClient? = null

    private var jobMain: Job? = null

    // Set when this session is abandoned because another VPN revoked our slot.
    // A revoked TUN makes in-flight writes fail with EIO (I/O error), and the
    // default exception handler would tear the service down — exactly when the
    // service is trying to reclaim the slot. While abandoned, this session's
    // errors are ignored and the service owns the teardown.
    @Volatile
    private var abandoned = false

    private val mutex = Mutex()

    // Guards against reporting more than one connection failure per session.
    // A single failed attempt can surface through both the exception handler and
    // the phase check, and duplicate reports made the app skip an Auto candidate
    // (TLS 1.3 -> 1.1 instead of TLS 1.3 -> 1.2).
    @Volatile
    private var failureReported = false

    private val isReconnectionEnabled = getBooleanPrefValue(OscPrefKey.RECONNECTION_ENABLED, bridge.prefs)
    private val isReconnectionAvailable: Boolean
        get() = getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, bridge.prefs) > 0

    private fun attachHandler() {
        bridge.handler = CoroutineExceptionHandler { _, throwable ->
            if (abandoned) {
                // The session was already shut down on purpose (slot reclaim or
                // a normal disconnect), so this error must not stop the service.
                ConnectionLog.append(
                    "SERVICE",
                    "Ignoring session error after shutdown: ${throwable.message}"
                )
                return@CoroutineExceptionHandler
            }

            // A network-level failure while the tunnel is up is handled by the
            // service, which rebuilds the tunnel on the new network instead of
            // tearing everything down.
            if (bridge.service.handleSessionError(throwable)) return@CoroutineExceptionHandler

            // Surface an SSL handshake failure to the app so it can retry with a
            // different TLS version or explain that a pinned version was
            // rejected.
            if (throwable.isSslFailure()) {
                ConnectionLog.append("SSL", "Reporting handshake failure to the app")
                reportFailure(
                    "SSL_HANDSHAKE_FAILED",
                    "The secure handshake failed. The server may not support the " +
                        "selected TLS version."
                )
            }

            kill(isReconnectionEnabled) {
//                FlutterCaller().disconnect()
                val header = "OSC: ERR_UNEXPECTED"
                bridge.service.logWriter?.report(header + "\n" + throwable.stackTraceToString())
                bridge.service.makeNotification(NOTIFICATION_ERROR_ID, header)
                ConnectionLog.append("ERROR", "$header\n${throwable.stackTraceToString()}")
            }
        }
    }

    /**
     * True when [this] (or one of its causes) is an SSL/TLS failure, e.g. a
     * handshake rejected because the server does not support the selected TLS
     * version.
     */
    private fun Throwable.isSslFailure(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 5) {
            if (current is javax.net.ssl.SSLException) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Reports a connection failure to the app exactly once per session. Later
     * reports for the same attempt are ignored so the app cannot skip an Auto
     * candidate.
     */
    private fun reportFailure(code: String, message: String) {
        if (failureReported) return
        failureReported = true
        connectionErrorCode = code
        connectionErrorMessage = message
        FlutterCaller().connectionFailed(code, message)
    }

    internal fun launchJobMain() {
        attachHandler()
        failureReported = false

        jobMain = bridge.service.scope.launch(bridge.handler) {
            bridge.attachSSLTerminal()
            bridge.attachIPTerminal()


            bridge.sslTerminal!!.initialize()
            if (!expectProceeded(Where.SSL, SSL_REQUEST_INTERVAL)) {
                return@launch
            }


            IncomingClient(bridge).also {
                it.launchJobMain()
                incomingClient = it
            }


            SstpClient(bridge).also {
                sstpClient = it
                incomingClient!!.registerMailbox(it)
                it.launchJobRequest()

                if (!expectProceeded(Where.SSTP_REQUEST, SSTP_REQUEST_TIMEOUT)) {
                    return@launch
                }

                sstpClient!!.launchJobControl()
            }


            PPPClient(bridge).also {
                pppClient = it
                incomingClient!!.registerMailbox(it)
                it.launchJobControl()
            }


            LCPClient(bridge).also {
                incomingClient!!.registerMailbox(it)
                it.launchJobNegotiation()

                if (!expectProceeded(Where.LCP, PPP_NEGOTIATION_TIMEOUT)) {
                    return@launch
                }

                incomingClient!!.unregisterMailbox(it)
            }


            val authTimeout = getIntPrefValue(OscPrefKey.PPP_AUTH_TIMEOUT, bridge.prefs) * 1000L
            when (bridge.currentAuth) {
                is AuthOptionPAP -> PAPClient(bridge).also {
                    incomingClient!!.registerMailbox(it)
                    it.launchJobAuth()

                    if (!expectProceeded(Where.PAP, authTimeout)) {
                        return@launch
                    }

                    incomingClient!!.unregisterMailbox(it)
                }

                is AuthOptionMSChapv2 -> ChapClient(bridge).also {
                    chapClient = it
                    incomingClient!!.registerMailbox(it)
                    it.launchJobAuth()

                    if (!expectProceeded(Where.CHAP, authTimeout)) {
                        return@launch
                    }
                }

                else -> throw NotImplementedError(bridge.currentAuth.protocol.toString())
            }

            println("before send call connected")
            sstpClient!!.sendCallConnected()


            if (bridge.PPP_IPv4_ENABLED) {
                IpcpClient(bridge).also {
                    incomingClient!!.registerMailbox(it)
                    it.launchJobNegotiation()

                    if (!expectProceeded(Where.IPCP, PPP_NEGOTIATION_TIMEOUT)) {
                        return@launch
                    }

                    incomingClient!!.unregisterMailbox(it)
                }
            }


            if (bridge.PPP_IPv6_ENABLED) {
                Ipv6cpClient(bridge).also {
                    incomingClient!!.registerMailbox(it)
                    it.launchJobNegotiation()

                    if (!expectProceeded(Where.IPV6CP, PPP_NEGOTIATION_TIMEOUT)) {
                        return@launch
                    }

                    incomingClient!!.unregisterMailbox(it)
                }
            }


            bridge.ipTerminal!!.initialize()
            if (!expectProceeded(Where.IP, null)) {
                return@launch
            }

            bridge.ipTerminal!!.startTrafficReporting()


            OutgoingClient(bridge).also {
                it.launchJobMain()
                outgoingClient = it
            }


            observer = NetworkObserver(bridge)

            if (isReconnectionEnabled) {
                resetReconnectionLife(bridge.prefs)
            }

            ConnectionLog.append("PHASE", "Connection established")

            // Mirror the state on the home screen in the foreground notification.
            bridge.service.updateNotificationStatus(CONNECTION_STATUS_CONNECTED)
            // A healthy tunnel resets the network-reconnect budget.
            bridge.service.onConnectionEstablished()

            expectProceeded(Where.SSTP_CONTROL, null) // wait ERR_ message until disconnection
        }
    }

    private suspend fun expectProceeded(where: Where, timeout: Long?): Boolean {
        val received = if (timeout != null) {
//            println("before invoke 2")
//            val tempMap : HashMap<String,Any>  = HashMap()
//
//            tempMap.put("disconnected",true)
//            tempMap.put("dns",dnsEnabled)
//            tempMap.put("dnsCount", dnsCount)
//            flutterChannel.invokeMethod("connectResponse", tempMap)
            withTimeoutOrNull(timeout) {
                bridge.controlMailbox.receive()
            } ?: ControlMessage(where, Result.ERR_TIMEOUT)

        } else {
            bridge.controlMailbox.receive()
        }

        // The session was abandoned because another VPN revoked our slot; the
        // service handles the reconnect, so do not report a failure here.
        if (abandoned) return false

        if (received.result == Result.PROCEEDED) {
            assertAlways(received.from == where)

            ConnectionLog.append("PHASE", "$where: OK")
            return true
        }

        ConnectionLog.append("PHASE", "$where failed -> ${received.from}: ${received.result.name}")

        // A failed TUN setup is not a protocol error and needs a dedicated,
        // actionable message instead of the generic "ERR_UNEXPECTED".
        if (received.result == Result.ERR_VPN_ESTABLISH_FAILED) {
            reportFailure(
                "VPN_ESTABLISH_FAILED",
                "Could not create the VPN interface. Another VPN app may hold the " +
                    "VPN slot (e.g. an always-on VPN), or the VPN permission was " +
                    "revoked. Turn off other VPNs and try again."
            )
        } else {
            // Report protocol failures the app can act on (TLS version / auth
            // protocol mismatch) so it can try another option or explain that a
            // pinned setting was rejected.
            when (received.from) {
                Where.SSL -> if (received.result == Result.ERR_UNEXPECTED_MESSAGE) {
                    reportFailure(
                        "SSL_HANDSHAKE_FAILED",
                        "The secure handshake failed. The server may not support " +
                            "the selected TLS version."
                    )
                }

                Where.LCP, Where.LCP_AUTH, Where.CHAP, Where.PAP ->
                    reportFailure(
                        "PPP_AUTH_FAILED",
                        "Authentication failed. The server may not support the " +
                            "selected authentication protocol."
                    )

                // The address negotiation is often rejected transiently by
                // public servers, so give it a dedicated code the app can retry.
                Where.IPCP, Where.IPCP_IP, Where.IPV6CP, Where.IPV6CP_IDENTIFIER,
                Where.IPv4, Where.IPv6 -> reportFailure(
                    "IPCP_FAILED",
                    "The server rejected the IP address negotiation."
                )

                // Nothing to configure when the profile enables neither IPv4
                // nor IPv6; explain that instead of the generic error.
                Where.IP -> reportFailure(
                    "NO_ADDRESS_FAMILY",
                    "This profile has neither IPv4 nor IPv6 enabled, so there is " +
                        "no address to configure. Turn on IPv4 (or IPv6) in the " +
                        "profile settings and try again."
                )

                // Any other phase failure (SSTP request, …) would otherwise
                // end silently, so report it too.
                else -> reportFailure(
                    "VPN_CONNECTION_FAILED",
                    "The connection could not be established. The server " +
                        "rejected the connection parameters."
                )
            }
        }

        val lastPacketType = if (received.result == Result.ERR_DISCONNECT_REQUESTED) {
            SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
        } else {
            SSTP_MESSAGE_TYPE_CALL_ABORT
        }

        kill(isReconnectionEnabled) {
            sstpClient?.sendLastPacket(lastPacketType)

            val message = "${received.from.name}: ${received.result.name}"
            bridge.service.logWriter?.report(message)
            bridge.service.makeNotification(NOTIFICATION_ERROR_ID, message)
        }

        return false
    }

    internal fun disconnect() { // use if the user want to normally disconnect
        kill(false) {
            sstpClient?.sendLastPacket(SSTP_MESSAGE_TYPE_CALL_DISCONNECT)
        }
    }

    /**
     * Tears the session down synchronously: stops the pipeline, unregisters the
     * network observer and releases the TUN descriptor / SSL socket.
     *
     * Unlike [kill] this never touches the service lifecycle, so it is safe to
     * call right before starting a new connection. Doing this synchronously is
     * essential: the previous cleanup only ran inside a coroutine, which the
     * service cancelled on teardown, leaking the TUN interface and making the
     * next establish() return null.
     */
    internal fun shutdown() {
        // Mark first: any in-flight write to the revoked TUN will fail asynchronously
        // and must be ignored instead of tearing the service down.
        abandoned = true
        try { observer?.close() } catch (_: Exception) {}
        observer = null
        jobMain?.cancel()
        cancelClients()
        closeTerminals()
    }

    internal fun kill(isReconnectionRequested: Boolean, cleanup: (suspend () -> Unit)?) {
        // An abandoned session must never stop the service: the service is
        // reclaiming the VPN slot and will start a fresh session itself.
        if (abandoned) return
        if (!mutex.tryLock()) return

        bridge.service.scope.launch {
            try {
                observer?.close()
                observer = null
                jobMain?.cancel()
                cancelClients()
                cleanup?.invoke()
            } finally {
                // Always release the interface, even if the cleanup above threw.
                closeTerminals()
            }

            if (isReconnectionRequested && isReconnectionAvailable) {
                bridge.service.launchJobReconnect()
            } else {
                bridge.service.close()
            }
        }
    }

    private fun cancelClients() {
        lcpClient?.cancel()
        papClient?.cancel()
        chapClient?.cancel()
        ipcpClient?.cancel()
        ipv6cpClient?.cancel()
        sstpClient?.cancel()
        pppClient?.cancel()
        incomingClient?.cancel()
        outgoingClient?.cancel()
    }

    /**
     * Releases native resources (TUN descriptor, SSL socket, traffic reporter).
     *
     * Internal so the service can call it synchronously from [onDestroy] before
     * the coroutine scope is cancelled: otherwise the async cleanup could be
     * cancelled mid-flight, leaking the TUN interface and making the next
     * establish() return null.
     */
    internal fun closeTerminals() {
        bridge.ipTerminal?.stopTrafficReporting()
        bridge.sslTerminal?.close()
        bridge.ipTerminal?.close()
    }
}
