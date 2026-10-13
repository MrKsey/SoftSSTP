package io.github.mrksey.softsstp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val appInfoChannelName = "softsstp/app_info"
    private val linksChannelName = "softsstp/links"
    private val networkChannelName = "softsstp/network"
    private val updateChannelName = "softsstp/update"

    private var networkChannel: MethodChannel? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateExecutor = Executors.newSingleThreadExecutor()

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

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, updateChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "canRequestPackageInstalls" -> result.success(canRequestPackageInstalls())
                    "openInstallPermissionSettings" -> {
                        openInstallPermissionSettings()
                        result.success(null)
                    }
                    "downloadAndInstall" -> {
                        val version = call.argument<String>("version") ?: "update"
                        @Suppress("UNCHECKED_CAST")
                        val assets = call.argument<List<Map<String, String>>>("assets").orEmpty()
                        downloadAndInstall(version, assets, result)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** True when this app may hand APKs to the system installer. */
    private fun canRequestPackageInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system screen where the user grants "install unknown apps". */
    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * Downloads the APK for this device's ABI and opens the system installer.
     * The download runs off the main thread; the result is posted back once the
     * installer has been launched.
     */
    private fun downloadAndInstall(
        version: String,
        assets: List<Map<String, String>>,
        result: MethodChannel.Result,
    ) {
        val chosen = chooseAsset(assets)
        if (chosen == null) {
            result.error("NO_ASSET", "This release has no APK for this device", null)
            return
        }
        updateExecutor.execute {
            try {
                val dir = File(File(cacheDir, "updates"), version).apply { mkdirs() }
                val file = File(dir, chosen.first)
                download(chosen.second, file)
                mainHandler.post {
                    try {
                        installApk(file)
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("INSTALL_FAILED", e.message, null)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { result.error("DOWNLOAD_FAILED", e.message, null) }
            }
        }
    }

    /**
     * Picks the release asset that matches one of the device ABIs. Falls back to
     * the first asset when nothing matches, so a universal APK still works.
     */
    private fun chooseAsset(assets: List<Map<String, String>>): Pair<String, String>? {
        for (abi in Build.SUPPORTED_ABIS) {
            val match = assets.firstOrNull { it["name"]?.contains(abi) == true }
            if (match != null) {
                val name = match["name"]
                val url = match["url"]
                if (name != null && url != null) return name to url
            }
        }
        val first = assets.firstOrNull() ?: return null
        val name = first["name"] ?: return null
        val url = first["url"] ?: return null
        return name to url
    }

    /** Streams [url] into [target], following redirects by hand. */
    private fun download(url: String, target: File) {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SoftSSTP-Android")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without a Location header")
                    redirects++
                    if (redirects > 5) throw IllegalStateException("Too many redirects")
                    current = location
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $code")
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Hands a downloaded APK to the system installer via a content URI. */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
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
        updateExecutor.shutdown()
        super.onDestroy()
    }
}
