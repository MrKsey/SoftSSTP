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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
internal const val CONNECTION_STATUS_WAITING_NETWORK = "Waiting for network"
internal const val CONNECTION_STATUS_REBUILDING = "Rebuilding tunnel"

// When the underlying network changes (Wi-Fi <-> mobile data) the SSTP socket
// breaks. The service rebuilds the tunnel on the new network instead of staying
// down. A switch produces a burst of connectivity callbacks, so the rebuild is
// debounced, and it is bounded so a flapping network cannot loop forever.
private const val MAX_NETWORK_RECONNECT_ATTEMPTS = 3
private const val NETWORK_CHANGE_DEBOUNCE_MS = 1_500L
// With no network at all there is nothing to rebuild, so the service parks the
// tunnel instead of stopping: it waits for an underlying network and then
// reconnects on its own. The wait is event-driven (the network observer wakes it)
// and this is only the fallback re-check, deliberately long so a parked tunnel
// costs almost no battery.
private const val NETWORK_WAIT_FALLBACK_MS = 30_000L
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
// Fallback quiet-stretch reset for the takeover budget: a takeover that is
// followed by this much silence also counts as recovered. The main reset is a
// successful reconnect (lastEstablishedAtMs moving past the revoke): AdGuard
// re-prepares itself after every network switch, and counting those takeovers
// against the budget made the tunnel give up even though every reclaim had
// worked.
private const val REVOKE_BUDGET_RESET_MS = 60_000L
// How long to wait for the competing VPN to appear before reclaiming anyway.
private const val WAIT_FOR_OTHER_VPN_TIMEOUT_MS = 20_000L
// Extra pause after the competing VPN is detected, so its own startup (and the
// initial handshake of its watchdog) finishes before we dethrone it.
private const val OTHER_VPN_SETTLE_DELAY_MS = 2_000L
private const val VPN_APPEAR_POLL_INTERVAL_MS = 500L

// A session that is torn down while the device has no network keeps its socket
// until a network is back (see pendingTeardown). Once it is released, the server
// needs a moment to drop the old session before the new one is negotiated: if it
// still holds the old session it also still routes that session's address to it,
// and the rebuilt tunnel comes up "connected" but carries no traffic.
private const val OLD_SESSION_RELEASE_SETTLE_MS = 1_000L

// A tunnel can stay "connected" and carry nothing at all: the socket may have
// been created on another VPN's interface and died with it, or the server may
// still hold a stale session and route the address to that one. Nothing about it
// surfaces as an error — the session just goes quiet. A live session, on the
// other hand, is never quiet for long: the peer sends its own SSTP/LCP echoes
// every few seconds, and that is what the watchdog below relies on.
private const val TUNNEL_WATCH_INTERVAL_MS = 5_000L
// Silence longer than this is what a dead tunnel looks like. Comfortably above
// the peer's echo period (a few seconds), so a healthy tunnel never trips it.
private const val TUNNEL_SILENCE_MS = 12_000L
// Time given to the echo probe to be answered before the silence is counted.
private const val TUNNEL_PROBE_GRACE_MS = 3_000L
// Consecutive unanswered probes before the session is rebuilt. More than one, so
// a slow moment on the network does not restart a working tunnel.
private const val TUNNEL_MAX_FAILURES = 2

// Screen off: instead of holding a partial wake lock for the whole session (the
// CPU then never suspends, which is the biggest single battery drain of a tunnel
// left connected), the lock is released and taken back only when it is needed:
//   * a short lock renewed while traffic actually flows, so a download with the
//     screen off is not throttled;
//   * a periodic alarm that wakes the CPU long enough for the SSTP keepalive and
//     the PPP echo to go out on an otherwise idle tunnel.
// The alarm interval is deliberately shorter than the 20 s echo interval.
private const val SCREEN_OFF_WAKEUP_INTERVAL_MS = 15_000L
// Long enough for the keepalive exchange to complete (a few milliseconds on a
// working link), short enough not to keep the CPU busy between alarms.
private const val SCREEN_OFF_WAKE_LOCK_MS = 4_000L
// Data path: how often the short wake lock is renewed while bytes are moving,
// and how long it is held after the last packet. Renewing is throttled so a
// busy tunnel does not touch the main handler for every packet.
private const val DATA_ACTIVITY_REFRESH_MS = 1_000L
private const val DATA_ACTIVITY_WAKE_LOCK_MS = 10_000L
// Bytes that must pass within one refresh window before the screen-off wake
// lock is renewed. Idle push/heartbeat packets (a few hundred bytes every few
// seconds) used to renew it forever, so the CPU never suspended. The threshold
// is above a background voice call's payload (~8-12 KB/s) so an idle phone does
// not pin the CPU, and a real transfer clears it many times over.
private const val DATA_ACTIVITY_MIN_BYTES = 16 * 1024
// Request code of the screen-off wake-up alarm's PendingIntent.
private const val WAKEUP_REQUEST_CODE = 42

internal class SstpVpnService : VpnService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var notificationManager: NotificationManagerCompat
    internal lateinit var scope: CoroutineScope

    internal var logWriter: LogWriter? = null
    private var controlClient: ControlClient?  = null

    // A session that was stopped because the underlying network disappeared,
    // kept until a network is back. Its socket still has to be closed: closing it
    // is the only thing that tells the server the session is over, and while the
    // device is offline that close (an RST, because the server's frames were
    // never read) is simply lost. The server then keeps the dead session — and
    // the address it was given — so the tunnel rebuilt afterwards is connected
    // but carries no traffic until the server's own session timeout expires.
    private var pendingTeardown: ControlClient? = null

    private var jobReconnect: Job? = null

    // Pending slot-reclaim work started from onRevoke(). Cancelled when the user
    // connects or disconnects, so it can never resurrect a stopped tunnel.
    private var jobRevokeRecovery: Job? = null

    // Watches the underlying (non-VPN) networks so the tunnel can be rebuilt
    // after a Wi-Fi <-> mobile data switch.
    private var networkChangeObserver: NetworkChangeObserver? = null
    private var jobNetworkReconnect: Job? = null
    private var networkReconnectAttempts = 0
    // Watches a live session for a tunnel that carries nothing (see
    // startTunnelWatchdog).
    private var jobTunnelWatchdog: Job? = null
    // True while the tunnel is parked because the device has no network at all.
    // The service stays alive (and foreground) but does no work: no session, no
    // wake lock, no periodic alarm. A network appearing resumes the rebuild.
    @Volatile
    private var waitingForNetwork = false
    // Wakes the parked rebuild the moment the observer reports a network.
    private val networkWaitWakeup = Channel<Unit>(Channel.CONFLATED)
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

    // Number of *consecutive* takeovers we could not recover from. A takeover
    // counts as recovered when the tunnel comes back up after it (see
    // lastEstablishedAtMs) or when a quiet stretch follows (see
    // REVOKE_BUDGET_RESET_MS); only an unrecovered streak spends the budget.
    // Reset on every user-initiated connect/disconnect. A VPN that keeps
    // nudging us but loses every reclaim must not use the budget up over a
    // long session.
    private var revokeRecoveryAttempts = 0
    // Monotonic time of the last revoke, used to reset the budget above.
    private var lastRevokeAtMs = 0L

    // Held only while the screen is off and the tunnel has work to do. With the
    // screen on the platform already keeps the CPU up, so taking a lock there
    // only inflated the power report without changing anything.
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
    // Bytes seen since the last wake-lock renewal (see noteDataActivity).
    private var dataBytesSinceRenew = 0

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

        // Parked with no network: there is no session to keep alive, so the
        // screen state changes nothing — and arming the periodic keepalive alarm
        // here would wake the CPU every 15 s for nothing.
        if (waitingForNetwork) {
            cancelScreenOffWakeUps()
            mainHandler.removeCallbacks(releaseIdleWakeLock)
            releaseWakeLock()
            return
        }

        if (interactive) {
            // The screen is on, so the platform keeps the CPU up by itself:
            // drop the screen-off lock and stop the periodic wake-ups.
            cancelScreenOffWakeUps()
            mainHandler.removeCallbacks(releaseIdleWakeLock)
            releaseWakeLock()
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
     * the data path once per packet; the renewal is throttled and requires a
     * minimum amount of traffic, so idle heartbeats do not pin the CPU forever.
     */
    internal fun noteDataActivity(bytes: Int = 0) {
        if (isScreenInteractive) return
        dataBytesSinceRenew += bytes
        val now = SystemClock.elapsedRealtime()
        if (now - lastDataActivityMs < DATA_ACTIVITY_REFRESH_MS) return
        if (dataBytesSinceRenew < DATA_ACTIVITY_MIN_BYTES) return
        dataBytesSinceRenew = 0
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

        // Android kills the process when startForegroundService() is not followed
        // by startForeground() within about five seconds. The connect path below
        // only reaches startForeground() after the tunnel is negotiated — TLS plus
        // PPP easily takes longer — so a start request that arrives while the app
        // is in the background (the system restarting the START_STICKY service, for
        // one) killed the process here with
        // ForegroundServiceDidNotStartInTimeException. Showing the notification
        // first thing meets the deadline and gives the tunnel its foreground
        // status from the very first moment, which also keeps the system from
        // killing a started-but-not-foreground service in the background.
        if (action == ACTION_VPN_CONNECT) {
            beForegrounded()
        }

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
                lastRevokeAtMs = 0L
                jobRevokeRecovery?.cancel()
                jobRevokeRecovery = null
                networkReconnectAttempts = 0
                jobNetworkReconnect?.cancel()
                jobNetworkReconnect = null

                logWriter?.write("Establish VPN connection")

                // A session parked by an earlier network loss still holds its
                // socket: release it before the new one is negotiated, so the
                // server is not still holding the old session when this one
                // comes up (see pendingTeardown). Without a network the close
                // would be lost again, so the release stays deferred.
                if (pendingTeardown != null && hasUnderlyingNetwork()) {
                    scope.launch {
                        if (releasePendingTeardown()) delay(OLD_SESSION_RELEASE_SETTLE_MS)
                        initializeClient()
                    }
                } else {
                    initializeClient()
                }

                setRootState(true)

                Service.START_STICKY
            }

            ACTION_VPN_WAKEUP -> {
                // The periodic screen-off wake-up: give the tunnel a few seconds
                // of CPU time for the keepalive/echo, then let it suspend again.
                if (!isScreenInteractive &&
                    connectionStatus == OscPrefKey.Connected.name &&
                    !manuallyDisconnected &&
                    // A parked tunnel has nothing to keep alive, and re-arming the
                    // alarm here would defeat the whole point of the pause.
                    !waitingForNetwork
                ) {
                    acquireWakeLock()
                    mainHandler.removeCallbacks(releaseIdleWakeLock)
                    mainHandler.postDelayed(releaseIdleWakeLock, SCREEN_OFF_WAKE_LOCK_MS)
                    // The alarm is one-shot: arm the next one.
                    scheduleScreenOffWakeUps()
                    Service.START_STICKY
                } else {
                    // Nothing to keep alive (tunnel down, parked, or screen back on).
                    cancelScreenOffWakeUps()
                    // A rebuild in progress owns the service: it must survive until
                    // the new session is up. Stopping here killed the service in the
                    // middle of a rebuild, because the state is neither "Connected"
                    // nor parked while the tunnel is being rebuilt.
                    val isRebuilding = isNetworkReconnectPending() || isRevokeRecoveryPending()
                    // Only an explicit disconnect ends the service here. A
                    // "Connecting"/"Reconnecting" state also owns the service: the
                    // rebuild coroutines are already done by then (initializeClient()
                    // is their last step) and the session is being negotiated, so
                    // isRebuilding is false — this alarm used to kill the service in
                    // the middle of a network reconnect, leaving the tunnel down.
                    if (connectionStatus == OscPrefKey.Disconnected.name &&
                        !waitingForNetwork &&
                        !isRebuilding
                    ) {
                        stopSelf()
                    }
                    Service.START_NOT_STICKY
                }
            }

            else -> {
                // Any disconnect request (notification action, tile, app) is a
                // user-initiated disconnect.
                manuallyDisconnected = true
                revokeRecoveryAttempts = 0
                lastRevokeAtMs = 0L
                jobRevokeRecovery?.cancel()
                jobRevokeRecovery = null
                networkReconnectAttempts = 0
                jobNetworkReconnect?.cancel()
                jobNetworkReconnect = null
                waitingForNetwork = false
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
                dropPendingTeardown()

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
        // Only one session may exist at a time. Two restart paths call this (the
        // network-reconnect and the slot-reclaim coroutines) and they overlap in
        // practice — losing the VPN slot also looks like a network change — so
        // without this the first session kept running with its own TUN while the
        // second one was started: two live tunnels and two handshakes against the
        // server, and the orphaned session's later socket error tore the whole
        // service down through kill().
        // The watchdog belongs to the old session: a fresh one starts it again
        // once it is up (see onConnectionEstablished).
        stopTunnelWatchdog()
        controlClient?.shutdown()
        controlClient = null

        controlClient = ControlClient(ClientBridge(this)).also {
            it.launchJobMain()
        }
    }

    /** Called once the tunnel is fully up, so the network-reconnect budget resets. */
    internal fun onConnectionEstablished() {
        networkReconnectAttempts = 0
        lastEstablishedAtMs = SystemClock.elapsedRealtime()
        startTunnelWatchdog()
        // A tunnel that comes up with the screen already off must switch to the
        // screen-off policy right away: the wake lock taken for the connect would
        // otherwise be held for the whole session. With the screen on no lock is
        // kept at all (the platform keeps the CPU up), so make sure a leftover
        // from an earlier screen-off period is dropped.
        if (!isScreenInteractive) {
            scheduleScreenOffWakeUps()
        }
        releaseWakeLock()
    }

    /**
     * Watches a live session for the one failure that never surfaces as an error:
     * a tunnel that is up but carries nothing.
     *
     * The check is the peer's own silence. A healthy session receives its echoes
     * (SSTP and LCP) every few seconds, so once nothing has arrived for
     * [TUNNEL_SILENCE_MS] the session is asked directly — an SSTP echo request,
     * which the peer always answers on a working session. Several unanswered
     * probes in a row mean the session has to be rebuilt.
     *
     * This has to live outside the receive loop: that loop blocks on the socket
     * read, so the echo timers inside it stop ticking exactly when the peer goes
     * quiet, which is the moment they would be needed.
     */
    private fun startTunnelWatchdog() {
        jobTunnelWatchdog?.cancel()
        jobTunnelWatchdog = scope.launch {
            var failures = 0
            while (isActive) {
                delay(TUNNEL_WATCH_INTERVAL_MS)
                if (manuallyDisconnected || waitingForNetwork) continue
                if (connectionStatus != OscPrefKey.Connected.name) continue

                val session = controlClient ?: continue
                val before = session.lastIncomingAtMs()
                if (SystemClock.elapsedRealtime() - before < TUNNEL_SILENCE_MS) {
                    failures = 0
                    continue
                }

                // Nothing has arrived for a while: ask the peer directly.
                session.sendEchoProbe()
                delay(TUNNEL_PROBE_GRACE_MS)

                if ((controlClient?.lastIncomingAtMs() ?: before) > before) {
                    // The probe was answered: the session is alive, just idle.
                    failures = 0
                    continue
                }

                failures++
                ConnectionLog.append(
                    "SERVICE",
                    "Tunnel silent; probe $failures/$TUNNEL_MAX_FAILURES unanswered"
                )
                if (failures < TUNNEL_MAX_FAILURES) continue

                failures = 0
                ConnectionLog.append("SERVICE", "Tunnel carries no traffic; rebuilding the session")
                scheduleNetworkReconnect(
                    status = CONNECTION_STATUS_REBUILDING,
                    userMessage = "The tunnel stopped carrying traffic — rebuilding it…"
                )
                return@launch
            }
        }
    }

    /** Stops watching the session; called whenever that session is gone. */
    private fun stopTunnelWatchdog() {
        jobTunnelWatchdog?.cancel()
        jobTunnelWatchdog = null
    }

    /**
     * True while the service is about to rebuild the tunnel because the
     * underlying network changed. The broken session's socket errors must be
     * ignored meanwhile, or they would tear the service down.
     */
    internal fun isNetworkReconnectPending(): Boolean = jobNetworkReconnect?.isActive == true

    /**
     * True while the service is rebuilding the tunnel to take the VPN slot back
     * from another app. Its session errors must be ignored meanwhile for the same
     * reason as [isNetworkReconnectPending].
     */
    internal fun isRevokeRecoveryPending(): Boolean = jobRevokeRecovery?.isActive == true

    /**
     * Decides whether a session error should be handled by the service (rebuild
     * on the current network) instead of stopping the tunnel.
     *
     * Returns true when the service takes over; the caller must then skip its
     * usual teardown.
     */
    internal fun handleSessionError(throwable: Throwable): Boolean {
        // A rebuild is already on its way — either on the new network or to
        // reclaim the VPN slot from another app.
        if (isNetworkReconnectPending()) return true
        if (isRevokeRecoveryPending()) return true

        if (manuallyDisconnected) return false

        // No underlying network at all: nothing to retry yet. Park the tunnel and
        // rebuild it when a network shows up, instead of failing the connect and
        // stopping the service. This also covers switching the VPN on while the
        // device is offline.
        if (throwable is IOException && !hasUnderlyingNetwork()) {
            ConnectionLog.append(
                "SERVICE",
                "Session error with no network (${throwable.javaClass.simpleName}); " +
                    "pausing until it comes back"
            )
            scheduleNetworkReconnect()
            return true
        }

        if (connectionStatus != OscPrefKey.Connected.name) {
            // A rebuild is already running its own attempt and the tunnel has not
            // come up yet. If that attempt fails (the new network was not usable
            // yet, or the server was briefly unreachable), keep retrying within
            // the same budget instead of tearing the service down — otherwise a
            // single unlucky attempt left the user without a tunnel until they
            // reconnected by hand.
            if (throwable is IOException && networkReconnectAttempts > 0) {
                val exhausted = networkReconnectAttempts >= MAX_NETWORK_RECONNECT_ATTEMPTS
                ConnectionLog.append(
                    "SERVICE",
                    "Rebuild attempt $networkReconnectAttempts/" +
                        "$MAX_NETWORK_RECONNECT_ATTEMPTS failed " +
                        "(${throwable.javaClass.simpleName}); " +
                        if (exhausted) "giving up" else "retrying"
                )
                // Handles the exhausted budget itself: it notifies the app and
                // stops the service cleanly.
                scheduleNetworkReconnect()
                return true
            }
            return false
        }

        // Only a live tunnel is worth rebuilding, and only for network-level
        // failures (a switch surfaces as an IOException / SocketException).
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

        // A parked tunnel is waiting for exactly this: let the rebuild continue.
        // Checked before anything else, because while parked there is no live
        // session and the state below is not "Connected".
        if (waitingForNetwork) {
            networkWaitWakeup.trySend(Unit)
            return
        }

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
     * The network the tunnel's own connection to the server must use: a real
     * (non-VPN) network with internet access. Our own tunnel is excluded, and so
     * is any other VPN (e.g. AdGuard): a socket created on another VPN's
     * interface dies with that interface, which is exactly what happens when we
     * reclaim the slot from it.
     */
    internal fun findUnderlyingNetwork(): android.net.Network? {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = try {
                connectivityManager.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            }
            caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /**
     * Makes sure this app may call protect() right now.
     *
     * The protect() right belongs to the app that last called
     * VpnService.prepare(): any prepare() from another VPN app (e.g. AdGuard
     * trying to bring its protection back) silently revokes it for us, without
     * an onRevoke() when our TUN is not up at that moment. A socket created
     * while the right is revoked cannot be protected — protect() returns false
     * — and once our TUN comes up, that socket's traffic is routed back into
     * the tunnel itself: the session reports "connected" but carries no traffic
     * until the server drops it. Calling prepare() here takes the right back
     * (the same way [IPTerminal] claims the slot before establishing).
     *
     * Returns true when the right is ours, false when the user's consent is
     * missing (nothing can be done from the background in that case).
     */
    internal fun ensureProtectRight(): Boolean {
        return try {
            val consent = VpnService.prepare(this)
            if (consent == null) {
                true
            } else {
                ConnectionLog.append("SERVICE", "Protect right: user consent required")
                false
            }
        } catch (e: Exception) {
            ConnectionLog.append("SERVICE", "Protect right re-claim failed: ${e.message}")
            false
        }
    }

    /**
     * Logs every network the platform currently knows about, together with its
     * transports and owner. Used while diagnosing a rebuilt tunnel that carries
     * no traffic: it shows whether another VPN was up when the socket was made.
     */
    internal fun logNetworkSnapshot(tag: String) {
        // Enumerating every network and its capabilities is not free; the line
        // only exists for the log.
        if (!ConnectionLog.enabled) return
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val description = connectivityManager.allNetworks.joinToString("; ") { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val transport = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    else -> "other"
                }
                "net${network.networkHandle}:$transport${if (vpn) "+vpn" else ""}" +
                    ":owner=${network.networkHandle}"
            }
            ConnectionLog.append("NET", "$tag: $description")
        } catch (_: Exception) {
            // Diagnostics must never break the connection.
        }
    }

    /**
     * Releases the session that was stopped while the device had no network: the
     * SSTP disconnect goes out and the socket is closed, now that the close can
     * actually reach the server. Returns true when there was one to release.
     */
    private suspend fun releasePendingTeardown(): Boolean {
        val session = pendingTeardown ?: return false
        pendingTeardown = null
        ConnectionLog.append("SERVICE", "Releasing the stopped session's socket")
        session.releaseSocket()
        return true
    }

    /**
     * Releases the deferred session without waiting for the graceful disconnect.
     * Used when the release cannot be delayed (the service is stopping).
     */
    private fun dropPendingTeardown() {
        val session = pendingTeardown ?: return
        pendingTeardown = null
        session.closeSocket()
    }

    /**
     * Stops the current session and rebuilds the tunnel after a short debounce,
     * so a burst of connectivity callbacks (network lost + new network up) turns
     * into a single reconnect. Bounded to avoid looping on a flapping network.
     *
     * With no network at all the attempt budget is left alone: the service parks
     * the tunnel (see [awaitUnderlyingNetwork]) and rebuilds it once a network is
     * back, instead of stopping and leaving the user to reconnect by hand.
     */
    private fun scheduleNetworkReconnect(
        status: String = CONNECTION_STATUS_NETWORK_CHANGED,
        userMessage: String = "Network changed — reconnecting…"
    ) {
        // The session this watchdog watched is being torn down right here.
        stopTunnelWatchdog()

        // The two restart paths are mutually exclusive: whichever is started last
        // owns the rebuild. Running both left the first session alive with its own
        // TUN while the second was being brought up.
        jobRevokeRecovery?.cancel()
        jobRevokeRecovery = null

        // Stop the broken session immediately: this also marks it abandoned so
        // its socket errors are ignored while we wait to reconnect. Its socket is
        // only released once a network is back (see pendingTeardown): closing it
        // is what makes the server drop the session, and while offline that close
        // never reaches the server.
        val broken = controlClient
        controlClient = null
        if (broken != null) {
            if (pendingTeardown == null) {
                // The close of a session stopped while offline is what makes the
                // server drop it, so it is deferred until the network is back.
                broken.shutdown(keepSocket = true)
                pendingTeardown = broken
            } else {
                // A session is already waiting to be released. That one is older,
                // and it is the one the server still holds, so keep it deferred
                // and let this one go right away.
                broken.shutdown()
            }
        }

        notificationStatus = status
        notificationSpeed = ""
        FlutterCaller().reconnecting(userMessage)
        refreshNotification()

        jobNetworkReconnect?.cancel()
        jobNetworkReconnect = scope.launch {
            try {
                delay(NETWORK_CHANGE_DEBOUNCE_MS)
                if (manuallyDisconnected) return@launch

                // The old network is gone and the new one may still be coming up.
                // Rebuilding before it is usable only produces a failed attempt —
                // and with no network at all there is nothing to rebuild yet.
                awaitUnderlyingNetwork()
                if (manuallyDisconnected) return@launch

                // Counted here, not before the wait: an offline device must not
                // spend the budget on attempts that cannot succeed.
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
                    return@launch
                }
                networkReconnectAttempts++

                // A usable network is back, so the stopped session's close can
                // finally reach the server. Releasing it here — and letting the
                // server act on it before the new session is negotiated — is what
                // keeps the server from still holding the old session, and the
                // address it was given, when the new tunnel comes up.
                if (releasePendingTeardown()) {
                    delay(OLD_SESSION_RELEASE_SETTLE_MS)
                }

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

    /**
     * Parks the caller until a usable underlying (non-VPN) network is up, so a
     * rebuild triggered by the loss of the old network does not run while the new
     * one is still being established, and an offline device waits instead of
     * burning its retry budget.
     *
     * Nothing is polled: the network observer wakes the wait, and the fallback
     * timeout only covers a missed callback. While parked the service holds no
     * wake lock and no session, so it costs almost nothing.
     */
    private suspend fun awaitUnderlyingNetwork() {
        if (hasUnderlyingNetwork()) return

        ConnectionLog.append("SERVICE", "No network — pausing until it comes back")
        waitingForNetwork = true
        notificationStatus = CONNECTION_STATUS_WAITING_NETWORK
        notificationSpeed = ""
        FlutterCaller().reconnecting("No network — waiting for it to come back…")
        refreshNotification()
        // Nothing to keep awake while parked: the session is gone, so the wake
        // lock and the screen-off keepalive alarm have no work to do.
        cancelScreenOffWakeUps()
        mainHandler.removeCallbacks(releaseIdleWakeLock)
        releaseWakeLock()

        try {
            while (!manuallyDisconnected) {
                withTimeoutOrNull(NETWORK_WAIT_FALLBACK_MS) { networkWaitWakeup.receive() }
                if (hasUnderlyingNetwork()) break
            }
        } finally {
            waitingForNetwork = false
        }

        if (manuallyDisconnected) return

        ConnectionLog.append("SERVICE", "Network is back — reconnecting")
        // Restore the power policy the session needs. With the screen on the
        // platform already keeps the CPU up; only a screen-off connect needs the
        // periodic keepalive alarm.
        if (isScreenInteractive) {
            releaseWakeLock()
        } else {
            scheduleScreenOffWakeUps()
        }
    }

    /**
     * True when the platform has a usable underlying (non-VPN) network with
     * internet access. Our own tunnel is excluded: it stays "up" while parked, so
     * it must never be mistaken for a real network.
     */
    private fun hasUnderlyingNetwork(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return connectivityManager.allNetworks.any { network ->
            val caps = try {
                connectivityManager.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            }
            caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
            notificationStatus == CONNECTION_STATUS_REBUILDING ->
                "No traffic came back — rebuilding the tunnel…"
            notificationStatus == CONNECTION_STATUS_WAITING_NETWORK ->
                "No network — waiting for it to come back…"
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
        waitingForNetwork = false
        stopTunnelWatchdog()
        // Stopping is final: a parked rebuild would otherwise bring the tunnel
        // back up after the service was told to stop.
        jobNetworkReconnect?.cancel()
        jobNetworkReconnect = null
        jobRevokeRecovery?.cancel()
        jobRevokeRecovery = null
        foregroundBuilder = null
        // Final guard: make sure the interface is gone before the service stops.
        controlClient?.closeTerminals()
        dropPendingTeardown()
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
     * always-on VPN: the budget counts only takeovers that were not recovered
     * by a successful reconnect.
     */
    override fun onRevoke() {
        ConnectionLog.append("SERVICE", "VPN slot revoked by another app")

        if (manuallyDisconnected) {
            super.onRevoke()
            return
        }

        // Count only the takeovers we could not recover from: when the tunnel
        // came back up after the previous revoke (lastEstablishedAtMs moved
        // past it), or a long quiet stretch followed it, the previous takeover
        // is settled and the count starts over. Without the reconnect check a
        // streak of recovered takeovers (AdGuard re-arming after each network
        // switch, all reclaimed within seconds) still spent the budget and the
        // fifth one gave up on a healthy tunnel.
        val now = SystemClock.elapsedRealtime()
        if (lastEstablishedAtMs > lastRevokeAtMs ||
            now - lastRevokeAtMs > REVOKE_BUDGET_RESET_MS
        ) {
            revokeRecoveryAttempts = 0
        }
        lastRevokeAtMs = now

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
        // The watchdog goes first: a probe fired during the reclaim would look
        // like a dead tunnel and would take the rebuild over from the reclaim.
        stopTunnelWatchdog()
        controlClient?.shutdown()
        controlClient = null

        // Taking the slot back already includes reconnecting, so it supersedes a
        // pending network-driven restart. Letting both run started two sessions at
        // once — two handshakes against the server and two TUN interfaces.
        jobNetworkReconnect?.cancel()
        jobNetworkReconnect = null

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
                // A session parked by a network loss may still hold its socket.
                // Only release it when a network is actually available: without
                // one the close would be lost again, so it stays deferred.
                if (hasUnderlyingNetwork() && releasePendingTeardown()) {
                    delay(OLD_SESSION_RELEASE_SETTLE_MS)
                }
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
        dropPendingTeardown()

        stopTunnelWatchdog()
        scope.cancel()

        cancelScreenOffWakeUps()
        mainHandler.removeCallbacks(releaseIdleWakeLock)
        releaseWakeLock()
        setRootState(false)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
