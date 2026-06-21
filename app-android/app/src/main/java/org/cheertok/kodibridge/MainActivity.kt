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
    private var volUpToken = "VolUp";   private var volUpName = ""
    private var volDownToken = "VolDown"; private var volDownName = ""
    private var touchedUp = false; private var touchedDown = false
    private val acts = listOf(
        "Nada" to "none",
        "Abrir un addon…" to "addon:",
        "Opciones de reproducción (OSD)" to "Input.ShowOSD",
        "Ir al inicio (Home)" to "Input.Home",
        "Menú contextual" to "Input.ContextMenu",
        "Play / Pausa" to "Player.PlayPause",
        "Subir volumen de Kodi" to "VolUp",
        "Bajar volumen de Kodi" to "VolDown",
    )

    // --- inalambrico ---
    private val adb by lazy { AdbWireless(this, adbPath()) }
    @Volatile private var wireStatus = "parado"  // estado del paso de vinculación
    private var serviceOn = false                // el puente corre en el servicio

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
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Shizuku.addRequestPermissionResultListener(permListener)

        // Permiso de notificaciones (Android 13+) para la notificación persistente.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2002)

        setupSpinners()
        loadPrefs()
        b.spVolUp.onItemSelectedListener = spinnerListener(true)
        b.spVolDown.onItemSelectedListener = spinnerListener(false)

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

        pollStatus()
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

    /** Uso diario: ya vinculado → arranca el SERVICIO (conecta + lanza el puente). */
    private fun connectAndStart(connAddr: String) {
        if (parseAddr(connAddr) == null) { toast("Escribe la IP:puerto de conexión"); return }
        startBridgeService(connAddr)
    }

    /** Primera vez: vincular (con código) y, si va, arrancar el servicio. */
    private fun pairThenStart(connAddr: String, pairAddr: String, code: String) {
        if (code.length < 6) { toast("Escribe el código de 6 dígitos del recuadro"); return }
        val pairHp = parseAddr(pairAddr) ?: run { toast("Escribe la IP:puerto de emparejamiento"); return }
        if (parseAddr(connAddr) == null) { toast("Escribe la IP:puerto de conexión"); return }
        wireStatus = "vinculando…"
        Thread {
            adb.startServer()
            if (!adb.pair(pairHp, code)) { ui.post { wireStatus = "código/IP incorrectos o caducados (¿recuadro cerrado?)" }; return@Thread }
            prefs.edit().putBoolean("paired", true).apply()
            ui.post { wireStatus = "vinculado ✓"; startBridgeService(connAddr) }
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
        b.spVolUp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, acts.map { it.first })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spVolDown.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, acts.map { it.first })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spVolUp.setOnTouchListener { _, _ -> touchedUp = true; false }
        b.spVolDown.setOnTouchListener { _, _ -> touchedDown = true; false }
    }

    private fun spinnerListener(isUp: Boolean) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
            val touched = if (isUp) touchedUp else touchedDown
            if (!touched) return
            if (isUp) touchedUp = false else touchedDown = false
            val token = acts[pos].second
            if (token == "addon:") pickAddon(isUp)
            else {
                if (isUp) { volUpToken = token; volUpName = "" } else { volDownToken = token; volDownName = "" }
                refreshAddonLabels()
            }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun pickAddon(isUp: Boolean) {
        toast("Buscando addons en Kodi…")
        Thread {
            val list = KodiClient(
                prefs.getString("host", "127.0.0.1")!!, prefs.getString("port", "8080")!!.toIntOrNull() ?: 8080,
                prefs.getString("user", "kodi")!!, prefs.getString("pass", "kodi")!!,
            ).listPluginAddons()
            ui.post {
                if (list.isEmpty()) { toast("No pude leer addons (¿Kodi encendido?)"); selectSpinnerForToken(isUp); return@post }
                AlertDialog.Builder(this).setTitle("Elige el addon a abrir")
                    .setItems(list.map { it.second }.toTypedArray()) { _, w ->
                        val (idSel, nameSel) = list[w]
                        if (isUp) { volUpToken = "addon:$idSel"; volUpName = nameSel } else { volDownToken = "addon:$idSel"; volDownName = nameSel }
                        refreshAddonLabels()
                    }.setOnCancelListener { selectSpinnerForToken(isUp) }.show()
            }
        }.start()
    }

    private fun refreshAddonLabels() {
        b.lblVolUpAddon.apply {
            if (volUpToken.startsWith("addon:")) { text = "→ $volUpName"; visibility = android.view.View.VISIBLE } else visibility = android.view.View.GONE
        }
        b.lblVolDownAddon.apply {
            if (volDownToken.startsWith("addon:")) { text = "→ $volDownName"; visibility = android.view.View.VISIBLE } else visibility = android.view.View.GONE
        }
    }

    private fun selectSpinnerForToken(isUp: Boolean) {
        val token = if (isUp) volUpToken else volDownToken
        val pos = if (token.startsWith("addon:")) acts.indexOfFirst { it.second == "addon:" }
        else acts.indexOfFirst { it.second == token }.let { if (it >= 0) it else 0 }
        (if (isUp) b.spVolUp else b.spVolDown).setSelection(pos)
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
        b.chkOpenKodi.isChecked = prefs.getBoolean("openkodi", false)
        volUpToken = prefs.getString("volup", "VolUp")!!;   volUpName = prefs.getString("volup_name", "")!!
        volDownToken = prefs.getString("voldown", "VolDown")!!; volDownName = prefs.getString("voldown_name", "")!!
        selectSpinnerForToken(true); selectSpinnerForToken(false)
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("host", b.host.text.toString().ifBlank { "127.0.0.1" })
            .putString("port", b.port.text.toString().ifBlank { "8080" })
            .putString("user", b.user.text.toString())
            .putString("pass", b.pass.text.toString())
            .putString("step", b.step.text.toString().ifBlank { "160" })
            .putString("connaddr", b.edtConnAddr.text.toString().trim())
            .putString("pairaddr", b.edtPairAddr.text.toString().trim())
            .putString("volup", volUpToken).putString("volup_name", volUpName)
            .putString("voldown", volDownToken).putString("voldown_name", volDownName)
            .apply()
    }

    private fun pollStatus() {
        // Si el servicio está activo, su estado manda (conectado/desconectado).
        val text = if (serviceOn) BridgeService.statusText else wireStatus
        b.status.text = "Puente: $text"
        ui.postDelayed({ pollStatus() }, 1000)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
