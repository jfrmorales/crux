package org.cheertok.kodibridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.cheertok.kodibridge.databinding.ActivityMainBinding
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * UI + control. Dos formas de "encender" el puente:
 *  - INALAMBRICO (principal, sin Shizuku, sirve en OPPO y Samsung): usa la
 *    depuracion inalambrica del propio movil via el adb empaquetado.
 *  - SHIZUKU (alternativa, si esta activo): lanza el binario por el UserService.
 * Atajos VOL+/VOL- configurables (incl. abrir un addon como Palantir).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    // --- estado atajos ---
    // Un "slot" = un destino configurable (botón lateral del CheerTok o gesto del reloj).
    // Cada uno guarda su token de acción y, si es un addon, su nombre legible.
    private inner class Slot(
        val sp: android.widget.Spinner,
        val lbl: android.widget.TextView,
        val prefKey: String,
        val default: String,
    ) {
        var token = default
        var name = ""
        var touched = false
    }
    private val slots by lazy {
        listOf(
            Slot(b.spVolUp, b.lblVolUpAddon, "volup", "VolUp"),
            Slot(b.spVolDown, b.lblVolDownAddon, "voldown", "VolDown"),
            Slot(b.spOkDouble, b.lblOkDoubleAddon, "ok_double", "Input.Back"),
            Slot(b.spOkTriple, b.lblOkTripleAddon, "ok_triple", "none"),
        )
    }
    // Tokens que el puente nativo necesita (botones laterales). Solo lectura.
    private val volUpToken get() = slots[0].token
    private val volDownToken get() = slots[1].token
    private val acts = listOf(
        "Nada" to "none",
        "Abrir un addon…" to "addon:",
        "Atrás" to "Input.Back",
        "Opciones de reproducción (OSD)" to "Input.ShowOSD",
        "Ir al inicio (Home)" to "Input.Home",
        "Menú contextual" to "Input.ContextMenu",
        "Play / Pausa" to "Player.PlayPause",
        "Abrir Kodi" to "OpenKodi",
        "Salir de Kodi (cerrar)" to "Application.Quit",
        "Subir volumen de Kodi" to "VolUp",
        "Bajar volumen de Kodi" to "VolDown",
    )

    // --- inalambrico ---
    private val adb by lazy { AdbWireless(this, adbPath()) }
    @Volatile private var wireStatus = "parado"  // estado del paso de vinculación
    private var serviceOn = false                // el puente corre en el servicio

    // --- aviso de versión de la app del reloj ---
    // Versión del wear.apk empaquetado (se calcula una vez en segundo plano) y si hay
    // algún reloj conectado. Comparado con la última versión que anunció el reloj
    // (prefs "watch_app_version") para avisar si su app se ha quedado desactualizada.
    @Volatile private var bundledWearVer = -1
    @Volatile private var watchNodePresent = false
    private var watchBannerShown = false  // para auto-expandir el bloque de instalar solo al aparecer
    private val LOOPBACK_PORT = 5555
    private val LOOPBACK_ADDR = "127.0.0.1:5555" // dirección diaria (sin WiFi)

    // --- shizuku ---
    private var userService: IUserService? = null
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, BridgeUserService::class.java.name))
            .daemon(true).processNameSuffix("bridge").debuggable(false).version(3)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IUserService.Stub.asInterface(binder)
            try { userService?.startBridge(buildConfig()) } catch (e: Exception) {}
        }
        override fun onServiceDisconnected(name: ComponentName?) { userService = null }
    }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        toast(if (grant == PackageManager.PERMISSION_GRANTED) "Permiso Shizuku concedido" else "Permiso Shizuku denegado")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Android 15 (targetSdk 35) dibuja edge-to-edge: deja sitio para la barra de
        // estado y la de navegación para que el contenido no se solape con ellas.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Shizuku.addRequestPermissionResultListener(permListener)

        // Permiso de notificaciones (Android 13+) para la notificación persistente.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2002)

        setupSpinners()
        loadPrefs()

        b.btnOpenWdb.setOnClickListener { openWirelessSettings() }
        b.btnStart.setOnClickListener {        // uso diario: solo conectar + arrancar
            savePrefs(); connectAndStart(b.edtConnAddr.text.toString().trim())
        }
        b.btnPair.setOnClickListener {          // primera vez: vincular (+ arrancar)
            savePrefs(); pairThenStart(
                b.edtConnAddr.text.toString().trim(),
                b.edtPairAddr.text.toString().trim(),
                b.edtCode.text.toString().trim(),
            )
        }
        b.btnStop.setOnClickListener { stopAll() }
        b.tvInstallWatchHeader.setOnClickListener {
            setInstallWatchExpanded(b.boxInstallWatch.visibility != android.view.View.VISIBLE)
        }
        b.btnPairWatch.setOnClickListener { savePrefs(); pairWatch() }
        b.btnInstallWatch.setOnClickListener { savePrefs(); installWatchApp() }
        b.btnShizuku.setOnClickListener { ensureShizukuPermission() }
        b.btnStartShizuku.setOnClickListener { savePrefs(); startShizuku() }
        b.btnSave.setOnClickListener { savePrefs(); applyRunning(); toast("Guardado") }
        b.chkOpenKodi.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("openkodi", checked).apply()
            if (checked && !android.provider.Settings.canDrawOverlays(this)) {
                toast("Concede «mostrar sobre otras apps» para que abra Kodi en segundo plano")
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } catch (e: Exception) {}
            }
        }

    }

    // El refresco de estado solo tiene sentido con la UI visible: arráncalo en onStart y
    // párenlo en onStop para no despertar el hilo principal cada segundo en segundo plano
    // ni retener la Activity tras destruirse (el postDelayed se reencolaba indefinidamente).
    override fun onStart() {
        super.onStart()
        pollStatus()
        refreshWatchInfo()
    }

    override fun onStop() {
        ui.removeCallbacks(pollStatusRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    // ---------- INALAMBRICO ----------

    private fun openWirelessSettings() {
        try { startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
        catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (e2: Exception) { toast("Abre Ajustes → Opciones de desarrollador") }
        }
    }

    private fun parseAddr(s: String): AdbWireless.HostPort? {
        val i = s.lastIndexOf(':'); if (i <= 0) return null
        val host = s.substring(0, i).trim(); val port = s.substring(i + 1).trim().toIntOrNull() ?: return null
        return AdbWireless.HostPort(host, port)
    }

    /**
     * Uso diario. Si el campo ya es loopback (`127.0.0.1:5555`), el `tcpip` sigue activo
     * este arranque → arranca directo. Si es la IP real (p.ej. tras reiniciar el móvil, que
     * borra el modo `tcpip`), conecta, lo reactiva y **cambia solo el campo** a loopback.
     * No pide código: el emparejamiento se recuerda entre reinicios.
     */
    private fun connectAndStart(connAddr: String) {
        val connHp = parseAddr(connAddr) ?: run { toast("Escribe la IP:puerto de conexión"); return }
        if (connHp.host == "127.0.0.1") { startBridgeService(connAddr); return }
        connectTcpipAndStart(connHp)
    }

    /**
     * Conecta a la IP real, habilita `tcpip` (loopback) y arranca en `127.0.0.1:5555`,
     * cambiando el campo automáticamente. Reutilizado por «Iniciar» (ya emparejado) y por
     * «Vincular» (tras emparejar). Si `tcpip` falla, arranca por WiFi normal.
     */
    private fun connectTcpipAndStart(connHp: AdbWireless.HostPort) {
        wireStatus = "conectando…"
        Thread {
            adb.startServer()
            if (!adb.connect(connHp)) {
                ui.post { wireStatus = "no conecta a ${connHp.host}:${connHp.port} (¿depuración inalámbrica activa?)" }; return@Thread
            }
            ui.post { wireStatus = "preparando modo sin-WiFi…" }
            if (adb.tcpip(connHp, LOOPBACK_PORT)) {
                Thread.sleep(2500) // adbd reinicia
                adb.connect(AdbWireless.HostPort("127.0.0.1", LOOPBACK_PORT))
                ui.post {
                    b.edtConnAddr.setText(LOOPBACK_ADDR); savePrefs()
                    wireStatus = "listo (funciona sin WiFi)"
                    startBridgeService(LOOPBACK_ADDR)
                }
            } else {
                // Sin tcpip: arranca con la conexión WiFi normal (necesitará WiFi).
                ui.post { wireStatus = "conectado (necesita WiFi)"; startBridgeService("${connHp.host}:${connHp.port}") }
            }
        }.start()
    }

    /** Primera vez: vincular (con código, con WiFi) y dejar el modo SIN-WiFi listo. */
    private fun pairThenStart(connAddr: String, pairAddr: String, code: String) {
        if (code.length < 6) { toast("Escribe el código de 6 dígitos del recuadro"); return }
        val pairHp = parseAddr(pairAddr) ?: run { toast("Escribe la IP:puerto de emparejamiento"); return }
        val connHp = parseAddr(connAddr) ?: run { toast("Escribe la IP:puerto de conexión"); return }
        wireStatus = "vinculando…"
        Thread {
            adb.startServer()
            if (!adb.pair(pairHp, code)) { ui.post { wireStatus = "código/IP incorrectos o caducados (¿recuadro cerrado?)" }; return@Thread }
            prefs.edit().putBoolean("paired", true).apply()
            // A partir de aquí es lo mismo que «Iniciar» con la IP real: conectar + tcpip.
            ui.post { wireStatus = "vinculado ✓, conectando…"; connectTcpipAndStart(connHp) }
        }.start()
    }

    /** Arranca el foreground service que mantiene el puente + la notificación. */
    private fun startBridgeService(connAddr: String) {
        wireStatus = "iniciando servicio…"
        serviceOn = true
        val i = Intent(this, BridgeService::class.java)
            .putExtra("connAddr", connAddr)
            .putExtra("bridgeCmd", buildBridgeCmd())
            .putExtra("adbPath", adbPath())
            .putExtra("openKodi", b.chkOpenKodi.isChecked)
        startForegroundService(i)
    }

    private fun stopAll() {
        serviceOn = false
        stopService(Intent(this, BridgeService::class.java))
        wireStatus = "parado"
        try { userService?.stopBridge(); Shizuku.unbindUserService(userServiceArgs, connection, true) } catch (e: Exception) {}
        userService = null
        toast("Parado")
    }

    // ---------- INSTALAR APP EN EL RELOJ ----------

    /** PASO 1 (solo la 1ª vez): vincula la clave adb de la app con el reloj. Solo necesita
     *  el emparejamiento (IP:puerto + código); NO la IP de conexión. Sin esto, el connect
     *  del Paso 2 da "failed to connect" aunque el puerto sea correcto. */
    private fun pairWatch() {
        val pairHp = parseAddr(b.edtWatchPair.text.toString().trim())
            ?: run { toast("Escribe la IP:puerto de emparejamiento del reloj"); return }
        val code = b.edtWatchCode.text.toString().trim()
        if (code.length < 6) { toast("Escribe el código de 6 dígitos del reloj"); return }
        wireStatus = "vinculando con el reloj…"; setWatchBusy(true)
        Thread {
            adb.startServer()
            val ok = adb.pair(pairHp, code)
            val why = adb.lastOutput.takeLast(140)
            ui.post {
                setWatchBusy(false)
                wireStatus = if (ok) "reloj vinculado ✓ — ahora completa el Paso 2 (Instalar)" else "reloj: no empareja — $why"
                toast(if (ok) "Reloj vinculado ✓" else "No se pudo vincular el reloj")
            }
        }.start()
    }

    /** PASO 2: conecta a la depuración inalámbrica del reloj e instala (o actualiza) su app.
     *  Solo necesita la IP:puerto de conexión. Requiere haber vinculado antes (Paso 1). */
    private fun installWatchApp() {
        val connHp = parseAddr(b.edtWatchConn.text.toString().trim())
            ?: run { toast("Escribe la IP:puerto de conexión del reloj"); return }
        wireStatus = "conectando con el reloj…"; setWatchBusy(true)
        Thread {
            adb.startServer()
            if (!adb.connect(connHp)) {
                val why = adb.lastOutput.takeLast(120)
                // "failed to connect" en wireless-debug casi siempre = clave adb NO emparejada
                // (no es puerto mal): recuerda el Paso 1.
                ui.post { setWatchBusy(false); wireStatus = "reloj: no conecta a ${connHp.host}:${connHp.port} — $why — ⚠️ ¿has hecho el Paso 1 (Vincular reloj)?" }; return@Thread
            }
            val apk = exportWearApk()
                ?: run { ui.post { setWatchBusy(false); wireStatus = "no encuentro el APK del reloj empaquetado" }; return@Thread }
            ui.post { wireStatus = "enviando e instalando la app en el reloj…" }
            val (ok, out) = adb.install(connHp, apk)
            ui.post {
                setWatchBusy(false)
                wireStatus = if (ok) "app instalada en el reloj ✓" else "fallo al instalar en el reloj"
                toast(if (ok) "App del reloj instalada ✓" else "Fallo: ${out.takeLast(140)}")
                if (ok) {
                    // Acabamos de instalar el wear.apk empaquetado: marca esa versión como
                    // instalada para que el aviso de "desactualizada" desaparezca al momento.
                    if (bundledWearVer > 0) prefs.edit().putInt("watch_app_version", bundledWearVer).apply()
                    updateWatchBanner()
                }
            }
        }.start()
    }

    /** Vuelca el asset "wear.apk" a un fichero real (adb necesita una ruta de disco). */
    private fun exportWearApk(): String? = try {
        val out = java.io.File(filesDir, "wear.apk")
        assets.open("wear.apk").use { inp -> out.outputStream().use { inp.copyTo(it) } }
        out.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "exportWearApk: ${e.message}"); null
    }

    // ---------- SHIZUKU (alternativa) ----------

    private fun shizukuReady() = try { Shizuku.pingBinder() } catch (e: Exception) { false }
    private fun ensureShizukuPermission() {
        if (!shizukuReady()) { toast("Shizuku no está activo"); return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) toast("Permiso ya concedido")
        else Shizuku.requestPermission(1001)
    }
    private fun startShizuku() {
        if (!shizukuReady()) { toast("Shizuku no está activo"); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { ensureShizukuPermission(); return }
        try { Shizuku.bindUserService(userServiceArgs, connection) } catch (e: Exception) { toast("Error: ${e.message}") }
    }

    // ---------- Config / paths ----------

    private fun adbPath() = applicationInfo.nativeLibraryDir + "/libadb.so"
    private fun bridgePath() = applicationInfo.nativeLibraryDir + "/libbridge.so"

    private fun authB64(): String {
        val u = prefs.getString("user", "kodi"); val p = prefs.getString("pass", "kodi")
        return Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
    }

    private fun buildBridgeCmd(): String {
        val host = prefs.getString("host", "127.0.0.1")
        val port = prefs.getString("port", "8080")
        val step = prefs.getString("step", "160")
        return "${bridgePath()} --host $host --port $port --auth ${authB64()} " +
            "--step $step --volup '$volUpToken' --voldown '$volDownToken'"
    }

    private fun buildConfig(): String = JSONObject().apply {
        put("host", prefs.getString("host", "127.0.0.1"))
        put("port", prefs.getString("port", "8080")!!.toIntOrNull() ?: 8080)
        put("user", prefs.getString("user", "kodi"))
        put("pass", prefs.getString("pass", "kodi"))
        put("step", prefs.getString("step", "160")!!.toIntOrNull() ?: 160)
        put("volup", volUpToken); put("voldown", volDownToken)
        put("bridge", bridgePath())
    }.toString()

    private fun applyRunning() {
        // Si el puente inalámbrico está en marcha, relánzalo con la nueva config.
        // Si va por Shizuku, refréscalo.
        if (serviceOn) startBridgeService(b.edtConnAddr.text.toString().trim())
        else try { userService?.let { it.stopBridge(); it.startBridge(buildConfig()) } } catch (e: Exception) {}
    }

    // ---------- Spinners / addon picker ----------

    @Suppress("ClickableViewAccessibility")
    private fun setupSpinners() {
        slots.forEach { slot ->
            slot.sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, acts.map { it.first })
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            slot.sp.setOnTouchListener { _, _ -> slot.touched = true; false }
            slot.sp.onItemSelectedListener = spinnerListener(slot)
        }
    }

    private fun spinnerListener(slot: Slot) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
            if (!slot.touched) return
            slot.touched = false
            val token = acts[pos].second
            if (token == "addon:") pickAddon(slot)
            else { slot.token = token; slot.name = ""; refreshAddonLabels() }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun pickAddon(slot: Slot) {
        toast("Buscando addons en Kodi…")
        Thread {
            val list = KodiClient(
                prefs.getString("host", "127.0.0.1")!!, prefs.getString("port", "8080")!!.toIntOrNull() ?: 8080,
                prefs.getString("user", "kodi")!!, prefs.getString("pass", "kodi")!!,
            ).listPluginAddons()
            ui.post {
                if (list.isEmpty()) { toast("No pude leer addons (¿Kodi encendido?)"); selectSpinnerForToken(slot); return@post }
                AlertDialog.Builder(this).setTitle("Elige el addon a abrir")
                    .setItems(list.map { it.second }.toTypedArray()) { _, w ->
                        val (idSel, nameSel) = list[w]
                        slot.token = "addon:$idSel"; slot.name = nameSel
                        refreshAddonLabels()
                    }.setOnCancelListener { selectSpinnerForToken(slot) }.show()
            }
        }.start()
    }

    private fun refreshAddonLabels() {
        slots.forEach { slot ->
            slot.lbl.apply {
                if (slot.token.startsWith("addon:")) { text = "→ ${slot.name}"; visibility = android.view.View.VISIBLE }
                else visibility = android.view.View.GONE
            }
        }
    }

    private fun selectSpinnerForToken(slot: Slot) {
        val pos = if (slot.token.startsWith("addon:")) acts.indexOfFirst { it.second == "addon:" }
        else acts.indexOfFirst { it.second == slot.token }.let { if (it >= 0) it else 0 }
        slot.sp.setSelection(pos)
        refreshAddonLabels()
    }

    // ---------- Prefs ----------

    private fun loadPrefs() {
        b.host.setText(prefs.getString("host", "127.0.0.1"))
        b.port.setText(prefs.getString("port", "8080"))
        b.user.setText(prefs.getString("user", "kodi"))
        b.pass.setText(prefs.getString("pass", "kodi"))
        b.step.setText(prefs.getString("step", "160"))
        b.edtConnAddr.setText(prefs.getString("connaddr", ""))
        b.edtPairAddr.setText(prefs.getString("pairaddr", ""))
        b.edtWatchConn.setText(prefs.getString("watchconn", ""))
        b.edtWatchPair.setText(prefs.getString("watchpair", ""))
        b.chkOpenKodi.isChecked = prefs.getBoolean("openkodi", false)
        slots.forEach { slot ->
            slot.token = prefs.getString(slot.prefKey, slot.default)!!
            slot.name = prefs.getString(slot.prefKey + "_name", "")!!
            selectSpinnerForToken(slot)
        }
    }

    private fun savePrefs() {
        val e = prefs.edit()
            .putString("host", b.host.text.toString().ifBlank { "127.0.0.1" })
            .putString("port", b.port.text.toString().ifBlank { "8080" })
            .putString("user", b.user.text.toString())
            .putString("pass", b.pass.text.toString())
            .putString("step", b.step.text.toString().ifBlank { "160" })
            .putString("connaddr", b.edtConnAddr.text.toString().trim())
            .putString("pairaddr", b.edtPairAddr.text.toString().trim())
            .putString("watchconn", b.edtWatchConn.text.toString().trim())
            .putString("watchpair", b.edtWatchPair.text.toString().trim())
        slots.forEach { e.putString(it.prefKey, it.token).putString(it.prefKey + "_name", it.name) }
        e.apply()
    }

    // Runnable estable para poder cancelarlo en onStop (un lambda nuevo no sería removible).
    private val pollStatusRunnable = Runnable { pollStatus() }

    private fun pollStatus() {
        // Si el servicio está activo, su estado manda (conectado/desconectado).
        val text = if (serviceOn) BridgeService.statusText else wireStatus
        b.status.text = "Puente: $text"
        // Refresca el aviso de versión del reloj por si llega un anuncio con la UI abierta.
        updateWatchBanner()
        ui.removeCallbacks(pollStatusRunnable)
        ui.postDelayed(pollStatusRunnable, 1000)
    }

    // ---------- Aviso de versión de la app del reloj ----------

    /** Calcula (una vez) la versión del wear.apk empaquetado y si hay un reloj conectado,
     *  en segundo plano para no copiar 22 MB en el hilo de UI; luego refresca el aviso. */
    private fun refreshWatchInfo() {
        Thread {
            val ver = if (bundledWearVer >= 0) bundledWearVer else computeBundledWearVersion()
            val node = try {
                com.google.android.gms.tasks.Tasks.await(
                    com.google.android.gms.wearable.Wearable.getNodeClient(this).connectedNodes
                ).isNotEmpty()
            } catch (e: Exception) { false }
            ui.post { bundledWearVer = ver; watchNodePresent = node; updateWatchBanner() }
        }.start()
    }

    /** versionCode del wear.apk que esta app del móvil lleva empaquetado como asset. */
    private fun computeBundledWearVersion(): Int {
        val path = exportWearApk() ?: return -1
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, 0)?.versionCode ?: -1
        } catch (e: Exception) { -1 }
    }

    /** Muestra la barra de progreso del reloj y bloquea sus botones mientras trabaja. */
    private fun setWatchBusy(busy: Boolean) {
        b.watchProgress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        b.btnPairWatch.isEnabled = !busy
        b.btnInstallWatch.isEnabled = !busy
    }

    /** Expande/contrae el bloque «Instalar / actualizar la app del reloj». */
    private fun setInstallWatchExpanded(expanded: Boolean) {
        b.boxInstallWatch.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        b.tvInstallWatchHeader.text =
            (if (expanded) "▾" else "▸") + "  Instalar / actualizar la app del reloj  (1ª vez o al actualizar)"
    }

    /** Muestra/oculta el aviso comparando la versión empaquetada con la última que anunció
     *  el reloj (prefs "watch_app_version", la escribe KodiWearListener). Al aparecer por
     *  primera vez, abre el bloque de instalar para que la solución quede a la vista. */
    private fun updateWatchBanner() {
        if (!::b.isInitialized) return
        val bundled = bundledWearVer
        val installed = prefs.getInt("watch_app_version", -1)
        val msg = when {
            bundled <= 0 -> null
            installed in 0 until bundled ->
                "⚠️ La app del reloj está desactualizada (instalada v$installed, incluida v$bundled). Ábrela abajo en «Instalar / actualizar» y reinstálala."
            installed < 0 && watchNodePresent ->
                "ℹ️ Si usas el mando del reloj, instala o actualiza su app abajo en «Instalar / actualizar»."
            else -> null
        }
        val v = b.lblWatchUpdate
        if (msg != null) {
            v.text = msg
            v.visibility = android.view.View.VISIBLE
            if (!watchBannerShown) { setInstallWatchExpanded(true); watchBannerShown = true }
        } else {
            v.visibility = android.view.View.GONE
            watchBannerShown = false
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
