package com.example.sstp_flutter.client.incoming

import android.os.SystemClock
import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.MAX_MRU
import com.example.sstp_flutter.client.ClientBridge
import com.example.sstp_flutter.client.ControlMessage
import com.example.sstp_flutter.client.Result
import com.example.sstp_flutter.client.SstpClient
import com.example.sstp_flutter.client.Where
import com.example.sstp_flutter.client.ppp.ChapClient
import com.example.sstp_flutter.client.ppp.IpcpClient
import com.example.sstp_flutter.client.ppp.Ipv6cpClient
import com.example.sstp_flutter.client.ppp.LCPClient
import com.example.sstp_flutter.client.ppp.PAPClient
import com.example.sstp_flutter.client.ppp.PPPClient
import com.example.sstp_flutter.extension.probeByte
import com.example.sstp_flutter.extension.probeShort
import com.example.sstp_flutter.extension.toIntAsUShort
import com.example.sstp_flutter.unit.ppp.ChapFrame
import com.example.sstp_flutter.unit.ppp.Frame
import com.example.sstp_flutter.unit.ppp.IpcpConfigureFrame
import com.example.sstp_flutter.unit.ppp.Ipv6cpConfigureFrame
import com.example.sstp_flutter.unit.ppp.LCPConfigureFrame
import com.example.sstp_flutter.unit.ppp.LCPEchoRequest
import com.example.sstp_flutter.unit.ppp.PAPFrame
import com.example.sstp_flutter.unit.ppp.PPP_HEADER
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_CHAP
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_IP
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_IPCP
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_IPv6
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_IPv6CP
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_LCP
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_PAP
import com.example.sstp_flutter.unit.sstp.ControlPacket
import com.example.sstp_flutter.unit.sstp.SSTP_PACKET_TYPE_CONTROL
import com.example.sstp_flutter.unit.sstp.SSTP_PACKET_TYPE_DATA
import com.example.sstp_flutter.unit.sstp.SstpEchoRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors


private const val SSTP_ECHO_INTERVAL = 20_000L
private const val PPP_ECHO_INTERVAL = 20_000L

// Rate limits of the TEMPORARY DIAG lines (see diag()).
private const val DIAG_BURST_COUNT = 15
private const val DIAG_EVERY_N = 200
private const val DIAG_MIN_INTERVAL_MS = 1_000L

internal class IncomingClient(internal val bridge: ClientBridge) {
    private val bufferSize = bridge.sslTerminal!!.getApplicationBufferSize() + MAX_MRU + 8 // MAX_MRU + 8 for fragment

    private var jobMain: Job? = null

    // The whole receive path — socket read, TLS unwrap, framing, TUN write —
    // runs as one loop on a dedicated thread: the loop blocks on the socket
    // read, so keeping it off the shared pool avoids both pool contention and
    // the resume-on-another-worker churn after every echo or control packet.
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sstp-incoming")
    }.asCoroutineDispatcher()

    // TEMPORARY diagnostics: counts received frames so the log shows what the
    // peer actually sends (protocol mix) without flooding the log file. The
    // message is built lazily and, after the initial burst, DIAG lines are
    // rate-limited to one per second: the counters keep advancing, but the hot
    // path neither formats strings nor appends to the log file per packet.
    private var diagCount = 0
    private var lastDiagLogAtMs = 0L

    /**
     * Hot-path diagnostics. Inlined on purpose: with a non-inline lambda the
     * closure object was allocated for every received packet even when the log
     * was off, which showed up as GC pressure on a busy download. The inline
     * wrapper checks the flag and the rate limit first, so a disabled log costs
     * one volatile read and no allocation; the message is only built when a
     * line is actually due.
     */
    internal inline fun diag(message: () -> String) {
        if (!ConnectionLog.enabled) return
        if (!diagDue()) return
        ConnectionLog.append("DIAG", "#$diagCount ${message()}")
    }

    @PublishedApi
    internal fun diagDue(): Boolean {
        diagCount++
        val inBurst = diagCount <= DIAG_BURST_COUNT
        if (!inBurst && diagCount % DIAG_EVERY_N != 0) return false
        if (!inBurst) {
            val now = SystemClock.uptimeMillis()
            if (now - lastDiagLogAtMs < DIAG_MIN_INTERVAL_MS) return false
            lastDiagLogAtMs = now
        }
        return true
    }

    // TEMPORARY diagnostics: the first incoming IPv4 packets are described in
    // full (protocol, addresses, ports) so the log shows whether the server is
    // actually answering the rebuilt session, and with what.
    private var ipDiagCount = 0

    internal fun diagIpPacket(start: Int, size: Int, buffer: ByteBuffer) {
        // The packet is only parsed for the log; with logging off there is
        // nothing to do here at all.
        if (!ConnectionLog.enabled) return
        if (ipDiagCount >= 20) return
        ipDiagCount++
        val array = buffer.array()
        try {
            val protocol = array[start + 9].toInt() and 0xFF
            fun ip(offset: Int): String =
                "${array[start + offset].toInt() and 0xFF}." +
                    "${array[start + offset + 1].toInt() and 0xFF}." +
                    "${array[start + offset + 2].toInt() and 0xFF}." +
                    "${array[start + offset + 3].toInt() and 0xFF}"
            val extra = when (protocol) {
                6 -> {
                    val srcPort = ((array[start + 20].toInt() and 0xFF) shl 8) or
                        (array[start + 21].toInt() and 0xFF)
                    val dstPort = ((array[start + 22].toInt() and 0xFF) shl 8) or
                        (array[start + 23].toInt() and 0xFF)
                    val flags = array[start + 33].toInt() and 0xFF
                    "tcp ${ip(12)}:$srcPort->${ip(16)}:$dstPort flags=0x${"%02x".format(flags)}"
                }
                17 -> {
                    val srcPort = ((array[start + 20].toInt() and 0xFF) shl 8) or
                        (array[start + 21].toInt() and 0xFF)
                    val dstPort = ((array[start + 22].toInt() and 0xFF) shl 8) or
                        (array[start + 23].toInt() and 0xFF)
                    "udp ${ip(12)}:$srcPort->${ip(16)}:$dstPort"
                }
                1 -> "icmp ${ip(12)}->${ip(16)} type=${array[start + 20].toInt() and 0xFF}"
                else -> "proto=$protocol ${ip(12)}->${ip(16)}"
            }
            ConnectionLog.append("DIAG", "IP#${ipDiagCount} rx size=$size $extra")
        } catch (_: Exception) {
            ConnectionLog.append("DIAG", "IP#${ipDiagCount} rx size=$size (unparsed)")
        }
    }

    internal var lcpMailbox: Channel<LCPConfigureFrame>? = null
    internal var papMailbox: Channel<PAPFrame>? = null
    internal var chapMailbox: Channel<ChapFrame>? = null
    internal var ipcpMailbox: Channel<IpcpConfigureFrame>? = null
    internal var ipv6cpMailbox: Channel<Ipv6cpConfigureFrame>? = null
    internal var pppMailbox: Channel<Frame>? = null
    internal var sstpMailbox: Channel<ControlPacket>? = null

    private val sstpTimer = EchoTimer(SSTP_ECHO_INTERVAL) {
        SstpEchoRequest().also {
            if (ConnectionLog.enabled) ConnectionLog.append("ECHO", "SSTP echo request out")
            bridge.sslTerminal!!.sendDataUnit(it)
        }
    }

    private val pppTimer = EchoTimer(PPP_ECHO_INTERVAL) {
        LCPEchoRequest().also {
            it.id = bridge.allocateNewFrameID()
            it.holder = "Abura Mashi Mashi".toByteArray(Charsets.US_ASCII)
            if (ConnectionLog.enabled) {
                ConnectionLog.append("ECHO", "LCP echo request out id=${it.id}")
            }
            bridge.sslTerminal!!.sendDataUnit(it)
        }
    }

    internal fun <T> registerMailbox(client: T) {
        when (client) {
            is LCPClient -> lcpMailbox = client.mailbox
            is PAPClient -> papMailbox = client.mailbox
            is ChapClient -> chapMailbox = client.mailbox
            is IpcpClient -> ipcpMailbox = client.mailbox
            is Ipv6cpClient -> ipv6cpMailbox = client.mailbox
            is PPPClient -> pppMailbox = client.mailbox
            is SstpClient -> sstpMailbox = client.mailbox
            else -> throw NotImplementedError(client?.toString() ?: "")
        }
    }

    internal fun <T> unregisterMailbox(client: T) {
        when (client) {
            is LCPClient -> lcpMailbox = null
            is PAPClient -> papMailbox = null
            is ChapClient -> chapMailbox = null
            is IpcpClient -> ipcpMailbox = null
            is Ipv6cpClient -> ipv6cpMailbox = null
            is PPPClient -> pppMailbox = null
            is SstpClient -> sstpMailbox = null
            else -> throw NotImplementedError(client?.toString() ?: "")
        }
    }

    internal fun launchJobMain() {
        jobMain = bridge.service.scope.launch(bridge.handler + dispatcher) {
            val buffer = ByteBuffer.allocate(bufferSize).also { it.limit(0) }

            sstpTimer.tick()
            pppTimer.tick()

            while (isActive) {
                if (!sstpTimer.checkAlive()) {
                    println("before invoke")
//                    FlutterCaller().disconnect()
                    bridge.controlMailbox.send(
                        ControlMessage(Where.SSTP_CONTROL, Result.ERR_TIMEOUT)
                    )

                    return@launch
                }

                if (!pppTimer.checkAlive()) {
                    println("before invoke")
//                    FlutterCaller().disconnect()
                    bridge.controlMailbox.send(
                        ControlMessage(Where.PPP, Result.ERR_TIMEOUT)
                    )

                    return@launch
                }


                val size = getPacketSize(buffer)
                when (size) {
                    in 4..bufferSize -> { }

                    -1 -> {
                        bridge.sslTerminal!!.receive(buffer)
                        continue
                    }

                    else -> {
                        bridge.controlMailbox.send(
                            ControlMessage(Where.INCOMING, Result.ERR_INVALID_PACKET_SIZE)
                        )
                        return@launch
                    }
                }

                if (size > buffer.remaining()) {
                    bridge.sslTerminal!!.receive(buffer)
                    continue
                }

                sstpTimer.tick()

                when (buffer.probeShort(0)) {
                    SSTP_PACKET_TYPE_DATA -> {
                        if (buffer.probeShort(4) != PPP_HEADER) {
                            bridge.controlMailbox.send(
                                ControlMessage(Where.SSTP_DATA, Result.ERR_UNKNOWN_TYPE)
                            )
                            return@launch
                        }

                        pppTimer.tick()

                        val protocol = buffer.probeShort(6)

                        diag { "DATA proto=0x${"%04x".format(protocol)} size=$size" }


                        // DATA
                        if (protocol == PPP_PROTOCOL_IP) {
                            diag { "IP rx size=$size" }
                            processIPPacket(bridge.PPP_IPv4_ENABLED, size, buffer)
                            continue
                        }

                        if (protocol == PPP_PROTOCOL_IPv6) {
                            diag { "IPv6 rx size=$size" }
                            processIPPacket(bridge.PPP_IPv6_ENABLED, size, buffer)
                            continue
                        }
                        

                        // CONTROL
                        val code = buffer.probeByte(8)
                        val isGo = when (protocol) {
                            PPP_PROTOCOL_LCP -> processLcpFrame(code, buffer)
                            PPP_PROTOCOL_PAP -> processPAPFrame(code, buffer)
                            PPP_PROTOCOL_CHAP -> processChapFrame(code, buffer)
                            PPP_PROTOCOL_IPCP -> processIpcpFrame(code, buffer)
                            PPP_PROTOCOL_IPv6CP -> processIpv6cpFrame(code, buffer)
                            else -> processUnknownProtocol(protocol, size, buffer)
                        }

                        if (!isGo) {
                            return@launch
                        }
                    }

                    SSTP_PACKET_TYPE_CONTROL -> {
                        diag { "CTRL type=${buffer.probeShort(4)}" }
                        if (!processControlPacket(buffer.probeShort(4), buffer)) {
                            return@launch
                        }
                    }

                    else -> {
                        bridge.controlMailbox.send(
                            ControlMessage(Where.INCOMING, Result.ERR_UNKNOWN_TYPE)
                        )

                        return@launch
                    }
                }
            }
        }
    }

    private fun getPacketSize(buffer: ByteBuffer): Int {
        return if (buffer.remaining() < 4) {
            -1
        } else {
            buffer.probeShort(2).toIntAsUShort()
        }
    }

    internal fun cancel() {
        jobMain?.cancel()
        dispatcher.close()
    }
}
