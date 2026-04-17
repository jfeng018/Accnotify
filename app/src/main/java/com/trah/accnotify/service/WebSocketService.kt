package com.trah.accnotify.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.crypto.E2ECrypto
import com.trah.accnotify.data.Message
import com.trah.accnotify.ui.MainActivity
import com.trah.accnotify.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Date
import java.util.concurrent.TimeUnit
import android.app.NotificationManager
import android.provider.Settings
import android.text.TextUtils

class WebSocketService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val gson = Gson()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false  // Prevent concurrent connection attempts
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 60_000L // 1 minute
    private val connectionLock = Any()  // Lock for connection operations

    // WakeLock to keep CPU awake during critical operations (not persistent)
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockTimeout = 30_000L // 30 seconds max for message processing
    
    // Notification update
    private var lastMessageTime: Long = 0L
    private var messageCount: Int = 0
    
    // Network callback for monitoring connectivity
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Flag to track foreground service mode
    @Volatile
    private var isForegroundMode = true

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES) // No timeout for WebSocket
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        // Check if foreground notification should be shown
        val app = application as AccnotifyApp
        isForegroundMode = app.keyManager.showForegroundNotification

        // Register network callback
        registerNetworkCallback()

        // Must always call startForeground() when started via startForegroundService(),
        // otherwise Android O+ will crash the app after 5 seconds.
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        if (!isForegroundMode) {
            // User opted out of the persistent notification — remove it immediately
            // while keeping the service alive.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            Log.i(TAG, "Service started in background mode (without notification)")
        } else {
            Log.i(TAG, "Service started in foreground mode (with notification)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_SEND_ACK -> {
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
                messageId?.let { sendAck(it) }
            }
            null -> {
                // Service restarted by system (START_STICKY), auto-reconnect
                Log.i(TAG, "Service restarted by system, auto-connecting")
                connect()
            }
        }
        
        // Return START_STICKY to restart service if killed
        // Return START_REDELIVER_INTENT to also redeliver the last intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "Service onDestroy")
        
        // Unregister network callback
        unregisterNetworkCallback()
        
        // Release WakeLock
        releaseWakeLock()
        
        disconnect()
        scope.cancel()
        
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - app was swiped away")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Acquire a temporary WakeLock for critical operations like message processing.
     * The WakeLock automatically releases after timeout to prevent battery drain.
     */
    private fun acquireTemporaryWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$TAG::MessageWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(wakeLockTimeout)
            Log.d(TAG, "Temporary WakeLock acquired for ${wakeLockTimeout}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }
    
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available - reconnecting")
                scope.launch {
                    delay(1000) // Wait a bit for network to stabilize
                    if (!isConnected) {
                        connect()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                isConnected = false
                broadcastConnectionStatus(false)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    private fun connect() {
        synchronized(connectionLock) {
            // Prevent concurrent connection attempts
            if (isConnected || isConnecting) {
                Log.d(TAG, "Already connected or connecting, skipping")
                return
            }
            isConnecting = true
        }

        val app = AccnotifyApp.getInstance()
        val keyManager = app.keyManager
        val deviceKey = keyManager.getDeviceKey()
        if (deviceKey == null) {
            synchronized(connectionLock) { isConnecting = false }
            return
        }
        val serverUrl = keyManager.serverUrl

        // Convert HTTP to WS
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws?key=$deviceKey"

        Log.i(TAG, "Connecting to WebSocket: ${wsUrl.substringBefore("?")}")

        // Close existing connection before creating a new one
        synchronized(connectionLock) {
            webSocket?.let {
                try {
                    it.close(1000, "Reconnecting")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing old WebSocket", e)
                }
            }
            webSocket = null
        }

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val newWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(connectionLock) {
                    // Only accept this connection if it's still the current one
                    if (this@WebSocketService.webSocket == webSocket) {
                        Log.i(TAG, "WebSocket connected")
                        isConnected = true
                        isConnecting = false
                        reconnectAttempt = 0
                        broadcastConnectionStatus(true)
                    } else {
                        Log.w(TAG, "Ignoring stale onOpen callback")
                        webSocket.close(1000, "Stale connection")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(connectionLock) {
                    if (this@WebSocketService.webSocket == webSocket) {
                        Log.i(TAG, "WebSocket closed: $code $reason")
                        isConnected = false
                        isConnecting = false
                        this@WebSocketService.webSocket = null
                        broadcastConnectionStatus(false)
                        scheduleReconnect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(connectionLock) {
                    if (this@WebSocketService.webSocket == webSocket) {
                        Log.e(TAG, "WebSocket failure", t)
                        isConnected = false
                        isConnecting = false
                        this@WebSocketService.webSocket = null
                        broadcastConnectionStatus(false)
                        scheduleReconnect()
                    } else {
                        Log.d(TAG, "Ignoring failure from stale connection")
                    }
                }
            }
        })

        synchronized(connectionLock) {
            webSocket = newWebSocket
        }
    }

    private fun disconnect() {
        synchronized(connectionLock) {
            webSocket?.close(1000, "User requested disconnect")
            webSocket = null
            isConnected = false
            isConnecting = false
        }
    }

    private fun scheduleReconnect() {
        val delay = minOf(
            1000L * (1 shl minOf(reconnectAttempt, 6)), // Exponential backoff
            maxReconnectDelay
        )
        reconnectAttempt++

        Log.i(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempt)")

        scope.launch {
            delay(delay)
            if (!isConnected) {
                connect()
            }
        }
    }

    private fun handleMessage(text: String) {
        Log.d(TAG, "Received message: ${text.take(200)}")
        
        // Acquire temporary WakeLock to ensure message is fully processed
        acquireTemporaryWakeLock()
        
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return

            Log.d(TAG, "Message type: $type")
            when (type) {
                "message" -> handlePushMessage(json)
                "ping" -> sendPong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        } finally {
            // Note: WakeLock has timeout, no need to explicitly release here
            // This ensures message processing completes even if there are delays
        }
    }

    private fun handlePushMessage(json: JsonObject) {
        Log.i(TAG, "Handling push message: ${json.get("id")}")
        val app = AccnotifyApp.getInstance()
        val messageId = json.get("id")?.asString ?: return
        val data = json.getAsJsonObject("data") ?: return
        Log.i(TAG, "Push data: title=${data.get("title")}, body=${data.get("body")}")
        
        // Update last message time and count for notification display
        lastMessageTime = System.currentTimeMillis()
        messageCount++
        updateForegroundNotification()

        var title = data.get("title")?.asString
        var body = data.get("body")?.asString
        val group = data.get("group")?.asString
        val icon = data.get("icon")?.asString
        val url = data.get("url")?.asString
        var image = data.get("image")?.asString
        val sound = data.get("sound")?.asString
        val badge = data.get("badge")?.asInt ?: 0
        val encryptedContent = data.get("encrypted_content")?.asString

        // Try to decrypt if encrypted
        var decryptedContent: String? = null
        if (!encryptedContent.isNullOrEmpty()) {
            val privateKey = app.keyManager.getPrivateKey()
            decryptedContent = E2ECrypto.tryDecrypt(encryptedContent, privateKey)

            // Parse decrypted content
            if (decryptedContent != null) {
                try {
                    val decrypted = gson.fromJson(decryptedContent, JsonObject::class.java)
                    title = decrypted.get("title")?.asString ?: title
                    body = decrypted.get("body")?.asString ?: body
                    image = decrypted.get("image")?.asString ?: image
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing decrypted content", e)
                }
            }
        }

        // Save to database
        scope.launch {
            val message = Message(
                messageId = messageId,
                title = title,
                body = body,
                group = group,
                icon = icon,
                url = url,
                image = image,
                sound = sound,
                badge = badge,
                encryptedContent = encryptedContent,
                decryptedContent = decryptedContent,
                timestamp = Date()
            )
            app.database.messageDao().insert(message)
        }

        // Show notification
        NotificationHelper.showNotification(
            context = this,
            messageId = messageId,
            title = title ?: "Accnotify",
            body = body ?: "",
            group = group,
            url = url
        )

        // Send ACK
        sendAck(messageId)
    }

    private fun sendAck(messageId: String) {
        val ack = JsonObject().apply {
            addProperty("type", "ack")
            addProperty("id", messageId)
        }
        webSocket?.send(gson.toJson(ack))
    }

    private fun sendPong() {
        val pong = JsonObject().apply {
            addProperty("type", "pong")
            addProperty("timestamp", System.currentTimeMillis() / 1000)
        }
        webSocket?.send(gson.toJson(pong))
    }

    private fun broadcastConnectionStatus(connected: Boolean) {
        val intent = Intent(ACTION_CONNECTION_STATUS).apply {
            putExtra(EXTRA_CONNECTED, connected)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createForegroundNotification(): Notification {
        return buildForegroundNotification()
    }
    
    private fun updateForegroundNotification() {
        if (!isForegroundMode) return // Skip if not in foreground mode
        val notification = buildForegroundNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Simple status text - keep it minimal
        val statusText = if (isConnected) "运行中" else "连接中..."
        
        // Build minimal notification
        return NotificationCompat.Builder(this, AccnotifyApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // 最低优先级
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // 锁屏不显示
            .setSilent(true)  // 静音
            .build()
    }
    
    /**
     * Check if the accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            0
        }
        
        if (accessibilityEnabled != 1) return false
        
        val serviceString = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(serviceString)
        
        val expectedService = "${packageName}/${KeepAliveAccessibilityService::class.java.canonicalName}"
        
        while (colonSplitter.hasNext()) {
            val service = colonSplitter.next()
            if (service.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        
        return false
    }

    companion object {
        private const val TAG = "WebSocketService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.trah.accnotify.action.CONNECT"
        const val ACTION_DISCONNECT = "com.trah.accnotify.action.DISCONNECT"
        const val ACTION_SEND_ACK = "com.trah.accnotify.action.SEND_ACK"
        const val ACTION_CONNECTION_STATUS = "com.trah.accnotify.action.CONNECTION_STATUS"

        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_CONNECTED = "connected"

        /**
         * Helper to start the service properly
         */
        fun start(context: Context) {
            val intent = Intent(context, WebSocketService::class.java).apply {
                action = ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
