package com.example.sstp_flutter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.example.sstp_flutter.ConnectionLog
import com.example.sstp_flutter.FlutterCaller
import com.example.sstp_flutter.R
import com.example.sstp_flutter.client.ClientBridge
import com.example.sstp_flutter.client.control.ControlClient
import com.example.sstp_flutter.client.control.LogWriter
import com.example.sstp_flutter.client.control.NetworkChangeObserver
import com.example.sstp_flutter.connectionStartTime
import com.example.sstp_flutter.connectionStatus
import com.example.sstp_flutter.connectionErrorCode
import com.example.sstp_flutter.connectionErrorAlreadySent
import com.example.sstp_flutter.connectionErrorMessage
import com.example.sstp_flutter.dnsCount
import com.example.sstp_flutter.dnsEnabled
import com.example.sstp_flutter.errorConnection
import com.example.sstp_flutter.flutterChannel
import com.example.sstp_flutter.manuallyDisconnected
import com.example.sstp_flutter.preference.OscPrefKey
import com.example.sstp_flutter.preference.accessor.getBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.getIntPrefValue
import com.example.sstp_flutter.preference.accessor.getStringPrefValue
import com.example.sstp_flutter.preference.accessor.getURIPrefValue
import com.example.sstp_flutter.preference.accessor.resetReconnectionLife
import com.example.sstp_flutter.preference.accessor.setBooleanPrefValue
import com.example.sstp_flutter.preference.accessor.setIntPrefValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


internal const val ACTION_VPN_CONNECT = "com.example.sstp_flutter.connect"
internal const val ACTION_VPN_DISCONNECT = "com.example.sstp_flutter.disconnect"
// Sent by the service to itself through AlarmManager while the screen is off, so
// the CPU wakes up long enough for the keepalive/echo to go out even on an
// otherwise idle tunnel.
internal const val ACTION_VPN_WAKEUP = "com.example.sstp_flutter.wakeup"

internal const val NOTIFICATION_CHANNEL_NAME = "com.example.sstp_flutter.notification.channel"

internal const val NOTIFICATION_ERROR_ID = 1
internal const val NOTIFICATION_RECONNECT_ID = 2
internal const val NOTIFICATION_DISCONNECT_ID = 3

// Human-readable states shown in the foreground notification, matching the
// labels used on the app's home screen.
internal const val CONNECTION_STATUS_CONNECTING = "Connecting..."
internal const val CONNECTION_STATUS_CONNECTED = "Connected"
internal const val CONNECTION_STATUS_DISCONNECTED = "Disconnected"
internal const val CONNECTION_STATUS_TAKEN_OVER = "Another VPN took over"
internal const val CONNECTION_STATUS_NETWORK_CHANGED = "Network changed"

// When the underlying network changes (Wi-Fi <-> mobile data) the SSTP socket
// breaks. The service rebuilds the tunnel on the new network instead of staying
// down. A switch produces a burst of connectivity callbacks, so the rebuild is
// debounced, and it is bounded so a flapping network cannot loop forever.
private const val MAX_NETWORK_RECONNECT_ATTEMPTS = 3
private const val NETWORK_CHANGE_DEBOUNCE_MS = 1_500L
// Window after a successful connect during which underlying-network changes are
// ignored: the TUN setup/teardown itself reshapes the networks and must not be
// mistaken for a real switch (see onUnderlyingNetworkChanged).
private const val NETWORK_SETTLE_MS = 10_000L

// When another VPN (e.g. AdGuard) grabs the VPN slot, the system calls
// onRevoke(). AdGuard keeps a watchdog that re-prepares itself a few seconds
// after losing the slot, so reclaiming immediately only starts a race. Instead
// we wait for the competing VPN to finish starting up, let it settle, and only
// then reclaim the slot — mirroring the user-visible "start ours after the
// other one is up" behaviour. Bounded, so an always-on VPN cannot cause an
// endless fight.
private const val MAX_REVOKE_RECOVERY_ATTEMPTS = 5
// How long to wait for the competing VPN to appear before reclaiming anyway.
private const val WAIT_FOR_OTHER_VPN_TIMEOUT_MS = 20_000L
// Extra pause after the competing VPN is detected, so its own startup (and the
// initial handshake of its watchdog) finishes before we dethrone it.
private const val OTHER_VPN_SETTLE_DELAY_MS = 2_000L
private const val VPN_APPEAR_POLL_INTERVAL_MS = 500L

// Screen off: instead of holding a partial wake lock for the whole session (the
// CPU then never suspends, which is the biggest single battery drain of a tunnel
// left connected), the lock is released and taken back only when it is needed:
//   * a short lock renewed while traffic actually flows, so a download with the
//     screen off is not throttled;
//   * a periodic alarm that wakes the CPU long enough for the SSTP keepalive and
//     the PPP echo to go out on an otherwise idle tunnel.
// The alarm interval is deliberately shorter than the 20 s echo interval.
private const val SCREEN_OFF_WAKEUP_INTERVAL_MS = 15_000L
private const val SCREEN_OFF_WAKE_LOCK_MS = 8_000L
// Data path: how often the short wake lock is renewed while bytes are moving,
// and how long it is held after the last packet. Renewing is throttled so a
// busy tunnel does not touch the main handler for every packet.
private const val DATA_ACTIVITY_REFRESH_MS = 1_000L
private const val DATA_ACTIVITY_WAKE_LOCK_MS = 10_000L
// Request code of the screen-off wake-up alarm's PendingIntent.
private const val WAKEUP_REQUEST_CODE = 42

internal class SstpVpnService : VpnService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var notificationManager: NotificationManagerCompat
    internal lateinit var scope: CoroutineScope

    internal var logWriter: LogWriter? = null
    private var controlClient: ControlClient?  = null

    private var jobReconnect: Job? = null

    // Pending slot-reclaim work started from onRevoke(). Cancelled when the user
    // connects or disconnects, so it can never resurrect a stopped tunnel.
    private var jobRevokeRecovery: Job? = null

    // Watches the underlying (non-VPN) networks so the tunnel can be rebuilt
    // after a Wi-Fi <-> mobile data switch.
    private var networkChangeObserver: NetworkChangeObserver? = null
    private var jobNetworkReconnect: Job? = null
    private var networkReconnectAttempts = 0
    // Monotonic time of the last successful connect, used for the settle window.
    @Volatile
    private var lastEstablishedAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundBuilder: NotificationCompat.Builder? = null

    // Mirrors the connection state shown on the app's home screen. Kept here so
    // the foreground notification always reflects the real state even when the
    // Flutter UI is not running.
    @Volatile
    private var notificationStatus: String = "Connecting..."
    @Volatile
    private var notificationSpeed: String = ""
    private var notificationBaseText: String = "SoftSSTP VPN"

    // Number of times we have reclaimed the slot after being revoked by another
    // VPN. Reset on every user-initiated connect/disconnect.
    private var revokeRecoveryAttempts = 0

    // Held for the whole lifetime of a tunnel. Without it the CPU is free to
    // suspend while the screen is off, which stalls the SSTP keepalive and the
    // PPP timers until the server drops the session.
    private var wakeLock: PowerManager.WakeLock? = null

    // Screen state. The tunnel itself does not depend on it, but everything that
    // only exists for the user interface does: the traffic reporter, the
    // foreground notification refresh and the short socket read timeout are all
    // suspended while the screen is off (as shadowsocks-android does) and woken
    // up again when it comes back.
    private var screenStateMonitor: ScreenStateMonitor? = null

    /** True while the device screen is on. Read by the terminals. */
    @Volatile
    internal var isScreenInteractive: Boolean = true

    // Screen-off wake-up machinery: the periodic alarm plus the short wake lock
    // it (and the data path) takes.
    private var wakeUpAlarm: android.app.AlarmManager? = null
    private var wakeUpPendingIntent: PendingIntent? = null
    private val releaseIdleWakeLock = Runnable { releaseWakeLockIfScreenOff() }
    private var lastDataActivityMs = 0L

    private fun setRootState(state: Boolean) {
        setBooleanPrefValue(state, OscPrefKey.ROOT_STATE, prefs)
    }

    private fun requestTileListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TileService.requestListeningState(this,
                ComponentName(this, SstpTileService::class.java)
            )
        }
    }

    /**
     * Keeps the CPU awake for the duration of the tunnel. A partial wake lock is
     * enough: the screen and radio stay under system control, only the CPU is
     * kept from suspending so the SSTP keepalive and the PPP timers keep running
     * while the screen is off.
     */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SoftSSTP::VpnWakeLock"
            ).apply { setReferenceCounted(false) }.also { wakeLock = it }
        }
        try {
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            ConnectionLog.append("ERROR", "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onCreate() {
        notificationManager = NotificationManagerCompat.from(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        ConnectionLog.init(this)
        ConnectionLog.append("SERVICE", "Service created")

        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OscPrefKey.ROOT_STATE.name) {
                val newState = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
                if(newState){
//                    FlutterCaller().connecting()
                }else{
                    // The failure may already have been delivered by
                    // FlutterCaller.connectionFailed(). Repeating it here made the
                    // app handle one failed attempt twice, which advanced the Auto
                    // negotiation list by two steps (TLS 1.3 -> 1.1).
                    if (!connectionErrorAlreadySent) {
                        val tempMap : HashMap<String,Any>  = HashMap()
                        connectionStatus = OscPrefKey.Disconnected.name
                        tempMap.put("status",connectionStatus)
                        tempMap.put("dns", dnsEnabled)
                        tempMap.put("dnsCount", dnsCount)
                        tempMap.put("error", errorConnection)
                        tempMap.put("errorCode", connectionErrorCode)
                        tempMap.put("errorMessage", connectionErrorMessage)

                        flutterChannel.invokeMethod("connectResponse", tempMap)
                    }
                    errorConnection = false
                    connectionErrorAlreadySent = false
                }
                println("listener onCreate sstp service $newState")
                setBooleanPrefValue(newState, OscPrefKey.HOME_CONNECTOR, prefs)
                requestTileListening()
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Follow the underlying network for the whole lifetime of the service.
        networkChangeObserver = NetworkChangeObserver(this) { reason ->
            onUnderlyingNetworkChanged(reason)
        }.also { it.start() }

        // Track the screen so the UI-only work can stop while it is off.
        screenStateMonitor = ScreenStateMonitor(this) { interactive ->
            onScreenStateChanged(interactive)
        }.also { it.start() }
        isScreenInteractive = screenStateMonitor?.isInteractive ?: true
    }

    /**
     * Called when the screen turns on or off. The tunnel keeps running either
     * way; only the periodic UI work is suspended, so an idle tunnel with the
     * screen off does not wake the CPU once per second.
     */
    private fun onScreenStateChanged(interactive: Boolean) {
        isScreenInteractive = interactive
        ConnectionLog.append(
            "SERVICE",
            if (interactive) "Screen on — resuming UI updates" else "Screen off — suspending UI updates"
        )
        // Wake the traffic reporter so the notification and the app get the
        // current figures the moment the screen comes back, and stretch/relax
        // the socket read timeout for the new state.
        controlClient?.onScreenStateChanged(interactive)

        if (interactive) {
            // The tunnel is visible again: keep the CPU up and stop the periodic
            // wake-ups.
            cancelScreenOffWakeUps()
            mainHandler.removeCallbacks(releaseIdleWakeLock)
            if (controlClient != null) acquireWakeLock()
        } else {
            // Let the CPU suspend. The data path renews a short wake lock while
            // bytes are moving and the periodic alarm wakes it for the keepalive.
            scheduleScreenOffWakeUps()
            mainHandler.removeCallbacks(releaseIdleWakeLock)
            releaseWakeLock()
        }
    }

    /**
     * Keeps a short wake lock alive while the screen is off and bytes are moving,
     * so an active transfer is not throttled by the CPU suspending. Called from
     * the data path once per packet; the renewal is throttled so the main handler
     * stays out of the per-packet path.
     */
    internal fun noteDataActivity() {
        if (isScreenInteractive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDataActivityMs < DATA_ACTIVITY_REFRESH_MS) return
        lastDataActivityMs = now
        acquireWakeLock()
        mainHandler.removeCallbacks(releaseIdleWakeLock)
        mainHandler.postDelayed(releaseIdleWakeLock, DATA_ACTIVITY_WAKE_LOCK_MS)
    }

    private fun releaseWakeLockIfScreenOff() {
        if (isScreenInteractive) return
        releaseWakeLock()
    }

    /**
     * Arms the periodic screen-off wake-up. The alarm is one-shot (a repeating
     * one is deferred while the device is idle), so it is re-armed every time it
     * fires as long as the screen stays off.
     */
    private fun scheduleScreenOffWakeUps() {
        val manager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        wakeUpAlarm = manager
        val intent = Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_WAKEUP)
        val pending = PendingIntent.getService(
            this,
            WAKEUP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        wakeUpPendingIntent = pending
        try {
            manager.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + SCREEN_OFF_WAKEUP_INTERVAL_MS,
                pending
            )
        } catch (e: Exception) {
            ConnectionLog.append("ERROR", "Could not schedule the screen-off wake-up: ${e.message}")
        }
    }

    private fun cancelScreenOffWakeUps() {
        val pending = wakeUpPendingIntent
        if (pending != null) {
            val manager =
                wakeUpAlarm ?: getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            try {
                manager?.cancel(pending)
            } catch (_: Exception) {
            }
        }
        wakeUpPendingIntent = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted our START_STICKY service;
        // that is a reconnect, not a user-initiated disconnect.
        val action = intent?.action ?: ACTION_VPN_CONNECT

        return when (action) {
            ACTION_VPN_CONNECT -> {
                // Every connect request clears the manual-disconnect flag.
                // Previously it was only cleared when the extra was true (which
                // never happens — the plugin always sends false), so the flag
                // stayed set after the first disconnect. onRevoke() then took the
                // "manual disconnect" branch and called super.onRevoke(), whose
                // default implementation is stopSelf(), killing the service and
                // the slot-reclaim coroutine with it.
                manuallyDisconnected = false

                // Mark the boundary between sessions so the log is easy to read.
                val sessionHost = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                val sessionPort = getIntPrefValue(OscPrefKey.SSL_PORT, prefs)
                val sessionTls = getStringPrefValue(OscPrefKey.SSL_VERSION, prefs)
                    .ifBlank { "DEFAULT" }
                ConnectionLog.startSession(
                    "Server: $sessionHost:$sessionPort  TLS: $sessionTls"
                )

                ConnectionLog.append("SERVICE", "Connect requested")
                FlutterCaller().connecting()
                // A user-initiated connect resets the takeover budget and
                // cancels any pending slot-reclaim work.
                revokeRecoveryAttempts = 0
                jobRevokeRecovery?.cancel()
                jobRevokeRecovery = null
                networkReconnectAttempts = 0
                jobNetworkReconnect?.cancel()
                jobNetworkReconnect = null
                // Release the previous session's TUN interface synchronously
                // before creating a new one. Doing it asynchronously raced with
                // this new session and made establish() return null.
                controlClient?.shutdown()
                controlClient = null

                notificationStatus = CONNECTION_STATUS_CONNECTING
                notificationSpeed = ""
                beForegrounded()
                acquireWakeLock()
                // A connect that happens with the screen already off (auto-connect,
                // quick-settings tile) must not keep the CPU up for the whole
                // session: the receiver only reports changes, so apply the
                // screen-off policy here as well.
                if (!isScreenInteractive) onScreenStateChanged(false)
                resetReconnectionLife(prefs)
                // A new attempt clears any previous failure.
                connectionErrorCode = ""
                connectionErrorMessage = ""
                if (getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)) {
                    prepareLogWriter()
                }

                logWriter?.write("Establish VPN connection")

                initializeClient()

                setRootState(true)

                Service.START_STICKY
            }

            ACTION_VPN_WAKEUP -> {
                // The periodic screen-off wake-up: give the tunnel a few seconds
                // of CPU time for the keepalive/echo, then let it suspend again.
                if (!isScreenInteractive &&
                    connectionStatus == OscPrefKey.Connected.name &&
                    !manuallyDisconnected
                ) {
                    acquireWakeLock()
                    mainHandler.removeCallbacks(releaseIdleWakeLock)
                    mainHandler.postDelayed(releaseIdleWakeLock, SCREEN_OFF_WAKE_LOCK_MS)
                    // The alarm is one-shot: arm the next one.
                    scheduleScreenOffWakeUps()
                    Service.START_STICKY
                } else {
                    // Nothing to keep alive (tunnel down or screen back on).
                    cancelScreenOffWakeUps()
                    if (connectionStatus != OscPrefKey.Connected.name) stopSelf()
                    Service.START_NOT_STICKY
                }
            }

            else -> {
                // Any disconnect request (notification action, tile, app) is a
                // user-initiated disconnect.
                manuallyDisconnected = true
                revokeRecoveryAttempts = 0
                jobRevokeRecovery?.cancel()
                jobRevokeRecovery = null
                networkReconnectAttempts = 0
                jobNetworkReconnect?.cancel()
                jobNetworkReconnect = null
                ConnectionLog.append("SERVICE", "Disconnect requested")
                connectionStatus = OscPrefKey.Disconnected.name
                connectionStartTime = 0L
                // ensure that reconnection has been completely canceled or done
                runBlocking { jobReconnect?.cancelAndJoin() }

                controlClient?.disconnect()
                // Close the TUN synchronously instead of relying on the async
                // kill() coroutine, which scope.cancel() in onDestroy would
                // otherwise cancel before it released the descriptor.
                controlClient?.shutdown()
                controlClient = null

                close()

                val tempMap : HashMap<String,Any>  = HashMap()
                connectionStatus = OscPrefKey.Disconnected.name
                tempMap.put("status",connectionStatus)
                tempMap.put("dns", dnsEnabled)
                tempMap.put("dnsCount", dnsCount)
                tempMap.put("error", errorConnection && !connectionErrorAlreadySent)
                tempMap.put("errorCode", connectionErrorCode)
                tempMap.put("errorMessage", connectionErrorMessage)

                flutterChannel.invokeMethod("connectResponse", tempMap)
                errorConnection = false
                connectionErrorAlreadySent = false

                Service.START_NOT_STICKY
            }
        }
    }

    private fun initializeClient() {
        controlClient = ControlClient(ClientBridge(this)).also {
            it.launchJobMain()
        }
    }

    /** Called once the tunnel is fully up, so the network-reconnect budget resets. */
    internal fun onConnectionEstablished() {
        networkReconnectAttempts = 0
        lastEstablishedAtMs = SystemClock.elapsedRealtime()
        // A tunnel that comes up with the screen already off must switch to the
        // screen-off policy right away: the wake lock taken for the connect would
        // otherwise be held for the whole session.
        if (!isScreenInteractive) {
            scheduleScreenOffWakeUps()
            releaseWakeLock()
        }
    }

    /**
     * True while the service is about to rebuild the tunnel because the
     * underlying network changed. The broken session's socket errors must be
     * ignored meanwhile, or they would tear the service down.
     */
    internal fun isNetworkReconnectPending(): Boolean = jobNetworkReconnect?.isActive == true

    /**
     * Decides whether a session error should be handled by the service (rebuild
     * on the current network) instead of stopping the tunnel.
     *
     * Returns true when the service takes over; the caller must then skip its
     * usual teardown.
     */
    internal fun handleSessionError(throwable: Throwable): Boolean {
        // A rebuild is already on its way.
        if (isNetworkReconnectPending()) return true

        if (manuallyDisconnected) return false

        // Only a live tunnel is worth rebuilding, and only for network-level
        // failures (a switch surfaces as an IOException / SocketException).
        if (connectionStatus != OscPrefKey.Connected.name) return false
        if (throwable !is IOException) return false

        ConnectionLog.append(
            "SERVICE",
            "Network-level session error (${throwable.javaClass.simpleName}); reconnecting"
        )
        scheduleNetworkReconnect()
        return true
    }

    /** Called by the connectivity observer when the underlying network changes. */
    private fun onUnderlyingNetworkChanged(reason: String) {
        ConnectionLog.append("SERVICE", "Network change: $reason")
        if (manuallyDisconnected) return
        if (connectionStatus != OscPrefKey.Connected.name) return

        // Ignore changes that arrive right after the tunnel came up: bringing the
        // TUN interface up/down (e.g. while switching protocols) briefly reshapes
        // the underlying networks and would otherwise start a reconnect that
        // itself reshapes them again — an endless loop, since every successful
        // reconnect resets the attempt budget. A real network switch still
        // surfaces through the session's socket error (handleSessionError).
        val sinceEstablished = SystemClock.elapsedRealtime() - lastEstablishedAtMs
        if (lastEstablishedAtMs > 0L && sinceEstablished < NETWORK_SETTLE_MS) {
            ConnectionLog.append(
                "SERVICE",
                "Ignoring network change ${sinceEstablished}ms after connect (settle window)"
            )
            return
        }

        ConnectionLog.append("SERVICE", "Underlying network changed; reconnecting")
        scheduleNetworkReconnect()
    }

    /**
     * Stops the current session and rebuilds the tunnel after a short debounce,
     * so a burst of connectivity callbacks (network lost + new network up) turns
     * into a single reconnect. Bounded to avoid looping on a flapping network.
     */
    private fun scheduleNetworkReconnect() {
        if (networkReconnectAttempts >= MAX_NETWORK_RECONNECT_ATTEMPTS) {
            ConnectionLog.append(
                "SERVICE",
                "Network reconnect budget exhausted; stopping"
            )
            FlutterCaller().connectionFailed(
                "VPN_NETWORK_LOST",
                "The network changed and the VPN could not be restored. " +
                    "Reconnect manually."
            )
            close()
            return
        }
        networkReconnectAttempts++

        // Stop the broken session immediately: this also marks it abandoned so
        // its socket errors are ignored while we wait to reconnect.
        controlClient?.shutdown()
        controlClient = null

        notificationStatus = CONNECTION_STATUS_NETWORK_CHANGED
        notificationSpeed = ""
        FlutterCaller().reconnecting("Network changed — reconnecting…")
        refreshNotification()

        jobNetworkReconnect?.cancel()
        jobNetworkReconnect = scope.launch {
            try {
                delay(NETWORK_CHANGE_DEBOUNCE_MS)
                if (manuallyDisconnected) return@launch

                ConnectionLog.append("SERVICE", "Reconnecting on the new network")
                notificationStatus = CONNECTION_STATUS_CONNECTING
                notificationSpeed = ""
                beForegrounded()
                initializeClient()
            } catch (_: CancellationException) {
                ConnectionLog.append("SERVICE", "Network reconnect cancelled")
            }
        }
    }


    private fun prepareLogWriter() {
        val currentDateTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
        val filename = "log_osc_${currentDateTime}.txt"

        val prefURI = getURIPrefValue(OscPrefKey.LOG_DIR, prefs)
        if (prefURI == null) {
            makeNotification(NOTIFICATION_ERROR_ID, "LOG: ERR_NULL_PREFERENCE")
            return
        }

        val dirURI = DocumentFile.fromTreeUri(this, prefURI)
        if (dirURI == null) {
            makeNotification(NOTIFICATION_ERROR_ID, "LOG: ERR_NULL_DIRECTORY")
            return
        }

        val fileURI = dirURI.createFile("text/plain", filename)
        if (fileURI == null) {
            makeNotification(NOTIFICATION_ERROR_ID, "LOG: ERR_NULL_FILE")
            return
        }

        val stream = contentResolver.openOutputStream(fileURI.uri, "wa")
        if (stream == null) {
            makeNotification(NOTIFICATION_ERROR_ID, "LOG: ERR_NULL_STREAM")
            return
        }

        logWriter = LogWriter(stream)
    }

    internal fun launchJobReconnect() {
        jobReconnect = scope.launch {
            try {
                val remaining = getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, prefs).also {
                    val life = it - 1
                    setIntPrefValue(life, OscPrefKey.RECONNECTION_LIFE, prefs)

                    val message = "Reconnection will be tried (LIFE = $life)"
                    makeNotification(NOTIFICATION_RECONNECT_ID, message)
                    logWriter?.report(message)
                    ConnectionLog.append("SERVICE", message)
                }

                val total = getIntPrefValue(OscPrefKey.RECONNECTION_COUNT, prefs)
                val attempt = (total - remaining + 1).coerceAtLeast(1)
                val statusMessage = "Reconnecting... (attempt $attempt)"
                updateNotificationStatus(statusMessage)
                FlutterCaller().reconnecting(statusMessage)

                delay(getIntPrefValue(OscPrefKey.RECONNECTION_INTERVAL, prefs) * 1000L)

                updateNotificationStatus(CONNECTION_STATUS_CONNECTING)
                initializeClient()
            } catch (_: CancellationException) { }
            finally {
                cancelNotification(NOTIFICATION_RECONNECT_ID)
            }
        }
    }

    private fun beForegrounded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                NOTIFICATION_CHANNEL_NAME,
                "VPN status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).also {
                notificationManager.createNotificationChannel(it)
            }
        }

        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT).putExtra("manuallyDisconnected",true),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationText = prefs.getString(OscPrefKey.NotificationText.name,"")
        val showNotification = prefs.getBoolean(OscPrefKey.NOTIFICATION_DO_SHOW_DISCONNECT.name,false)
        notificationBaseText = notificationText?.ifBlank { "SoftSSTP VPN" } ?: "SoftSSTP VPN"

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_NAME).also {
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setAutoCancel(true)
            // Use the app's own launcher icon for both the status-bar glyph and
            // the large icon, so the notification matches the app icon exactly.
            // Android renders the small icon as a monochrome mask from its
            // alpha channel, and the launcher icon is a glyph on a transparent
            // background, so the silhouette is preserved.
            val appIcon = applicationInfo.icon
            it.setSmallIcon(if (appIcon != 0) appIcon else R.drawable.baseline_vpn_key_24)
            setApplicationLargeIcon(it)
            // Status and speed go on separate lines (title vs text) so a fast
            // connection does not wrap into a second line.
            it.setContentTitle(renderNotificationTitle())
            it.setContentText(renderNotificationContentText())

            // Tapping the notification opens the app.
            packageManager.getLaunchIntentForPackage(packageName)?.also { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                it.setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            if(showNotification){
                it.addAction(0, "DISCONNECT", pendingIntent)
            }
        }
        foregroundBuilder = builder
        startForegroundNotification(builder.build())
    }

    private fun startForegroundNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_DISCONNECT_ID, notification, 1)
        } else {
            startForeground(NOTIFICATION_DISCONNECT_ID, notification)
        }
    }

    /**
     * Updates the foreground notification with the current download/upload speed.
     * Called once per second from the traffic reporter.
     */
    internal fun updateTrafficNotification(downloadBps: Long, uploadBps: Long) {
        notificationSpeed = "↓ ${formatSpeed(downloadBps)}   ↑ ${formatSpeed(uploadBps)}"
        refreshNotification()
    }

    /**
     * Reflects the connection state in the notification, mirroring the labels
     * used on the app's home screen ("Connecting...", "Connected", ...).
     * Safe to call from any thread.
     */
    internal fun updateNotificationStatus(status: String) {
        notificationStatus = status
        if (status != CONNECTION_STATUS_CONNECTED) {
            notificationSpeed = ""
        }
        refreshNotification()
    }

    /**
     * Uses the application's own launcher icon as the notification's large icon
     * so it matches the icon the user sees on the home screen. The small icon
     * must stay a monochrome drawable (Android tints it in the status bar).
     */
    private fun setApplicationLargeIcon(builder: NotificationCompat.Builder) {
        try {
            val iconRes = applicationInfo.icon
            if (iconRes != 0) {
                builder.setLargeIcon(BitmapFactory.decodeResource(resources, iconRes))
            }
        } catch (_: Exception) {
            // A missing icon must never break the notification.
        }
    }

    private fun renderNotificationTitle(): String = notificationStatus

    private fun renderNotificationContentText(): String {
        if (notificationSpeed.isNotEmpty()) return notificationSpeed
        return when {
            notificationStatus == CONNECTION_STATUS_CONNECTED -> notificationBaseText
            notificationStatus == CONNECTION_STATUS_CONNECTING ->
                "Establishing secure tunnel…"
            notificationStatus == CONNECTION_STATUS_TAKEN_OVER ->
                "Waiting for the other VPN, then reconnecting…"
            notificationStatus == CONNECTION_STATUS_NETWORK_CHANGED ->
                "Network changed — rebuilding the tunnel…"
            notificationStatus.startsWith("Reconnecting") -> "Waiting to retry…"
            else -> notificationBaseText
        }
    }

    private fun refreshNotification() {
        val builder = foregroundBuilder ?: return
        val title = renderNotificationTitle()
        val content = renderNotificationContentText()
        mainHandler.post {
            try {
                builder.setContentTitle(title)
                builder.setContentText(content)
                startForegroundNotification(builder.build())
            } catch (_: Exception) {
                // Updating the notification must never break the tunnel.
            }
        }
    }

    /**
     * Formats a transfer rate. [bitsPerSecond] is the value computed by the
     * traffic reporter, so the result uses bit-based units (bit/s, Kbit/s,
     * Mbit/s) — the convention for link speeds. Data volume is shown in bytes
     * elsewhere, but a speed is always a bit rate.
     */
    private fun formatSpeed(bitsPerSecond: Long): String {
        val bits = if (bitsPerSecond < 0) 0L else bitsPerSecond
        return when {
            bits < 1000 -> "$bits bit/s"
            bits < 1000 * 1000 -> String.format(Locale.US, "%.1f Kbit/s", bits / 1000.0)
            bits < 1000 * 1000 * 1000 ->
                String.format(Locale.US, "%.1f Mbit/s", bits / 1_000_000.0)
            else -> String.format(Locale.US, "%.2f Gbit/s", bits / 1_000_000_000.0)
        }
    }

    internal fun makeNotification(id: Int, message: String) {
        println("err msg : $message")
        ConnectionLog.append("ERROR", message)
        errorConnection = true

    }

    internal fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    internal fun close() {
        connectionStatus = OscPrefKey.Disconnected.name
        connectionStartTime = 0L
        foregroundBuilder = null
        // Final guard: make sure the interface is gone before the service stops.
        controlClient?.closeTerminals()
        cancelScreenOffWakeUps()
        mainHandler.removeCallbacks(releaseIdleWakeLock)
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }

    /**
     * Called by the system when our VPN slot is taken over — typically because
     * another VPN app (e.g. AdGuard) prepared itself. Instead of silently
     * stopping, we wait for the competing VPN to finish starting up (it usually
     * re-prepares itself a few seconds after losing the slot) and then reclaim
     * the slot and bring our tunnel back up, so the user does not have to switch
     * SoftSSTP on again manually. Bounded to avoid an endless fight with an
     * always-on VPN.
     */
    override fun onRevoke() {
        ConnectionLog.append("SERVICE", "VPN slot revoked by another app")

        if (manuallyDisconnected) {
            super.onRevoke()
            return
        }

        if (revokeRecoveryAttempts >= MAX_REVOKE_RECOVERY_ATTEMPTS) {
            ConnectionLog.append(
                "SERVICE",
                "VPN slot revoked too many times; giving up (always-on VPN?)"
            )
            updateNotificationStatus(CONNECTION_STATUS_DISCONNECTED)
            FlutterCaller().connectionFailed(
                "VPN_TAKEN_OVER",
                "Another VPN keeps taking over. Disable its always-on mode and " +
                    "reconnect."
            )
            close()
            return
        }

        revokeRecoveryAttempts++
        ConnectionLog.append(
            "SERVICE",
            "Another VPN took over; will reclaim (attempt " +
                "$revokeRecoveryAttempts/$MAX_REVOKE_RECOVERY_ATTEMPTS)"
        )

        // The system has already deactivated our interface; release our side.
        controlClient?.shutdown()
        controlClient = null

        // Tell the user what is happening instead of looking disconnected.
        updateNotificationStatus(CONNECTION_STATUS_TAKEN_OVER)
        FlutterCaller().reconnecting("Another VPN took over — waiting to reconnect…")

        scope.launch {
            try {
                // Wait for the competing VPN to appear, then let it settle, so it
                // does not immediately steal the slot again.
                val otherVpnUp = awaitOtherVpn()
                if (otherVpnUp) {
                    delay(OTHER_VPN_SETTLE_DELAY_MS)
                }

                // Re-prepare: this dethrones the competing VPN and makes us the
                // owner.
                try {
                    if (VpnService.prepare(this@SstpVpnService) != null) {
                        ConnectionLog.append("SERVICE", "Reclaim needs user consent; stopping")
                        close()
                        return@launch
                    }
                } catch (e: Exception) {
                    ConnectionLog.append("SERVICE", "Reclaim prepare failed: ${e.message}")
                }

                ConnectionLog.append("SERVICE", "Reclaiming VPN slot now")
                notificationStatus = CONNECTION_STATUS_CONNECTING
                notificationSpeed = ""
                FlutterCaller().reconnecting("Reclaiming VPN slot…")
                beForegrounded()
                initializeClient()
            } catch (_: CancellationException) {
                ConnectionLog.append("SERVICE", "Slot reclaim cancelled")
            }
        }.also { jobRevokeRecovery = it }
    }

    /**
     * Waits until another VPN's network appears in the connectivity manager,
     * which means the competing app has finished taking the slot. Returns true
     * if it was detected, false if [WAIT_FOR_OTHER_VPN_TIMEOUT_MS] elapsed.
     */
    private suspend fun awaitOtherVpn(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val deadline = SystemClock.elapsedRealtime() + WAIT_FOR_OTHER_VPN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val vpnUp = connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (vpnUp) {
                ConnectionLog.append("SERVICE", "Competing VPN detected; letting it settle")
                return true
            }
            delay(VPN_APPEAR_POLL_INTERVAL_MS)
        }
        ConnectionLog.append(
            "SERVICE",
            "No competing VPN appeared within timeout; reclaiming anyway"
        )
        return false
    }

    override fun onDestroy() {
        ConnectionLog.append("SERVICE", "Service destroyed")
        logWriter?.write("Terminate VPN connection")
        logWriter?.close()
        logWriter = null

        networkChangeObserver?.stop()
        networkChangeObserver = null

        screenStateMonitor?.stop()
        screenStateMonitor = null


        // Release the TUN interface synchronously, before the scope is cancelled.
        // Doing it only through kill() was racy: scope.cancel() could cancel the
        // cleanup coroutine before it closed the descriptor, leaking the
        // interface and making the next establish() return null.
        controlClient?.shutdown()
        controlClient = null

        scope.cancel()

        cancelScreenOffWakeUps()
        mainHandler.removeCallbacks(releaseIdleWakeLock)
        releaseWakeLock()
        setRootState(false)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
