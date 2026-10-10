package io.github.mrksey.softsstp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val appInfoChannelName = "softsstp/app_info"
    private val linksChannelName = "softsstp/links"
    private val networkChannelName = "softsstp/network"

    private var networkChannel: MethodChannel? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, appInfoChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getAppInfo" -> {
                        val packageInfo = packageManager.getPackageInfo(packageName, 0)
                        val versionName = packageInfo.versionName ?: "unknown"
                        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            packageInfo.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            packageInfo.versionCode.toLong()
                        }
                        result.success(
                            mapOf(
                                "appName" to "SoftSSTP",
                                "packageName" to packageName,
                                "versionName" to versionName,
                                "versionCode" to versionCode,
                            )
                        )
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, linksChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openUrl" -> {
                        val url = call.argument<String>("url")
                        if (url.isNullOrBlank()) {
                            result.error("INVALID_URL", "A non-empty url is required", null)
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                result.success(true)
                            } catch (e: Exception) {
                                result.error("OPEN_FAILED", e.message, null)
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        networkChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            networkChannelName,
        )
        registerNetworkCallback()
    }

    /**
     * Reports default-network changes to Flutter so the app can refresh the
     * direct ("before connection") public IP when Wi-Fi / mobile data switches,
     * without waiting for the app to be restarted.
     */
    private fun registerNetworkCallback() {
        try {
            val manager = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = notifyNetworkChanged()
                override fun onLost(network: Network) = notifyNetworkChanged()
            }
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            // Network monitoring is best-effort; never crash the app over it.
        }
    }

    private fun notifyNetworkChanged() {
        mainHandler.post {
            try {
                networkChannel?.invokeMethod("onNetworkChanged", null)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        try {
            networkCallback?.let {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {
        }
        networkCallback = null
        networkChannel = null
        super.onDestroy()
    }
}
