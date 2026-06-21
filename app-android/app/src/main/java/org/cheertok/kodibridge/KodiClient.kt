package org.cheertok.kodibridge

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente minimo de JSON-RPC de Kodi. Envia acciones de navegacion por HTTP a
 * localhost (o donde corra Kodi). Corre dentro del UserService de Shizuku, que
 * tiene acceso de red.
 */
class KodiClient(
    @Volatile var host: String,
    @Volatile var port: Int,
    user: String,
    pass: String,
) {
    @Volatile
    var auth: String = basic(user, pass)
        private set

    private var id = 0

    fun setCredentials(user: String, pass: String) {
        auth = basic(user, pass)
    }

    /** Ejecuta una accion (token). Devuelve true si Kodi respondio 200. */
    fun send(action: String): Boolean {
        if (action == "none" || action.isBlank()) return true
        // "addon:plugin.video.palantir3" -> abre ese addon directamente.
        if (action.startsWith("addon:")) {
            val addonId = action.removePrefix("addon:")
            return post(
                """{"jsonrpc":"2.0","method":"Addons.ExecuteAddon","params":{"addonid":"$addonId"},"id":${++id}}"""
            )
        }
        val body = when (action) {
            "Player.PlayPause" ->
                """{"jsonrpc":"2.0","method":"Player.PlayPause","params":{"playerid":1},"id":${++id}}"""
            "VolUp" ->
                """{"jsonrpc":"2.0","method":"Application.SetVolume","params":{"volume":"increment"},"id":${++id}}"""
            "VolDown" ->
                """{"jsonrpc":"2.0","method":"Application.SetVolume","params":{"volume":"decrement"},"id":${++id}}"""
            else ->
                """{"jsonrpc":"2.0","method":"$action","id":${++id}}"""
        }
        return post(body)
    }

    /** Lista los addons de tipo plugin (para el selector de la UI). Devuelve
     *  pares (id, nombre). Vacio si Kodi no responde. */
    fun listPluginAddons(): List<Pair<String, String>> {
        val resp = postRead(
            """{"jsonrpc":"2.0","method":"Addons.GetAddons","params":{"type":"xbmc.python.pluginsource","properties":["name"]},"id":${++id}}"""
        ) ?: return emptyList()
        val result = ArrayList<Pair<String, String>>()
        try {
            val addons = org.json.JSONObject(resp).getJSONObject("result").getJSONArray("addons")
            for (i in 0 until addons.length()) {
                val a = addons.getJSONObject(i)
                val name = a.optString("name", a.optString("addonid"))
                    .replace(Regex("\\[/?COLOR[^\\]]*\\]"), "").trim()
                result.add(a.getString("addonid") to name)
            }
        } catch (e: Exception) { /* ignore */ }
        return result
    }

    /** Desactiva el cursor del raton en Kodi (para que no se vea ni interfiera). */
    fun disableMouse(): Boolean = post(
        """{"jsonrpc":"2.0","method":"Settings.SetSettingValue","params":{"setting":"input.enablemouse","value":false},"id":${++id}}"""
    )

    /** Comprueba conectividad con Kodi (JSONRPC.Ping). */
    fun ping(): Boolean = post(
        """{"jsonrpc":"2.0","method":"JSONRPC.Ping","id":${++id}}"""
    )

    private fun post(body: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("http://$host:$port/jsonrpc").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", auth)
                doOutput = true
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.inputStream.use { it.readBytes() }
            code in 200..299
        } catch (e: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }

    /** Como post() pero devuelve el cuerpo de la respuesta (o null si falla). */
    private fun postRead(body: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("http://$host:$port/jsonrpc").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 4000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", auth)
                doOutput = true
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { String(it.readBytes()) }
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private companion object {
        fun basic(user: String, pass: String): String =
            "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
    }
}
