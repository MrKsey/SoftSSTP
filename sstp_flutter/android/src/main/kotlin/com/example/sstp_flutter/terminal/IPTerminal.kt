package com.example.sstp_flutter.terminal

import android.annotation.SuppressLint
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.FlutterCaller
import com.example.sstp_flutter.client.ClientBridge
import com.example.sstp_flutter.client.ControlMessage
import com.example.sstp_flutter.client.Result
import com.example.sstp_flutter.client.Where
import com.example.sstp_flutter.extension.isSame
import com.example.sstp_flutter.extension.toHexByteArray
import com.example.sstp_flutter.preference.OscPrefKey
import com.example.sstp_flutter.preference.accessor.getBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.getStringPrefValue
import com.example.sstp_flutter.trafficTotalDownloadBits
import com.example.sstp_flutter.trafficTotalUploadBits
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong


private const val TRAFFIC_REPORT_INTERVAL_MS = 1_000L

// Energy saving stretches the reporter (and therefore the notification refresh)
// so an idle tunnel wakes the CPU less often. It does not touch the data path,
// which blocks on the TUN read.
private const val TRAFFIC_REPORT_INTERVAL_POWER_SAVE_MS = 3_000L

// `establish()` can return null on some devices when a previous TUN interface
// has not been released yet. Retry a few times with a fresh builder before
// giving up, so a transient failure does not abort the whole connection.
private const val ESTABLISH_MAX_ATTEMPTS = 6
private const val ESTABLISH_RETRY_DELAY_MS = 800L


internal class IPTerminal(private val bridge: ClientBridge) {
    private var fd: ParcelFileDescriptor? = null

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    private val isAppBasedRuleEnabled = bridge.allowedApps.isNotEmpty()
    private val isDefaultRouteAdded = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE, bridge.prefs)
    private val isPrivateAddressesRouted = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ROUTE_PRIVATE_ADDRESSES, bridge.prefs)
    private val isCustomDNSServerUsed = getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, bridge.prefs)
    private val isCustomRoutesAdded = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_CUSTOM_ROUTES, bridge.prefs)

    // Byte and packet counters are updated on the hot data path (one increment
    // per packet) and read once per second by the reporter. AtomicLong keeps the
    // increments cheap and correct without any locks. The packet counts feed the
    // end-of-session summary only.
    private val totalDownloadBytes = AtomicLong(0)
    private val totalUploadBytes = AtomicLong(0)
    private val totalDownloadPackets = AtomicLong(0)
    private val totalUploadPackets = AtomicLong(0)

    // Guards the one-shot end-of-session summary: close() is reached from several
    // teardown paths, and only the first one carries the real totals.
    private var sessionEndLogged = false

    private var trafficJob: Job? = null
    private var lastReportedDownloadBytes = 0L
    private var lastReportedUploadBytes = 0L
    private var lastReportAtMs = 0L

    @SuppressLint("NewApi")
    internal suspend fun initialize() {
        resetTrafficCounters()

        // With neither address family enabled there is nothing to configure and
        // establish() would throw "At least one address must be specified" on
        // every attempt. Report it once instead of retrying six times.
        if (!bridge.PPP_IPv4_ENABLED && !bridge.PPP_IPv6_ENABLED) {
            ConnectionLog.append("IP", "Neither IPv4 nor IPv6 is enabled")
            bridge.controlMailbox.send(ControlMessage(Where.IP, Result.ERR_NO_ADDRESS_FAMILY))
            return
        }

        // Address validation is terminal: without a usable address there is no
        // point in retrying the interface setup.
        if (bridge.PPP_IPv4_ENABLED && bridge.currentIPv4.isSame(ByteArray(4))) {
            bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
            return
        }
        if (bridge.PPP_IPv6_ENABLED && bridge.currentIPv6.isSame(ByteArray(8))) {
            bridge.controlMailbox.send(ControlMessage(Where.IPv6, Result.ERR_INVALID_ADDRESS))
            return
        }

        when {
            // The address the server assigned via IPCP, surfaced to the UI.
            // IPv4 is always preferred; IPv6 is reported only when IPv4 is off.
            bridge.PPP_IPv4_ENABLED -> {
                FlutterCaller().sendAssignedIp(
                    InetAddress.getByAddress(bridge.currentIPv4).hostAddress ?: ""
                )
            }
            bridge.PPP_IPv6_ENABLED -> {
                val ipv6 = ByteArray(16).also { // link local address
                    "FE80".toHexByteArray().copyInto(it)
                    ByteArray(6).copyInto(it, destinationOffset = 2)
                    bridge.currentIPv6.copyInto(it, destinationOffset = 8)
                }
                FlutterCaller().sendAssignedIp(
                    InetAddress.getByAddress(ipv6).hostAddress ?: ""
                )
            }
        }

        val descriptor = establishInterface()
        if (descriptor == null) {
            bridge.controlMailbox.send(ControlMessage(Where.IP, Result.ERR_VPN_ESTABLISH_FAILED))
            return
        }

        fd = descriptor.also {
            inputStream = FileInputStream(it.fileDescriptor)
            outputStream = FileOutputStream(it.fileDescriptor)
        }

        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    /**
     * Establishes the TUN interface, retrying with a fresh builder.
     *
     * `establish()` returns null unless this app currently owns the VPN slot,
     * and it does NOT dethrone another VPN by itself. Only `VpnService.prepare`
     * does, so we (re)claim the slot right before every attempt. This defeats
     * the race where another VPN (e.g. AdGuard) re-prepares itself during our
     * PPP negotiation and silently steals ownership.
     *
     * A fresh builder is used each time because `VpnService.Builder` is
     * single-use, and any interface still held is released before retrying.
     */
    private suspend fun establishInterface(): ParcelFileDescriptor? {
        repeat(ESTABLISH_MAX_ATTEMPTS) { attempt ->
            // Re-claim ownership of the VPN slot immediately before establishing.
            if (!claimVpnSlot()) {
                // Consent was revoked or never granted: retrying cannot help.
                return null
            }

            val builder = bridge.newBuilder()
            if (!configure(builder)) return null

            val descriptor = try {
                builder.establish()
            } catch (e: Exception) {
                ConnectionLog.append("IP", "establish() threw: ${e.message}")
                null
            }
            if (descriptor != null) return descriptor

            ConnectionLog.append(
                "IP",
                "establish() returned null (attempt ${attempt + 1}/$ESTABLISH_MAX_ATTEMPTS)"
            )
            if (attempt < ESTABLISH_MAX_ATTEMPTS - 1) {
                // Release anything still held so a stale interface can be torn
                // down before the next attempt.
                closeDescriptor()
                delay(ESTABLISH_RETRY_DELAY_MS)
            }
        }
        return null
    }

    /**
     * Ensures this app owns the VPN slot, dethroning any other active VPN.
     *
     * Returns true when we are (or have just become) the owner, false when the
     * system still requires the user's consent, in which case a connection
     * cannot be established from the background.
     */
    private fun claimVpnSlot(): Boolean {
        return try {
            val consentIntent = VpnService.prepare(bridge.service)
            if (consentIntent == null) {
                true
            } else {
                ConnectionLog.append(
                    "IP",
                    "VpnService.prepare requires user consent (revoked or never granted)"
                )
                false
            }
        } catch (e: Exception) {
            ConnectionLog.append("IP", "VpnService.prepare failed: ${e.message}")
            false
        }
    }

    /** Applies the full VPN configuration to a freshly created [builder]. */
    private suspend fun configure(builder: VpnService.Builder): Boolean {
        return try {
            configureInternal(builder)
            true
        } catch (e: Exception) {
            ConnectionLog.append("IP", "builder configuration failed: ${e.message}")
            false
        }
    }

    private suspend fun configureInternal(builder: VpnService.Builder) {
        if (bridge.PPP_IPv4_ENABLED) {
            builder.addAddress(InetAddress.getByAddress(bridge.currentIPv4), 32)

            if (isCustomDNSServerUsed) {
                builder.addDnsServer(getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, bridge.prefs))
            }

            if (!bridge.currentProposedDNS.isSame(ByteArray(4))) {
                builder.addDnsServer(InetAddress.getByAddress(bridge.currentProposedDNS))
            }

            if (isDefaultRouteAdded) {
                builder.addRoute("0.0.0.0", 0)
            }

            if (isPrivateAddressesRouted) {
                builder.addRoute("10.0.0.0", 8)
                builder.addRoute("172.16.0.0", 12)
                builder.addRoute("192.168.0.0", 16)
            }
        }

        if (bridge.PPP_IPv6_ENABLED) {
            ByteArray(16).also { // for link local addresses
                "FE80".toHexByteArray().copyInto(it)
                ByteArray(6).copyInto(it, destinationOffset = 2)
                bridge.currentIPv6.copyInto(it, destinationOffset = 8)
                builder.addAddress(InetAddress.getByAddress(it), 64)
            }

            if (isDefaultRouteAdded) {
                builder.addRoute("::", 0)
            }

            if (isPrivateAddressesRouted) {
                builder.addRoute("fc00::", 7)
            }
        }

        if (isCustomRoutesAdded) {
            addCustomRoutes(builder)
        }

        if (isAppBasedRuleEnabled) {
            addAppBasedRules(builder)
        }

        builder.setMtu(bridge.PPP_MTU)
        builder.setBlocking(true)
    }

    @SuppressLint("NewApi")
    private fun addAppBasedRules(builder: VpnService.Builder) {
        val allowedPackages = bridge.allowedApps.map { it.packageName }.toMutableSet()
        bridge.allowedApps.forEach {
            builder.addAllowedApplication(it.packageName)
        }

        // Always tunnel the host application. When only selected apps are
        // routed, the host app's own traffic would otherwise bypass the tunnel,
        // so its public-IP probe would report the direct address instead of the
        // VPN exit address.
        val hostPackage = bridge.service.packageName
        if (hostPackage.isNotEmpty() && allowedPackages.add(hostPackage)) {
            try {
                builder.addAllowedApplication(hostPackage)
            } catch (_: Exception) {
                // Host package must exist, but never fail the tunnel over it.
            }
        }
    }

    private suspend fun addCustomRoutes(builder: VpnService.Builder) {
        getStringPrefValue(OscPrefKey.ROUTE_CUSTOM_ROUTES, bridge.prefs).split("\n").filter { it.isNotEmpty() }.forEach {
            val parsed = it.split("/")
            if (parsed.size != 2) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return
            }

            val address = parsed[0]
            val prefix = parsed[1].toIntOrNull()
            if (prefix == null){
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return
            }

            try {
                builder.addRoute(address, prefix)
            } catch (_: IllegalArgumentException) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return
            }
        }
    }

    private fun resetTrafficCounters() {
        totalDownloadBytes.set(0)
        totalUploadBytes.set(0)
        totalDownloadPackets.set(0)
        totalUploadPackets.set(0)
        trafficTotalDownloadBits = 0L
        trafficTotalUploadBits = 0L
    }

    /**
     * Writes the end-of-session summary (byte and packet totals) to the app log.
     * The connect path already writes the phase lines, so a matching end line is
     * what makes it possible to read how much a tunnel carried without a logcat.
     * Called from [close] before the counters are reset; guarded so repeated
     * teardown paths (kill, shutdown, service close) log it only once.
     */
    private fun logSessionEnd() {
        if (sessionEndLogged) return
        val downloadBytes = totalDownloadBytes.get()
        val uploadBytes = totalUploadBytes.get()
        if (downloadBytes == 0L && uploadBytes == 0L) return
        sessionEndLogged = true
        ConnectionLog.append(
            "PHASE",
            "Session stopped — session ended (tx=${totalUploadPackets.get()}p/${uploadBytes}B " +
                "rx=${totalDownloadPackets.get()}p/${downloadBytes}B)"
        )
    }

    /** Closes the TUN descriptor and its streams, tolerating repeated calls. */
    private fun closeDescriptor() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        try { fd?.close() } catch (_: Exception) {}
        fd = null
    }

    internal suspend fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        // nothing will be written until initialized
        // the position won't be changed
        outputStream?.write(buffer.array(), start, size)
        if (size > 0) {
            totalDownloadBytes.addAndGet(size.toLong())
            totalDownloadPackets.incrementAndGet()
        }
    }

    internal suspend fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        val bytesRead = inputStream?.read(buffer.array(), 0, bridge.PPP_MTU) ?: 0
        buffer.position(bytesRead)
        buffer.flip()

        if (bytesRead > 0) {
            totalUploadBytes.addAndGet(bytesRead.toLong())
            totalUploadPackets.incrementAndGet()
        }
    }

    /**
     * Starts a lightweight reporter that reads the byte counters once per second
     * and forwards the computed speeds to Flutter and to the foreground
     * notification. Doing this here (instead of on every single packet) keeps
     * the data path free of MethodChannel/main-thread work, which is what used
     * to cap the throughput.
     */
    internal fun startTrafficReporting() {
        if (trafficJob?.isActive == true) return

        lastReportedDownloadBytes = totalDownloadBytes.get()
        lastReportedUploadBytes = totalUploadBytes.get()
        lastReportAtMs = System.currentTimeMillis()

        trafficJob = bridge.service.scope.launch {
            while (isActive) {
                delay(trafficReportIntervalMs())

                val totalDownload = totalDownloadBytes.get()
                val totalUpload = totalUploadBytes.get()

                val downloadDelta = totalDownload - lastReportedDownloadBytes
                val uploadDelta = totalUpload - lastReportedUploadBytes
                lastReportedDownloadBytes = totalDownload
                lastReportedUploadBytes = totalUpload

                // Speeds are scaled by the interval that actually elapsed, so a
                // stretched reporter (energy saving) still reports bytes per
                // second instead of bytes per tick.
                val now = System.currentTimeMillis()
                val elapsedMs = (now - lastReportAtMs).coerceAtLeast(1L)
                lastReportAtMs = now

                // Kept in bits to preserve the existing display convention.
                // Long arithmetic avoids the 32-bit overflow that used to
                // truncate the cumulative total past ~268 MB.
                val downloadBps = downloadDelta * 8 * 1000 / elapsedMs
                val uploadBps = uploadDelta * 8 * 1000 / elapsedMs
                val totalDownloadBits = totalDownload * 8
                val totalUploadBits = totalUpload * 8

                trafficTotalDownloadBits = totalDownloadBits
                trafficTotalUploadBits = totalUploadBits

                FlutterCaller().DownloadSpeed(downloadBps, totalDownloadBits)
                FlutterCaller().UploadSpeed(uploadBps, totalUploadBits)
                bridge.service.updateTrafficNotification(downloadBps, uploadBps)
            }
        }
    }

    /**
     * Traffic report cadence. Read on every tick so toggling the energy-saving
     * switch takes effect without reconnecting.
     */
    private fun trafficReportIntervalMs(): Long =
        if (getBooleanPrefValue(OscPrefKey.POWER_SAVING, bridge.prefs)) {
            TRAFFIC_REPORT_INTERVAL_POWER_SAVE_MS
        } else {
            TRAFFIC_REPORT_INTERVAL_MS
        }

    internal fun stopTrafficReporting() {
        trafficJob?.cancel()
        trafficJob = null
    }

    internal fun close() {
        stopTrafficReporting()
        logSessionEnd()
        resetTrafficCounters()
        closeDescriptor()
    }
}
