package org.cheertok.kodibridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mecanismo tipo LADB SIN apps extra ni Shizuku: usa la depuracion inalambrica del
 * propio movil. Ejecuta el binario `adb` empaquetado (libadb.so) contra el adbd del
 * dispositivo (descubierto por mDNS), consiguiendo una shell con uid `shell` capaz
 * de lanzar el puente. Funciona en OPPO y Samsung por igual.
 *
 * Flujo: startServer → (descubrir pairing por mDNS) → pair(code) → (descubrir
 * connect por mDNS) → connect → runBridge.
 */
class AdbWireless(private val ctx: Context, private val adbPath: String) {

    private val home: String = ctx.filesDir.absolutePath
    private val nsd by lazy { ctx.getSystemService(Context.NSD_SERVICE) as NsdManager }

    data class HostPort(val host: String, val port: Int)

    /** Salida cruda del último pair/connect, para poder mostrar el error real de adb
     *  (p. ej. "Connection refused", "failed to authenticate", "timed out") en vez de un
     *  mensaje genérico. */
    @Volatile var lastOutput: String = ""
        private set

    /** Env común: HOME propio (clave adb), puerto de servidor adb EXCLUSIVO de la
     *  app (evita colisiones con cualquier otro adb), y mDNS desactivado en el
     *  cliente (en Android falla y rompe el `pair`). */
    private fun applyEnv(pb: ProcessBuilder) {
        val e = pb.environment()
        e["HOME"] = home
        e["ANDROID_USER_HOME"] = "$home/.android"
        e["TMPDIR"] = ctx.cacheDir.absolutePath
        e["ANDROID_ADB_SERVER_PORT"] = ADB_PORT.toString()
        e["ADB_MDNS"] = "0"
    }

    /** Ejecuta libadb.so con argumentos. Devuelve (exit, salida combinada). */
    private fun run(vararg args: String, timeoutMs: Long = 15000): Pair<Int, String> {
        return try {
            File("$home/.android").mkdirs()
            val pb = ProcessBuilder(listOf(adbPath) + args)
            applyEnv(pb)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            try { p.exitValue() } catch (e: Exception) { p.destroy(); -1 } to out
        } catch (e: Exception) {
            Log.e(TAG, "run error", e); -1 to (e.message ?: "error")
        }
    }

    fun startServer() = run("start-server", timeoutMs = 8000)

    fun pair(hp: HostPort, code: String): Boolean {
        val (_, out) = run("pair", "${hp.host}:${hp.port}", code, timeoutMs = 20000)
        Log.i(TAG, "pair -> $out")
        lastOutput = out.trim()
        return out.contains("Successfully paired")
    }

    fun connect(hp: HostPort): Boolean {
        val (_, out) = run("connect", "${hp.host}:${hp.port}", timeoutMs = 12000)
        Log.i(TAG, "connect -> $out")
        lastOutput = out.trim()
        return out.contains("connected to")
    }

    /**
     * Pone el adbd del dispositivo en modo TCP escuchando en TODAS las interfaces
     * (incluido 127.0.0.1). Así, una vez hecho con WiFi, la app puede conectar por
     * loopback SIN WiFi (datos móviles). Persiste hasta reiniciar el móvil.
     */
    fun tcpip(current: HostPort, port: Int): Boolean {
        val (_, out) = run("-s", "${current.host}:${current.port}", "tcpip", port.toString(), timeoutMs = 8000)
        Log.i(TAG, "tcpip -> $out")
        return out.contains("restarting in TCP mode")
    }

    /** Instala (o actualiza, -r) un APK en el dispositivo conectado. Para instalar la
     *  app del reloj desde el móvil reutilizando este mismo adb. */
    fun install(hp: HostPort, apkPath: String): Pair<Boolean, String> {
        val (_, out) = run("-s", "${hp.host}:${hp.port}", "install", "-r", apkPath, timeoutMs = 180000)
        Log.i(TAG, "install -> $out")
        return out.contains("Success") to out.trim()
    }

    /** Lanza el puente por la shell remota; Process de larga vida (lee stdout). */
    fun runBridge(hp: HostPort, bridgeCmd: String): Process? = try {
        File("$home/.android").mkdirs()
        val pb = ProcessBuilder(adbPath, "-s", "${hp.host}:${hp.port}", "shell", bridgeCmd)
        applyEnv(pb)
        pb.redirectErrorStream(true)
        pb.start()
    } catch (e: Exception) { Log.e(TAG, "runBridge error", e); null }

    /** Descubre por mDNS un servicio adb y lo resuelve a host:puerto (o null). */
    fun discover(serviceType: String, timeoutMs: Long = 8000): HostPort? {
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<HostPort?>(null)
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo?, code: Int) {}
            override fun onServiceResolved(s: NsdServiceInfo) {
                val host = s.host?.hostAddress ?: return
                result.set(HostPort(host, s.port)); latch.countDown()
            }
        }
        val discListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION") nsd.resolveService(s, resolveListener)
            }
            override fun onServiceLost(s: NsdServiceInfo?) {}
        }
        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: Exception) { null }
        finally { try { nsd.stopServiceDiscovery(discListener) } catch (e: Exception) {} }
    }

    companion object {
        const val TAG = "AdbWireless"
        const val PAIRING = "_adb-tls-pairing._tcp."
        const val CONNECT = "_adb-tls-connect._tcp."
        const val ADB_PORT = 5071 // puerto exclusivo del servidor adb de la app
    }
}
