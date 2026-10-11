package com.example.sstp_flutter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager
import com.example.sstp_flutter.preference.OscPrefKey
import com.example.sstp_flutter.preference.accessor.getBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.getSetPrefValue
import com.example.sstp_flutter.preference.accessor.getURIPrefValue
import com.example.sstp_flutter.preference.accessor.setBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.setIntPrefValue
import com.example.sstp_flutter.preference.accessor.setSetPrefValue
import com.example.sstp_flutter.preference.accessor.setStringPrefValue
import com.example.sstp_flutter.preference.accessor.setURIPrefValue
import com.example.sstp_flutter.preference.custom.DirectoryPreference
import com.example.sstp_flutter.service.ACTION_VPN_CONNECT
import com.example.sstp_flutter.service.ACTION_VPN_DISCONNECT
import com.example.sstp_flutter.service.SstpVpnService
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

lateinit var flutterChannel : MethodChannel
var dnsEnabled : Boolean = false
var dnsCount : Int = 0
var connectionStatus : String = OscPrefKey.Disconnected.name
var connectionStartTime : Long = 0L
var manuallyDisconnected : Boolean = false
var errorConnection : Boolean = false
var assignedIp = ""

/**
 * Cumulative traffic counters (in bits, matching the existing speed convention)
 * mirrored from the active [com.example.sstp_flutter.terminal.IPTerminal]. The
 * app can pull these on resume, so the total is never lost even if the
 * per-second push notifications were dropped while it was in the background.
 */
var trafficTotalDownloadBits : Long = 0L
var trafficTotalUploadBits : Long = 0L

/**
 * Machine-readable reason and human readable explanation for the last failed
 * connection attempt. Pushed to Flutter inside "connectResponse" so the app can
 * show a useful hint instead of a raw stack trace.
 */
var connectionErrorCode : String = ""
var connectionErrorMessage : String = ""

/**
 * Set once FlutterCaller.connectionFailed() has delivered a failure to the app.
 * The service also reports the final disconnected state when it stops; without
 * this flag that second event carried the same error again, so the app handled a
 * single failure twice (which made the Auto negotiation skip a candidate).
 */
var connectionErrorAlreadySent : Boolean = false

/** SstpFlutterPlugin by Navid Shokoufeh*/
class SstpFlutterPlugin: FlutterPlugin, MethodCallHandler , ActivityAware, FlutterFragmentActivity() {
  var activityResultListener: PluginRegistry.ActivityResultListener? = null
  private lateinit var activityBinding: ActivityPluginBinding
  private lateinit var channel : MethodChannel
  lateinit var prefs : SharedPreferences
  lateinit var context : Context
  lateinit var  tempResult : Result

  private val certDirLauncher = PluginRegistry.ActivityResultListener { req, res, data ->

    val uri = if (res == Activity.RESULT_OK) data?.data?.also {
      context.contentResolver.takePersistableUriPermission(
        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
      )
    } else null

    setURIPrefValue(uri, OscPrefKey.SSL_CERT_DIR, prefs)
    Thread{
      tempResult.success(getURIPrefValue(OscPrefKey.SSL_CERT_DIR, prefs).toString())
    }.start()
    true
  }


  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "sstp_flutter")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
    prefs = PreferenceManager.getDefaultSharedPreferences(flutterPluginBinding.applicationContext)
    flutterChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "responseReceiver")
    // Initialize the shared log file eagerly so the log screen can read it even
    // if the SSTP service has not run yet (the SoftEther plugin writes to the
    // same file).
    ConnectionLog.init(flutterPluginBinding.applicationContext)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activityBinding = binding

  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    tempResult = result
    when {
      call.method.equals("takePermission") -> {
        val intent = VpnService.prepare(activityBinding.activity.applicationContext)

        if(intent != null){

          activityResultListener = PluginRegistry.ActivityResultListener { req, res, _ ->
            result.success(req == 0 && res == RESULT_OK)
            activityResultListener?.let { activityBinding.removeActivityResultListener(it) }
            true
          }
          activityBinding.addActivityResultListener(activityResultListener!!)
          activityBinding.activity.startActivityForResult(intent, 0)
        }else{
          result.success(true)
        }
      }

      call.method.equals("connect") -> {
        Thread {
          startVpnService(ACTION_VPN_CONNECT)
          result.success(true)
        }.start()
      }

      call.method.equals("disconnect") -> {
        Thread{
          startVpnService(ACTION_VPN_DISCONNECT)
          result.success(true);
        }.start()
      }

      call.method.equals("getApps") -> {
        Thread {
          val apps: List<Map<String, Any?>> =
            getInstalledApps()
          result.success(apps)
        }.start()
      }

      call.method.equals("addAllowedApps") -> {
        println("adding allowed apps")
        Thread {
          val status =
            call.argument<ArrayList<String>>("packageName")?.let { memorizeAllowedApps(it) }
          result.success(status)
        }.start()

      }

      call.method.equals("getAllowedApps") ->{
        Thread {
          val apps : List<String> =
            getAllowedApps()
          result.success(apps)
        }.start()

      }

      // Stores the allowlist independently of the "enabled" flag, so the
      // selection survives toggling app-based routing off and back on.
      call.method.equals("setAppBasedRule") -> {
        Thread {
          val enabled = call.argument<Boolean>("enabled") ?: false
          val packages = call.argument<ArrayList<String>>("packageName") ?: arrayListOf()
          result.success(memorizeAppBasedRule(enabled, packages))
        }.start()
      }

      call.method.equals("getAppBasedRuleEnabled") -> {
        Thread {
          result.success(getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs))
        }.start()
      }

      call.method.equals("enableDns") -> {
        val dns = call.argument<String>("customDns")
        setBooleanPrefValue(true,OscPrefKey.DNS_DO_USE_CUSTOM_SERVER,prefs)
        if (dns != null) {
          setStringPrefValue(dns,OscPrefKey.DNS_CUSTOM_ADDRESS,prefs)
        }
        dnsEnabled = true
        dnsCount++
        result.success(true)
      }

      call.method.equals("disableDns") -> {
        setBooleanPrefValue(false,OscPrefKey.DNS_DO_USE_CUSTOM_SERVER,prefs)
        setStringPrefValue("",OscPrefKey.DNS_CUSTOM_ADDRESS,prefs)
        dnsEnabled = false
        dnsCount = 0
        result.success(true)
      }

      call.method.equals("enableProxy") -> {
        val proxyIp = call.argument<String>("proxyIp")
        val proxyPort = call.argument<String>("proxyPort")
        val proxyUserName = call.argument<String>("proxyUserName")
        val proxyPassword = call.argument<String>("proxyPassword")

        setBooleanPrefValue(true,OscPrefKey.PROXY_DO_USE_PROXY,prefs)

        if (proxyIp != null) {
          setStringPrefValue(proxyIp,OscPrefKey.PROXY_HOSTNAME,prefs)
        }
        if (proxyPort != null) {
          setStringPrefValue(proxyPort,OscPrefKey.PROXY_PORT,prefs)
        }
        if (proxyUserName != null) {
          setStringPrefValue(proxyUserName,OscPrefKey.PROXY_USERNAME,prefs)
        }
        if (proxyPassword != null) {
          setStringPrefValue(proxyPassword,OscPrefKey.PROXY_PASSWORD,prefs)
        }
      }

      call.method.equals("disableProxy") -> {
        setBooleanPrefValue(false,OscPrefKey.PROXY_DO_USE_PROXY,prefs)
        setStringPrefValue("",OscPrefKey.PROXY_HOSTNAME,prefs)
        setStringPrefValue("",OscPrefKey.PROXY_PORT,prefs)
        setStringPrefValue("",OscPrefKey.PROXY_USERNAME,prefs)
        setStringPrefValue("",OscPrefKey.PROXY_PASSWORD,prefs)
      }

      call.method.equals("checkLastConnectionStatus") -> {
        Thread {
          result.success(connectionStatus)
        }.start()
      }

      call.method.equals("getConnectionStartTime") -> {
        result.success(connectionStartTime)
      }

      call.method.equals("getAssignedIp") -> {
        result.success(assignedIp)
      }

      call.method.equals("getTrafficTotals") -> {
        // [downloadBits, uploadBits] accumulated by the native traffic reporter.
        result.success(listOf(trafficTotalDownloadBits, trafficTotalUploadBits))
      }


      call.method.equals("saveServer") -> {
        Thread {
          saveData(call)
          result.success(true)
        }.start()
      }

      call.method.equals("addTrustedCertificate") -> {
        addTrustedCertificate()
      }

      call.method.equals("setConnectionLogging") -> {
        ConnectionLog.enabled = call.argument<Boolean>("enabled") ?: false
        ConnectionLog.append(
          "LOG",
          if (ConnectionLog.enabled) "Connection logging enabled" else "Connection logging disabled"
        )
        result.success(true)
      }

      call.method.equals("getConnectionLog") -> {
        result.success(ConnectionLog.read())
      }

      call.method.equals("clearConnectionLog") -> {
        ConnectionLog.clear()
        result.success(true)
      }

      call.method.equals("getConnectionLogPath") -> {
        result.success(ConnectionLog.filePath())
      }

      // Battery optimization. Without an exemption the system may stop the
      // service (and the tunnel) while the app is in the background.
      call.method.equals("isIgnoringBatteryOptimizations") -> {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        result.success(powerManager.isIgnoringBatteryOptimizations(context.packageName))
      }

      call.method.equals("requestIgnoreBatteryOptimizations") -> {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
          result.success(true)
        } else {
          result.success(openBatteryOptimizationSettings())
        }
      }
    }
  }

  /**
   * Asks the system to exempt SoftSSTP from battery optimization, so Android does
   * not stop the tunnel while the screen is off.
   *
   * Falls back to the optimization list when the direct request dialog is not
   * available (some OEM builds reject the intent).
   */
  private fun openBatteryOptimizationSettings(): Boolean {
    if (!::activityBinding.isInitialized) return false
    val activity = activityBinding.activity
    return try {
      val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
      activity.startActivity(direct)
      true
    } catch (e: Exception) {
      try {
        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        true
      } catch (e2: Exception) {
        false
      }
    }
  }

  fun addTrustedCertificate(): String {

    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also { intent ->
      intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
      activityBinding.addActivityResultListener(certDirLauncher)
      activityBinding.activity.startActivityForResult(intent, 0).also {
        return getURIPrefValue(OscPrefKey.SSL_CERT_DIR, prefs).toString()
      }
    }

  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private fun saveData(call: MethodCall) {
    val hostName = call.argument<String>("hostName")
    val userName = call.argument<String>("userName")
    val password = call.argument<String>("password")
    val sslPort = call.argument<Int>("sslPort")
    val verifyHostName = call.argument<Boolean>("verifyHostName")
    val verifySSLCert = call.argument<Boolean>("verifySSLCert")
    val useTrustedCert = call.argument<Boolean>("useTrustedCert")
    val sslVersion = call.argument<String>("sslVersion")
    val useCustomSni = call.argument<Boolean>("useCustomSni")
    val customSni = call.argument<String>("customSni")
    val showDisconnectOnNotification = call.argument<Boolean>("showDisconnectOnNotification")
    val notificationText = call.argument<String>("notificationText")

    // PPP settings
    val pppMru = call.argument<Int>("pppMru")
    val pppMtu = call.argument<Int>("pppMtu")
    val pppAuthProtocol = call.argument<String>("pppAuthProtocol")
    val pppAuthTimeout = call.argument<Int>("pppAuthTimeout")
    val pppIpv4Enabled = call.argument<Boolean>("pppIpv4Enabled")
    val pppIpv6Enabled = call.argument<Boolean>("pppIpv6Enabled")
    val requestStaticIp = call.argument<Boolean>("requestStaticIp")
    val staticIp = call.argument<String>("staticIp")

    // Routing settings
    val addDefaultRoute = call.argument<Boolean>("addDefaultRoute")
    val routePrivateAddresses = call.argument<Boolean>("routePrivateAddresses")

    // DNS
    val requestDns = call.argument<Boolean>("requestDns")

    // Reconnection settings
    val reconnectionEnabled = call.argument<Boolean>("reconnectionEnabled")
    val reconnectionCount = call.argument<Int>("reconnectionCount")
    val reconnectionInterval = call.argument<Int>("reconnectionInterval")

    setStringPrefValue(
      hostName ?:"", OscPrefKey.HOME_HOSTNAME,prefs
    )
    setStringPrefValue(
      userName ?:"", OscPrefKey.HOME_USERNAME,prefs
    )
    setStringPrefValue(
      password ?:"", OscPrefKey.HOME_PASSWORD,prefs
    )
    setIntPrefValue(
      sslPort ?: 443, OscPrefKey.SSL_PORT,prefs
    )

    setBooleanPrefValue(showDisconnectOnNotification ?: false,OscPrefKey.NOTIFICATION_DO_SHOW_DISCONNECT,prefs)
    setBooleanPrefValue(useTrustedCert ?: false,OscPrefKey.SSL_DO_ADD_CERT,prefs)

    setStringPrefValue(
      sslVersion ?:"", OscPrefKey.SSL_VERSION,prefs
    )

    setBooleanPrefValue(useCustomSni == true, OscPrefKey.SSL_DO_USE_CUSTOM_SNI, prefs)
    setStringPrefValue(customSni ?:"", OscPrefKey.SSL_CUSTOM_SNI, prefs)

    if(!notificationText.isNullOrEmpty()){
      setStringPrefValue(
        notificationText , OscPrefKey.NotificationText,prefs
      )
    }else{
      setStringPrefValue(
        "", OscPrefKey.NotificationText,prefs
      )
    }

    setBooleanPrefValue(verifyHostName == true,OscPrefKey.SSL_DO_VERIFY,prefs)
    setBooleanPrefValue(verifySSLCert == true,OscPrefKey.SSL_DO_VERIFY_CERT,prefs)

    // PPP
    pppMru?.let { setIntPrefValue(it, OscPrefKey.PPP_MRU, prefs) }
    pppMtu?.let { setIntPrefValue(it, OscPrefKey.PPP_MTU, prefs) }
    if (pppAuthProtocol != null) {
      val usePap = pppAuthProtocol.equals("PAP", ignoreCase = true)
      setBooleanPrefValue(usePap, OscPrefKey.PPP_PAP_ENABLED, prefs)
      setBooleanPrefValue(!usePap, OscPrefKey.PPP_MSCHAPv2_ENABLED, prefs)
    }
    pppAuthTimeout?.let { setIntPrefValue(it, OscPrefKey.PPP_AUTH_TIMEOUT, prefs) }
    pppIpv4Enabled?.let { setBooleanPrefValue(it, OscPrefKey.PPP_IPv4_ENABLED, prefs) }
    pppIpv6Enabled?.let { setBooleanPrefValue(it, OscPrefKey.PPP_IPv6_ENABLED, prefs) }
    requestStaticIp?.let { setBooleanPrefValue(it, OscPrefKey.PPP_DO_REQUEST_STATIC_IPv4_ADDRESS, prefs) }
    if (staticIp != null) {
      setStringPrefValue(staticIp, OscPrefKey.PPP_STATIC_IPv4_ADDRESS, prefs)
    }

    // Routing
    addDefaultRoute?.let { setBooleanPrefValue(it, OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE, prefs) }
    routePrivateAddresses?.let { setBooleanPrefValue(it, OscPrefKey.ROUTE_DO_ROUTE_PRIVATE_ADDRESSES, prefs) }

    // DNS
    requestDns?.let { setBooleanPrefValue(it, OscPrefKey.DNS_DO_REQUEST_ADDRESS, prefs) }

    // Reconnection. RECONNECTION_LIFE is intentionally not set here: it is an
    // internal counter (attempts left) that the service initializes from
    // RECONNECTION_COUNT on every connection.
    reconnectionEnabled?.let { setBooleanPrefValue(it, OscPrefKey.RECONNECTION_ENABLED, prefs) }
    reconnectionCount?.let { setIntPrefValue(it, OscPrefKey.RECONNECTION_COUNT, prefs) }
    reconnectionInterval?.let { setIntPrefValue(it, OscPrefKey.RECONNECTION_INTERVAL, prefs) }
  }

  private fun startVpnService(
    action: String,
  ) {


    if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val intent = Intent(context, SstpVpnService::class.java).setAction(action).putExtra("manuallyDisconnected",false)
      context.startForegroundService(intent)
    } else {
      val intent = Intent(context, SstpVpnService::class.java).setAction(action).putExtra("manuallyDisconnected",true)
      context.startService(intent)
    }
  }

//  private fun takePermission(
//    call: MethodCall,
//    connect: Boolean,
//  ) : Boolean {
//    VpnService.prepare(context)?.also { intent ->
//      preparationLauncher.launch(intent)
//    } ?:
//    if(connect){
//      FlutterCaller().connecting()
//      saveData(call)
//      startVpnService(ACTION_VPN_CONNECT)
//    }
//
//    return localPermission
//  }

  private fun getInstalledApps(): List<Map<String, Any?>> {
    val packageManager = context.packageManager
    var installedApps = packageManager.getInstalledApplications(0)
    installedApps =
      installedApps.filter { app -> !isSystemApp(packageManager, app.packageName) }
    return installedApps.map { app -> Util.convertAppToMap(packageManager, app, true) }
  }

  private fun isSystemApp(packageManager: PackageManager, packageName: String): Boolean {
    return packageManager.getLaunchIntentForPackage(packageName) == null
  }

  private fun memorizeAllowedApps(packageName: ArrayList<String>): Boolean {
    val allowed = mutableSetOf<String>()
    for(element in packageName){
      allowed.add(element)
    }
    if(allowed.size >0){
      setBooleanPrefValue(true,OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs)
    }else{
      setBooleanPrefValue(false,OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs)
    }
    setSetPrefValue(allowed, OscPrefKey.ROUTE_ALLOWED_APPS, prefs)
    return true
  }

  /**
   * Persists the app-based routing state. Unlike [memorizeAllowedApps] the
   * enabled flag and the package list are independent: turning the rule off
   * keeps the previous selection so it can be restored later.
   */
  private fun memorizeAppBasedRule(enabled: Boolean, packageName: ArrayList<String>): Boolean {
    val allowed = packageName.toMutableSet()
    setBooleanPrefValue(enabled, OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs)
    setSetPrefValue(allowed, OscPrefKey.ROUTE_ALLOWED_APPS, prefs)
    return true
  }

  private  fun getAllowedApps(): List<String> {
    val allowed = getSetPrefValue(OscPrefKey.ROUTE_ALLOWED_APPS, prefs)
    return allowed.toList()
  }

  override fun onDestroy() {
    super.onDestroy()
  }

  override fun onDetachedFromActivityForConfigChanges() {
    // no-op: the plugin can survive configuration changes
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activityBinding = binding
  }

  override fun onDetachedFromActivity() {
    // no-op: the VPN service runs independently of the activity
  }
}
