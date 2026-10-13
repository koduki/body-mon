package com.master.healthcoach.data.mcp

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.master.healthcoach.HealthCoachApplication
import com.master.healthcoach.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

 data class TunnelState(val status: String = "stopped", val expiresAt: Long = 0) {
    val running: Boolean get() = status in setOf("starting", "connected", "reconnecting")
}

class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var deadline = 0L
    private var stopReason = "stopped"
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) end("locked") }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "ChatGPT連携", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START) { end("stopped"); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        try {
            val app = (application as HealthCoachApplication).container
            val config = app.tunnelSettings.load()
            check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            deadline = SystemClock.elapsedRealtime() + config.minutes * 60_000L
            val expiresAt = System.currentTimeMillis() + config.minutes * 60_000L
            accepting.set(true)
            mutableState.value = TunnelState("starting", expiresAt)
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val startedCpu = Process.getElapsedCpuTime()
            val battery = getSystemService(BatteryManager::class.java)
            val batteryStart = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toLong()
            val chargingStart = if (battery.isCharging) 1L else 0L
            val started = SystemClock.elapsedRealtime()
            job = scope.launch {
                val backend = HealthMcpBackend(app.database, app.repository, app.diagnostics, { app.tunnelSettings.permissions() }, { expiresAt })
                fun isActiveSession() = accepting.get() && SystemClock.elapsedRealtime() < deadline && app.tunnelSettings.permissions() == config.permissions
                val transport = TunnelTransport(config, ::isActiveSession, { request ->
                    if (!isActiveSession()) throw CancellationException("Session ended")
                    val start = SystemClock.elapsedRealtime()
                    val response = McpProtocol.dispatch(request, backend::tools, backend::call)
                    app.diagnostics.event("mcp", "success", SystemClock.elapsedRealtime() - start)
                    if (!isActiveSession()) throw CancellationException("Session ended")
                    response
                }, { status -> if (isActiveSession()) mutableState.value = TunnelState(status, expiresAt) })
                app.diagnostics.event("tunnel", "started")
                try {
                    withTimeout(config.minutes * 60_000L) { transport.run() }
                } catch (_: TimeoutCancellationException) { stopReason = "expired"
                } catch (_: CancellationException) { /* explicit stop */
                } catch (_: TunnelAuthFailure) { stopReason = "auth"
                } catch (_: Exception) { stopReason = "failure"
                } finally {
                    accepting.set(false)
                    withContext(NonCancellable) {
                        val c = transport.counters
                        app.diagnostics.event("tunnel", stopReason, SystemClock.elapsedRealtime() - started, counters = mapOf(
                            "polls" to c.polls, "emptyPolls" to c.emptyPolls, "retries" to c.retries, "bytes" to c.bytes, "tools" to c.tools, "errors" to c.errors,
                            "batteryStart" to batteryStart, "batteryEnd" to battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toLong(),
                            "chargingStart" to chargingStart, "chargingEnd" to if (battery.isCharging) 1L else 0L,
                            "cpuMs" to Process.getElapsedCpuTime() - startedCpu,
                        ))
                    }
                    withContext(NonCancellable + Dispatchers.Main) { mutableState.value = TunnelState(stopReason); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        } catch (_: Exception) {
            accepting.set(false); mutableState.value = TunnelState("start_failed"); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ChatGPT連携中").setContentText("選択した情報を共有中。期限または画面ロックで終了します")
            .setContentIntent(open).setOngoing(true).addAction(0, "停止", stop).build()
    }
    private fun end(reason: String) {
        stopReason = reason; accepting.set(false); job?.cancel()
        if (job == null) { mutableState.value = TunnelState(reason); stopSelf() }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { end("os_timeout"); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { end("task_removed") }
    override fun onDestroy() {
        accepting.set(false)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        scope.cancel()
        if (mutableState.value.running) mutableState.value = TunnelState("stopped")
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "health-mcp-tunnel"
        private const val NOTIFICATION = 901
        private const val START = "com.master.healthcoach.TUNNEL_START"
        private const val STOP = "com.master.healthcoach.TUNNEL_STOP"
        private val accepting = AtomicBoolean(false)
        private val mutableState = MutableStateFlow(TunnelState())
        val state = mutableState.asStateFlow()
        fun start(context: Context) {
            if (state.value.running) return
            try { ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java).setAction(START)) }
            catch (_: Exception) { mutableState.value = TunnelState("start_failed") }
        }
        fun stop(context: Context) {
            accepting.set(false)
            if (state.value.running) context.startService(Intent(context, TunnelService::class.java).setAction(STOP))
        }
    }
}
