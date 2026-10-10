package com.example.sstp_flutter.client.ppp

import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.client.ClientBridge
import com.example.sstp_flutter.client.ControlMessage
import com.example.sstp_flutter.client.Result
import com.example.sstp_flutter.client.Where
import com.example.sstp_flutter.preference.OscPrefKey
import com.example.sstp_flutter.preference.accessor.getBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.getStringPrefValue
import com.example.sstp_flutter.unit.ppp.IpcpConfigureAck
import com.example.sstp_flutter.unit.ppp.IpcpConfigureFrame
import com.example.sstp_flutter.unit.ppp.IpcpConfigureReject
import com.example.sstp_flutter.unit.ppp.IpcpConfigureRequest
import com.example.sstp_flutter.unit.ppp.option.IpcpAddressOption
import com.example.sstp_flutter.unit.ppp.option.IpcpOptionPack
import com.example.sstp_flutter.unit.ppp.option.OPTION_TYPE_IPCP_DNS
import com.example.sstp_flutter.unit.ppp.option.OPTION_TYPE_IPCP_IP
import kotlinx.coroutines.delay
import java.net.Inet4Address


// A Configure-Reject for the address option is what SoftEther based public
// servers (VPN Gate) answer when their address pool briefly cannot hand out an
// address for the session. Retry a bounded number of times instead of spinning
// until the request counter is exhausted.
private const val MAX_IP_REJECT_RETRIES = 1
private const val IP_REJECT_RETRY_DELAY_MS = 1500L


internal class IpcpClient(bridge: ClientBridge) : ConfigClient<IpcpConfigureFrame>(Where.IPCP, bridge) {
    private val isDNSRequested = getBooleanPrefValue(OscPrefKey.DNS_DO_REQUEST_ADDRESS, bridge.prefs)
    private var isDNSRejected = false
    private var ipRejectRetries = 0
    private val requestedAddress = if (getBooleanPrefValue(OscPrefKey.PPP_DO_REQUEST_STATIC_IPv4_ADDRESS, bridge.prefs)) {
        try {
            Inet4Address.getByName(getStringPrefValue(OscPrefKey.PPP_STATIC_IPv4_ADDRESS, bridge.prefs)).address
        } catch (_: Exception) {
            // Invalid/empty static address: fall back to a server-assigned one.
            null
        }
    } else {
        null
    }

    override fun tryCreateServerReject(request: IpcpConfigureFrame): IpcpConfigureFrame? {
        val reject = IpcpOptionPack()

        if (request.options.unknownOptions.isNotEmpty()) {
            reject.unknownOptions = request.options.unknownOptions
        }

        request.options.dnsOption?.also { // client doesn't have dns server
            reject.dnsOption = request.options.dnsOption
        }

        return if (reject.allOptions.isNotEmpty()) {
            IpcpConfigureReject().also {
                it.id = request.id
                it.options = reject
                it.options.order = request.options.order
            }
        } else null
    }

    override fun tryCreateServerNak(request: IpcpConfigureFrame): IpcpConfigureFrame? {
        return null
    }

    override fun createServerAck(request: IpcpConfigureFrame): IpcpConfigureFrame {
        return IpcpConfigureAck().also {
            it.id = request.id
            it.options = request.options
        }
    }

    override fun createClientRequest(): IpcpConfigureFrame {
        val request = IpcpConfigureRequest()

        requestedAddress?.also {
            it.copyInto(bridge.currentIPv4)
        }

        request.options.ipOption = IpcpAddressOption(OPTION_TYPE_IPCP_IP).also {
            bridge.currentIPv4.copyInto(it.address)
        }

        if (isDNSRequested && !isDNSRejected) {
            request.options.dnsOption = IpcpAddressOption(OPTION_TYPE_IPCP_DNS).also {
                bridge.currentProposedDNS.copyInto(it.address)
            }
        }

        return request
    }

    override suspend fun tryAcceptClientNak(nak: IpcpConfigureFrame) {
        nak.options.ipOption?.also {
            if (requestedAddress != null) {
                bridge.controlMailbox.send(ControlMessage(Where.IPCP, Result.ERR_ADDRESS_REJECTED))
            } else {
                it.address.copyInto(bridge.currentIPv4)
            }
        }

        nak.options.dnsOption?.also {
            it.address.copyInto(bridge.currentProposedDNS)
        }
    }

    override suspend fun tryAcceptClientReject(reject: IpcpConfigureFrame) {
        reject.options.ipOption?.also {
            if (ipRejectRetries < MAX_IP_REJECT_RETRIES) {
                ipRejectRetries++
                ConnectionLog.append(
                    "IPCP",
                    "Address option rejected; retrying ($ipRejectRetries/$MAX_IP_REJECT_RETRIES)"
                )
                // An immediate resend is rejected just as fast, so give the
                // peer's address pool a moment to recover first.
                delay(IP_REJECT_RETRY_DELAY_MS)
            } else {
                // The peer cannot assign an address right now. Re-sending the
                // same request only burns the retry counter, so report it once
                // and let the app rebuild the whole connection instead.
                ConnectionLog.append("IPCP", "The server rejected the address option")
                bridge.controlMailbox.send(
                    ControlMessage(Where.IPCP_IP, Result.ERR_OPTION_REJECTED)
                )
            }
        }

        reject.options.dnsOption?.also {
            isDNSRejected = true
        }
    }
}
