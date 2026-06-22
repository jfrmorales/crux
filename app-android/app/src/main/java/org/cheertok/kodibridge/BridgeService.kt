package org.cheertok.kodibridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Servicio en primer plano que mantiene vivo el puente (aunque se cierre la app) y
 * muestra una NOTIFICACIÓN PERSISTENTE con el estado del mando: aparece al
 * conectarse el CheerTok y refleja si se desconecta/reconecta.
 *
 * Lee la salida del binario (`READY` = mando agarrado; "esperando/desconectado" =
 * sin mando) y actualiza la notificación en consecuencia.
 */
class BridgeService : Service() {

    private var worker: Thread? = null
    @Volatile private var proc: Process? = null
    @Volatile private var running = false
    @Volatile private var openKodi = false
    @Volatile private var wasConnected = false

    // Temporizador de inactividad: si el CheerTok no se agarra durante IDLE_TIMEOUT_MS
    // (uso típico: se controla por el reloj), el servicio se autodetiene para soltar ADB,
    // el proceso remoto y el foreground service, y dejar que el móvil entre en reposo.
    private val main = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { idleShutdown() }
    private var idleArmed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopBridge(); stopSelf(); return START_NOT_STICKY }

        val connAddr = intent?.getStringExtra("connAddr") ?: return START_NOT_STICKY
        val bridgeCmd = intent.getStringExtra("bridgeCmd") ?: return START_NOT_STICKY
        val adbPath = intent.getStringExtra("adbPath") ?: return START_NOT_STICKY
        openKodi = intent.getBooleanExtra("openKodi", false)

        startForeground(NOTIF_ID, buildNotification("Conectando…", false))
        stopBridge()  // si llega un nuevo arranque (p. ej. cambió la config), reinicia
        startBridge(connAddr, bridgeCmd, adbPath)
        armIdleTimer()  // arranca "desconectado": cuenta atrás hasta tener un CheerTok
        // START_NOT_STICKY: no se resucita con intent nulo (que dejaría un FGS zombi sin
        // rearrancar el puente). El usuario reactiva con «Iniciar» cuando quiera el CheerTok.
        return START_NOT_STICKY
    }

    /** Arma la cuenta atrás de inactividad al entrar en estado desconectado. Idempotente:
     *  el puente nativo repite "esperando CheerTok" cada 2 s, así que NO se debe resetear
     *  en cada línea (si no, nunca dispararía); solo cuenta desde la primera. */
    private fun armIdleTimer() {
        if (idleArmed) return
        idleArmed = true
        main.postDelayed(idleStop, IDLE_TIMEOUT_MS)
    }

    /** Cancela la cuenta atrás. Se llama al agarrar el CheerTok (READY) y al parar. */
    private fun cancelIdleTimer() {
        idleArmed = false
        main.removeCallbacks(idleStop)
    }

    /** Sin CheerTok durante el umbral: suelta todo y para el servicio. */
    private fun idleShutdown() {
        idleArmed = false
        update("⏸️ En pausa por inactividad", false)
        stopBridge()
        stopSelf()
    }

    private fun startBridge(connAddr: String, bridgeCmd: String, adbPath: String) {
        if (running) return
        running = true
        statusText = "conectando…"; connected = false
        worker = Thread {
            val adb = AdbWireless(this, adbPath)
            val hp = parseAddr(connAddr)
            if (hp == null) { update("dirección de conexión inválida", false); return@Thread }
            // Bucle resistente: si la conexión adb se cae, reconecta y relanza. Para no
            // martillear adb cada pocos segundos cuando no hay conexión (sin WiFi, sin
            // tcpip), espera con backoff exponencial con tope; se resetea tras un READY.
            var backoffMs = BACKOFF_MIN_MS
            while (running) {
                if (!adb.connect(hp)) {
                    // El servidor adb pudo no estar arrancado: levántalo y reintenta una vez.
                    adb.startServer()
                    if (!adb.connect(hp)) {
                        update("no conecta (¿vinculado? ¿WiFi?)", false)
                        sleep(backoffMs); backoffMs = nextBackoff(backoffMs); continue
                    }
                }
                val p = adb.runBridge(hp, bridgeCmd)
                proc = p
                if (p == null) {
                    update("no arrancó el puente", false)
                    sleep(backoffMs); backoffMs = nextBackoff(backoffMs); continue
                }
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                        for (line in lines) { parseLine(line); backoffMs = BACKOFF_MIN_MS }
                    }
                } catch (e: Exception) {}
                proc = null
                if (running) { update("⚠️ conexión perdida — reconectando…", false); sleep(backoffMs); backoffMs = nextBackoff(backoffMs) }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}

    private fun nextBackoff(ms: Long) = (ms * 2).coerceAtMost(BACKOFF_MAX_MS)

    private fun parseLine(line: String) {
        when {
            line.startsWith("READY") -> {
                // Transición desconectado → conectado: abre Kodi si está activado.
                if (!wasConnected && openKodi) launchKodi()
                wasConnected = true
                main.post { cancelIdleTimer() }  // hay mando: no autodetener
                update("🎮 Mando conectado", true)
            }
            line.contains("esperando CheerTok") -> { wasConnected = false; main.post { armIdleTimer() }; update("⚠️ Mando desconectado — esperando…", false) }
            line.contains("desconectado") -> { wasConnected = false; main.post { armIdleTimer() }; update("⚠️ Mando desconectado — reconectando…", false) }
        }
    }

    /** Trae Kodi al primer plano (requiere permiso «mostrar sobre otras apps» si la
     *  app no está en primer plano). */
    private fun launchKodi() {
        try {
            val i = packageManager.getLaunchIntentForPackage(KODI_PKG) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
        } catch (e: Exception) {}
    }

    private fun stopBridge() {
        running = false
        cancelIdleTimer()
        proc?.destroy(); proc = null
        worker = null
        connected = false; statusText = "parado"
    }

    override fun onDestroy() { stopBridge(); super.onDestroy() }

    // ---- notificación ----

    private fun update(text: String, conn: Boolean) {
        statusText = text; connected = conn
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, conn))
    }

    private fun buildNotification(text: String, conn: Boolean): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = if (conn) android.R.drawable.ic_media_play else android.R.drawable.stat_sys_warning
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("CheerTok → Kodi")
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Estado del puente", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Estado del mando CheerTok" }
                )
            }
        }
    }

    private fun parseAddr(s: String): AdbWireless.HostPort? {
        val i = s.lastIndexOf(':'); if (i <= 0) return null
        val host = s.substring(0, i).trim(); val port = s.substring(i + 1).trim().toIntOrNull() ?: return null
        return AdbWireless.HostPort(host, port)
    }

    companion object {
        const val CHANNEL = "bridge"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "org.cheertok.kodibridge.STOP"
        const val KODI_PKG = "org.xbmc.kodi"
        // Auto-parada: sin CheerTok agarrado durante este tiempo → soltar ADB y parar.
        const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L
        // Backoff de reconexión: empieza en 3 s y dobla hasta 60 s.
        const val BACKOFF_MIN_MS = 3000L
        const val BACKOFF_MAX_MS = 60000L
        // Estado compartido que la UI lee para mostrarlo.
        @Volatile var statusText: String = "parado"
        @Volatile var connected: Boolean = false
    }
}
