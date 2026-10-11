package com.example.softether_flutter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import vn.unlimit.softether.SoftEtherLog
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.AuthMethod
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.Route

/**
 * Flutter bridge for the vendored SoftEther VPN module.
 *
 * Exposes two channels:
 *  - `softether_flutter`  — commands from Dart (permission, connect, disconnect, …).
 *  - `softether_receiver` — events pushed to Dart ("connectResponse",
 *    "downloadSpeed", "uploadSpeed").
 *
 * The heavy lifting lives in [SoftEtherVpnService], which is started with a
 * Parcelable [ConnectionConfig]. State and traffic are relayed through the
 * service's in-process listeners.
 */
class SoftetherFlutterPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private lateinit var commandChannel: MethodChannel
    private lateinit var eventChannel: MethodChannel
    private lateinit var context: Context
    private var activityBinding: ActivityPluginBinding? = null
    private var activityResultListener: PluginRegistry.ActivityResultListener? = null

    private val stateListener = object : SoftEtherVpnService.StateListener {
        override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
            val isError = state == SoftEtherVpnService.STATE_ERROR
            eventChannel.invokeMethod(
                "connectResponse",
                mapOf(
                    "status" to mapState(state),
                    "ip" to assignedIp,
                    "error" to isError,
                    "errorCode" to if (isError) "SOFTETHER_ERROR" else "",
                    "errorMessage" to if (isError) "SoftEther connection failed" else "",
                )
            )
        }
    }

    private val trafficListener = object : SoftEtherVpnService.TrafficListener {
        override fun onSoftEtherTrafficUpdated(snapshot: SoftEtherTrafficSnapshot) {
            // The module counts bytes; the app's formatters expect bits, so convert.
            val interval = snapshot.intervalMs.coerceAtLeast(1L)
            val downBps = snapshot.diffInBytes * 8L * 1000L / interval
            val upBps = snapshot.diffOutBytes * 8L * 1000L / interval
            eventChannel.invokeMethod("downloadSpeed", listOf(downBps, snapshot.inBytes * 8L))
            eventChannel.invokeMethod("uploadSpeed", listOf(upBps, snapshot.outBytes * 8L))
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        SoftEtherLog.init(context)
        commandChannel = MethodChannel(binding.binaryMessenger, "softether_flutter")
        commandChannel.setMethodCallHandler(this)
        eventChannel = MethodChannel(binding.binaryMessenger, "softether_receiver")
        SoftEtherVpnService.addStateListener(stateListener)
        SoftEtherVpnService.addTrafficListener(trafficListener)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        commandChannel.setMethodCallHandler(null)
        SoftEtherVpnService.removeStateListener(stateListener)
        SoftEtherVpnService.removeTrafficListener(trafficListener)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        // Let the service open this app when the notification is tapped instead
        // of the module's hard-coded (and here missing) DetailActivity.
        SoftEtherVpnService.notificationTargetActivity = binding.activity.javaClass
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
        SoftEtherVpnService.notificationTargetActivity = binding.activity.javaClass
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "takePermission" -> takePermission(result)
            "connect" -> {
                val config = buildConfig(call)
                startVpnService(SoftEtherVpnService.ACTION_CONNECT, config)
                result.success(true)
            }
            "disconnect" -> {
                startVpnService(SoftEtherVpnService.ACTION_DISCONNECT, null)
                result.success(true)
            }
            "checkLastConnectionStatus" -> result.success(mapState(SoftEtherVpnService.currentState))
            "getAssignedIp" -> result.success(SoftEtherVpnService.currentAssignedIp)
            "getTrafficTotals" -> {
                val snapshot = SoftEtherVpnService.lastTrafficSnapshot
                result.success(listOf(snapshot.inBytes * 8L, snapshot.outBytes * 8L))
            }
            "setConnectionLogging" -> {
                SoftEtherLog.enabled = call.argument<Boolean>("enabled") ?: false
                SoftEtherLog.append(
                    "SERVICE",
                    if (SoftEtherLog.enabled) "Connection logging enabled" else "Connection logging disabled"
                )
                result.success(true)
            }
            "getConnectionLog" -> result.success(SoftEtherLog.read())
            "getConnectionLogPath" -> result.success(SoftEtherLog.filePath())
            "clearConnectionLog" -> {
                SoftEtherLog.clear()
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    /**
     * Claims the VPN slot. Returns true when the app is already prepared, or the
     * outcome of the system consent dialog otherwise.
     */
    private fun takePermission(result: MethodChannel.Result) {
        val binding = activityBinding
        val intent = VpnService.prepare(context)
        if (intent == null) {
            result.success(true)
            return
        }
        if (binding == null) {
            result.success(false)
            return
        }
        activityResultListener?.let { binding.removeActivityResultListener(it) }
        val listener = PluginRegistry.ActivityResultListener { requestCode, resultCode, _ ->
            result.success(requestCode == REQUEST_VPN_PERMISSION && resultCode == Activity.RESULT_OK)
            activityResultListener?.let { binding.removeActivityResultListener(it) }
            activityResultListener = null
            true
        }
        activityResultListener = listener
        binding.addActivityResultListener(listener)
        binding.activity.startActivityForResult(intent, REQUEST_VPN_PERMISSION)
    }

    private fun buildConfig(call: MethodCall): ConnectionConfig {
        val username = call.argument<String>("username") ?: ""
        val addDefaultRoute = call.argument<Boolean>("addDefaultRoute") ?: true
        val routes = if (addDefaultRoute) listOf(Route("0.0.0.0", 0)) else emptyList()
        // SoftEther session options (Windows-client equivalents).
        val disableUdp = call.argument<Boolean>("disableUdpAcceleration") ?: true
        val duplexMode = call.argument<String>("duplexMode") ?: "auto"
        val fullDuplex = when (duplexMode) {
            "half" -> false
            "full" -> true
            else -> null
        }
        return ConnectionConfig(
            serverHost = call.argument<String>("host") ?: "",
            serverPort = call.argument<Int>("port") ?: 443,
            username = username,
            password = call.argument<String>("password") ?: "",
            virtualHub = call.argument<String>("hub")?.takeIf { it.isNotBlank() } ?: "VPN",
            sessionName = call.argument<String>("sessionName")?.takeIf { it.isNotBlank() }
                ?: "SoftEther VPN",
            dnsServer = call.argument<String>("dns")?.takeIf { it.isNotBlank() } ?: "8.8.8.8",
            mtu = call.argument<Int>("mtu") ?: 1500,
            routes = routes,
            excludedApps = call.argument<ArrayList<String>>("excludedApps") ?: emptyList(),
            allowedApps = call.argument<ArrayList<String>>("allowedApps") ?: emptyList(),
            useTcp = true,
            useUdp = !disableUdp,
            udpPort = call.argument<Int>("udpPort") ?: 0,
            connectTimeoutMs = call.argument<Int>("connectTimeoutMs") ?: 30000,
            authMethod = if (username.isEmpty()) AuthMethod.ANONYMOUS else AuthMethod.AUTO,
            clientMac = call.argument<String>("clientMac") ?: "",
            maxConnections = call.argument<Int>("maxConnections") ?: 4,
            establishIntervalMs = call.argument<Int>("establishIntervalMs") ?: 1000,
            useEncrypt = call.argument<Boolean>("useEncrypt") ?: true,
            useCompress = call.argument<Boolean>("useCompress") ?: false,
            qos = call.argument<Boolean>("qos") ?: true,
            fullDuplex = fullDuplex,
        )
    }

    private fun startVpnService(action: String, config: ConnectionConfig?) {
        val intent = Intent(context, SoftEtherVpnService::class.java).setAction(action)
        if (config != null) {
            intent.putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Maps the module's internal state to the status strings the app expects. */
    private fun mapState(state: String): String = when (state) {
        SoftEtherVpnService.STATE_CONNECTED -> "Connected"
        SoftEtherVpnService.STATE_CONNECTING,
        SoftEtherVpnService.STATE_TLS_HANDSHAKE,
        SoftEtherVpnService.STATE_PROTOCOL_HANDSHAKE,
        SoftEtherVpnService.STATE_AUTHENTICATING,
        SoftEtherVpnService.STATE_SESSION_SETUP -> "Connecting"
        SoftEtherVpnService.STATE_DISCONNECTING -> "Disconnecting"
        else -> "Disconnected"
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 0x5E01
    }
}
