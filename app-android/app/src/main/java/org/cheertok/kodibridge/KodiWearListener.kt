package org.cheertok.kodibridge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.Executors

/**
 * Recibe los comandos del mando del reloj (Wear OS) por el Data Layer y los
 * reenvía a Kodi reutilizando [KodiClient].
 *
 * Camino: reloj → MessageClient (Bluetooth, sin WiFi) → este servicio →
 * KodiClient.send(token) → Kodi (JSON-RPC en localhost). No necesita root, ADB,
 * /dev/input ni el BridgeService del CheerTok.
 *
 * El payload del mensaje es directamente el token de acción que KodiClient ya
 * entiende: "Input.Up", "Input.Select", "Player.PlayPause", "addon:<id>"…
 */
class KodiWearListener : WearableListenerService() {

    // Un solo hilo: serializa los comandos y evita crear un Thread por pulsación
    // (la corona puede emitir varios por segundo).
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var client: KodiClient? = null
    private var cfgSig: String = ""

    override fun onMessageReceived(event: MessageEvent) {
        // El reloj anuncia su versión: la guardamos para que la UI del móvil pueda avisar
        // si la app del reloj se ha quedado desactualizada respecto al wear.apk empaquetado.
        if (event.path == PATH_VERSION) {
            val v = String(event.data).trim().toIntOrNull() ?: return
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putInt("watch_app_version", v).apply()
            Log.i(TAG, "reloj anuncia versión $v")
            return
        }
        if (event.path != PATH_CMD) return
        val raw = String(event.data).trim()
        if (raw.isEmpty()) return
        // Los gestos "Ok2"/"Ok3" (doble/triple toque en el OK del reloj) no son acciones:
        // el móvil decide qué hacen según la config del usuario. Resto: token directo.
        val token = mapGesture(raw)
        if (token.isEmpty() || token == TOKEN_NONE) {
            Log.i(TAG, "gesto '$raw' -> sin acción asignada")
            return
        }
        // "OpenKodi" no es JSON-RPC: trae Kodi al primer plano en el móvil.
        if (token == TOKEN_OPEN_KODI) { launchKodi(); return }
        val c = clientFromPrefs()
        io.execute {
            val ok = c.send(token)
            Log.i(TAG, "cmd '$token' (de '$raw') -> Kodi ok=$ok")
        }
    }

    /** Traduce un gesto del reloj a la acción configurada; los demás tokens pasan tal cual. */
    private fun mapGesture(raw: String): String {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return when (raw) {
            GESTURE_OK_DOUBLE -> p.getString("ok_double", "Input.Back")!!
            GESTURE_OK_TRIPLE -> p.getString("ok_triple", TOKEN_NONE)!!
            else -> raw
        }
    }

    /** Lanza Kodi en el móvil (mismo mecanismo que BridgeService). Requiere el
     *  permiso «mostrar sobre otras apps» para lanzar desde segundo plano. */
    private fun launchKodi() {
        try {
            val i = packageManager.getLaunchIntentForPackage(BridgeService.KODI_PKG) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
            Log.i(TAG, "OpenKodi -> lanzado")
        } catch (e: Exception) {
            Log.w(TAG, "OpenKodi falló: ${e.message}")
        }
    }

    /** Construye (o reutiliza) el KodiClient según la config actual del usuario. */
    private fun clientFromPrefs(): KodiClient {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val host = p.getString("host", "127.0.0.1")!!
        val port = p.getString("port", "8080")!!.toIntOrNull() ?: 8080
        val user = p.getString("user", "kodi")!!
        val pass = p.getString("pass", "kodi")!!
        val sig = "$host:$port:$user:$pass"
        var c = client
        if (c == null || sig != cfgSig) {
            c = KodiClient(host, port, user, pass)
            client = c
            cfgSig = sig
        }
        return c
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KodiWearListener"
        const val PATH_CMD = "/kodi/cmd"
        const val PATH_VERSION = "/kodi/version"
        const val TOKEN_OPEN_KODI = "OpenKodi"
        const val TOKEN_NONE = "none"
        // Gestos del OK del reloj (acción configurable desde la app del móvil).
        const val GESTURE_OK_DOUBLE = "Ok2"
        const val GESTURE_OK_TRIPLE = "Ok3"
    }
}
