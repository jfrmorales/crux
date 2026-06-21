package org.cheertok.kodibridge

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * UserService que Shizuku ejecuta como `shell`. Lanza el binario nativo
 * AUTOCONTENIDO (libbridge.so) que hace TODO el puente (agarra el CheerTok,
 * mapea y habla con Kodi). Aqui solo se arranca/para y se lee su estado.
 *
 * Es EXACTAMENTE el mismo binario que en el OPPO se lanza con LADB, por eso el
 * comportamiento es identico en Samsung (via Shizuku) y OPPO (via LADB).
 */
class BridgeUserService : IUserService.Stub {

    constructor()

    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var proc: Process? = null

    @Volatile private var actions = 0L
    @Volatile private var lastAction = "-"
    @Volatile private var devicesInfo = "?"
    @Volatile private var kodiOk = false
    @Volatile private var grabbing = false

    private lateinit var cmd: Array<String>

    override fun destroy() { stopBridge(); exitProcess(0) }
    override fun exit() = destroy()

    override fun startBridge(config: String) {
        if (running) return
        val c = JSONObject(config)
        val user = c.optString("user", "kodi")
        val pass = c.optString("pass", "kodi")
        val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        cmd = arrayOf(
            c.optString("bridge", "bridge"),
            "--host", c.optString("host", "127.0.0.1"),
            "--port", c.optInt("port", 8080).toString(),
            "--auth", auth,
            "--step", c.optInt("step", 160).toString(),
            "--volup", c.optString("volup", "VolUp"),
            "--voldown", c.optString("voldown", "VolDown"),
        )
        running = true
        worker = Thread({ runLoop() }, "cheertok-bridge").apply { isDaemon = true; start() }
        Log.i(TAG, "startBridge ${cmd.joinToString(" ")}")
    }

    override fun stopBridge() {
        running = false
        proc?.destroy(); proc = null
        worker = null
        grabbing = false
    }

    override fun getStatus(): String {
        val state = if (running) "ACTIVO" else "parado"
        val grab = if (grabbing) "cursor oculto" else "esperando mando"
        val net = if (kodiOk) "Kodi OK" else "Kodi sin respuesta"
        return "$state | $grab | $devicesInfo | acciones: $actions | últ: $lastAction | $net"
    }

    private fun runLoop() {
        while (running) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                proc = p
                BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                    var line: String? = null
                    while (running && br.readLine().also { line = it } != null) parseLine(line!!)
                }
            } catch (e: Exception) {
                Log.e(TAG, "exec error", e)
                devicesInfo = "error: ${e.message}"
            } finally {
                grabbing = false
                proc?.destroy(); proc = null
            }
            if (running) sleep(1000)
        }
    }

    private fun parseLine(line: String) {
        when {
            line.startsWith("READY") -> {
                grabbing = true
                devicesInfo = line.substringAfter("devices=", "?").let { "$it devices" }
            }
            line.startsWith("ACT ") -> {
                val parts = line.split(' ')
                if (parts.size >= 3) {
                    actions++
                    lastAction = parts[1]
                    kodiOk = parts[2] == "1"
                }
            }
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}

    private companion object { const val TAG = "CheerTokBridge" }
}
