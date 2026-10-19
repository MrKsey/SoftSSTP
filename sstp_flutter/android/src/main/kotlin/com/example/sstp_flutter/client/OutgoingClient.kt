package com.example.sstp_flutter.client

import android.os.SystemClock
import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.unit.ppp.PPP_HEADER
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_IP
import com.example.sstp_flutter.unit.ppp.PPP_PROTOCOL_IPv6
import com.example.sstp_flutter.unit.sstp.SSTP_PACKET_TYPE_DATA
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors


private const val PREFIX_SIZE = 8

private const val IPv4_HEADER: Int = (0x4).shl(28)
private const val IPv6_HEADER: Int = (0x6).shl(28)
private const val IP_VERSION_MASK: Int = (0xF).shl(28)

// Hot-path diagnostics are rate-limited: the packet counter keeps advancing,
// but after the initial burst at most one DIAG line per second reaches the
// log file (which lives on FUSE-backed storage, where an append costs far
// more than handling the packet itself).
private const val TX_DIAG_MIN_INTERVAL_MS = 1_000L

internal class OutgoingClient(private val bridge: ClientBridge) {
    private var jobMain: Job? = null

    // The whole upload path — TUN read, SSTP/PPP framing, TLS wrap, socket
    // write — runs as one loop on a dedicated thread. The previous design
    // handed every packet through a rendezvous channel between two coroutines:
    // the suspend/resume pairs spread tiny tasks across the whole shared pool,
    // and the scheduler overhead (measured: ~10 workers at 5-8% each during a
    // bulk upload) exceeded the cost of the data path itself.
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sstp-upload")
    }.asCoroutineDispatcher()

    // TEMPORARY diagnostics: how many packets were actually handed to the SSL
    // socket. A rebuilt tunnel that carries no traffic looks the same from the
    // outside whether the packets never left the TUN or the server ignored them.
    private var sentPackets = 0
    private var lastTxDiagAtMs = 0L

    private val mainBuffer = ByteBuffer.allocate(bridge.sslTerminal!!.getApplicationBufferSize())

    internal fun launchJobMain() {
        jobMain = bridge.service.scope.launch(bridge.handler + dispatcher) {
            ConnectionLog.append("DIAG", "outgoing jobMain started")
            val packetBuffer = ByteBuffer.allocate(bridge.PPP_MTU)

            while (isActive) {
                mainBuffer.clear()

                // Blocking TUN read: the loop paces itself on the interface,
                // and a full socket send buffer naturally throttles the read.
                bridge.ipTerminal!!.readPacket(packetBuffer)
                if (packetBuffer.remaining() == 0) continue
                if (!load(packetBuffer)) continue

                mainBuffer.flip()
                bridge.sslTerminal!!.send(mainBuffer)

                // TEMPORARY diagnostics: only maintained while the log is on,
                // so a disabled log costs a single volatile read per packet.
                if (ConnectionLog.enabled) {
                    sentPackets++
                    val now = SystemClock.uptimeMillis()
                    if (sentPackets <= 5 ||
                        (sentPackets % 500 == 0 && now - lastTxDiagAtMs >= TX_DIAG_MIN_INTERVAL_MS)
                    ) {
                        lastTxDiagAtMs = now
                        ConnectionLog.append("DIAG", "tx ip #$sentPackets to the socket")
                    }
                }
            }
        }
    }

    private suspend fun load(packet: ByteBuffer): Boolean { // true if data protocol is enabled
        val header = packet.getInt(0)
        val protocol = when (header and IP_VERSION_MASK) {
            IPv4_HEADER -> {
                if (!bridge.PPP_IPv4_ENABLED) return false

                PPP_PROTOCOL_IP
            }

            IPv6_HEADER -> {
                if (!bridge.PPP_IPv6_ENABLED) return false

                PPP_PROTOCOL_IPv6
            }

            else -> {
                bridge.controlMailbox.send(ControlMessage(Where.OUTGOING, Result.ERR_UNKNOWN_TYPE))

                return false
            }
        }

        mainBuffer.putShort(SSTP_PACKET_TYPE_DATA)
        mainBuffer.putShort((packet.remaining() + PREFIX_SIZE).toShort())
        mainBuffer.putShort(PPP_HEADER)
        mainBuffer.putShort(protocol)
        mainBuffer.put(packet)

        return true
    }

    internal fun cancel() {
        jobMain?.cancel()
        dispatcher.close()
    }
}
