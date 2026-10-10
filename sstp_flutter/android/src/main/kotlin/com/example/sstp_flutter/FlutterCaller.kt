package com.example.sstp_flutter

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import com.example.sstp_flutter.preference.OscPrefKey
import kotlinx.coroutines.Dispatchers


import kotlinx.coroutines.withContext

class FlutterCaller {

    private val mainHandler = Handler(Looper.getMainLooper())

@SuppressLint("SuspiciousIndentation")
fun connect(){
    if(connectionStatus == OscPrefKey.Connected.name) return
        connectionStatus = OscPrefKey.Connected.name
        connectionStartTime = System.currentTimeMillis()
        val tempMap : HashMap<String,Any>  = HashMap()
        tempMap.put("status",connectionStatus)
        tempMap.put("ip",assignedIp)
        invoke("connectResponse", tempMap)
    }

    fun connecting(){
        connectionStatus = OscPrefKey.Connecting.name
        val tempMap : HashMap<String,Any>  = HashMap()
        tempMap.put("status",connectionStatus)
        invoke("connectResponse", tempMap)
    }

    /**
     * Notifies the app that an automatic reconnection attempt is in progress.
     * [message] is a human readable label shown on the home screen.
     */
    fun reconnecting(message: String){
        connectionStatus = OscPrefKey.Reconnecting.name
        val tempMap : HashMap<String,Any>  = HashMap()
        tempMap.put("status",connectionStatus)
        tempMap.put("message", message)
        invoke("connectResponse", tempMap)
    }

    /**
     * Reports a connection failure with a machine readable [code] and a
     * human readable [message], so the app can explain what went wrong instead
     * of surfacing a raw native stack trace.
     */
    fun connectionFailed(code: String, message: String){
        connectionStatus = OscPrefKey.Disconnected.name
        connectionErrorCode = code
        connectionErrorMessage = message
        // Remember that the app has been told about this failure, so the
        // service's final "disconnected" event does not repeat it.
        connectionErrorAlreadySent = true
        val tempMap : HashMap<String,Any>  = HashMap()
        tempMap.put("status", connectionStatus)
        tempMap.put("error", true)
        tempMap.put("errorCode", code)
        tempMap.put("errorMessage", message)
        invoke("connectResponse", tempMap)
    }

    /**
     * Reports the IPv4 address assigned by the server through IPCP. Sent right
     * after the address is known, which is after the "Connected" event.
     *
     * The caller runs on a background dispatcher, but outbound MethodChannel
     * calls must originate on the platform (main) thread, otherwise the event
     * is silently dropped and the UI only picks the address up on resume.
     */
    fun sendAssignedIp(ip: String){
        assignedIp = ip
        val tempMap : HashMap<String,Any>  = HashMap()
        tempMap.put("ip", ip)
        invoke("assignedIp", tempMap)
    }

    suspend fun DownloadSpeed(downloadSpeed : Long, totalDownload : Long){
        try {
            withContext(Dispatchers.Main) {
                flutterChannel.invokeMethod("downloadSpeed",listOf(downloadSpeed, totalDownload) )
            }
        } catch (_: Throwable) {
            // Reporting traffic must never break the running tunnel.
        }
    }

    suspend fun UploadSpeed(uploadSpeed : Long, totalUpload : Long){
        try {
            withContext(Dispatchers.Main) {
                flutterChannel.invokeMethod("uploadSpeed", listOf(uploadSpeed, totalUpload))
            }
        } catch (_: Throwable) {
            // Reporting traffic must never break the running tunnel.
        }
    }

    private fun invoke(method: String, arguments: Any?) {
        // MethodChannel calls must happen on the platform (main) thread. Some
        // callers (e.g. IPTerminal.initialize) run on a background dispatcher,
        // so marshal every call to the main looper when needed. Without this the
        // "assignedIp" event was silently dropped and the internal address only
        // appeared after the app was resumed (which polls the value instead).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doInvoke(method, arguments)
        } else {
            mainHandler.post { doInvoke(method, arguments) }
        }
    }

    private fun doInvoke(method: String, arguments: Any?) {
        try {
            flutterChannel.invokeMethod(method, arguments)
        } catch (_: Throwable) {
            // The Flutter engine may already be detached; ignore.
        }
    }

}