package org.cheertok.kodibridge.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Envía tokens de acción al móvil por el Data Layer (Bluetooth, sin WiFi).
 *
 * El payload es el token tal cual lo entiende KodiClient en el móvil:
 * "Input.Up", "Input.Select", "Player.PlayPause", "addon:<id>"… El móvil lo
 * recibe en [org.cheertok.kodibridge.KodiWearListener] y lo reenvía a Kodi.
 */
class KodiMessenger(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)

    // Nodo del móvil cacheado; se re-resuelve si falla un envío o cambia la conexión.
    @Volatile private var phoneNodeId: String? = null

    /** Callback opcional para que la UI muestre el estado de la conexión. */
    var onConnected: ((Boolean) -> Unit)? = null

    private val capListener = CapabilityClient.OnCapabilityChangedListener { info ->
        phoneNodeId = pickNode(info.nodes)
        onConnected?.invoke(phoneNodeId != null)
        phoneNodeId?.let { reportVersion(it) }
    }

    fun start() {
        capabilityClient.addListener(capListener, CAPABILITY)
        scope.launch(Dispatchers.IO) { resolveNode() }
    }

    fun stop() {
        capabilityClient.removeListener(capListener)
    }

    /** Envía un token de acción. Resuelve el nodo si hace falta y reintenta una vez. */
    fun send(token: String) {
        scope.launch(Dispatchers.IO) {
            var node = phoneNodeId ?: resolveNode()
            if (node == null) { onConnected?.invoke(false); return@launch }
            try {
                messageClient.sendMessage(node, PATH_CMD, token.toByteArray()).await()
                onConnected?.invoke(true)
            } catch (e: Exception) {
                // El nodeId pudo cambiar tras reconexión: re-resuelve y reintenta.
                node = resolveNode()
                if (node == null) { onConnected?.invoke(false); return@launch }
                try {
                    messageClient.sendMessage(node, PATH_CMD, token.toByteArray()).await()
                    onConnected?.invoke(true)
                } catch (e2: Exception) {
                    Log.w(TAG, "envío fallido: ${e2.message}")
                    onConnected?.invoke(false)
                }
            }
        }
    }

    /** Descubre el nodo del móvil que anuncia la capability del puente. */
    private suspend fun resolveNode(): String? {
        return try {
            val info = capabilityClient
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val id = pickNode(info.nodes)
            phoneNodeId = id
            onConnected?.invoke(id != null)
            id?.let { reportVersion(it) }
            id
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo resolver el nodo: ${e.message}")
            null
        }
    }

    /** Anuncia al móvil la versión instalada de la app del reloj, para que pueda avisar
     *  si se ha quedado desactualizada respecto al wear.apk que el móvil empaqueta. */
    private fun reportVersion(node: String) {
        scope.launch(Dispatchers.IO) {
            try {
                messageClient.sendMessage(node, PATH_VERSION, BuildConfig.VERSION_CODE.toString().toByteArray()).await()
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo anunciar la versión: ${e.message}")
            }
        }
    }

    private fun pickNode(nodes: Collection<com.google.android.gms.wearable.Node>): String? =
        nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id

    companion object {
        private const val TAG = "KodiMessenger"
        const val CAPABILITY = "cheertok_kodi_phone"
        const val PATH_CMD = "/kodi/cmd"
        const val PATH_VERSION = "/kodi/version"  // anuncio de la versión del reloj al móvil
    }
}
