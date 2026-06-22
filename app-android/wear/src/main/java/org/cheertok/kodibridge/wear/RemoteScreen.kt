package org.cheertok.kodibridge.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

/** Token del atajo del reloj. De momento fijo a Palantir; configurable más adelante. */
private const val ADDON_SHORTCUT = "addon:plugin.video.palantir3"
/** Token especial: el móvil trae Kodi al primer plano (no es JSON-RPC). */
private const val OPEN_KODI = "OpenKodi"
/** Cierra Kodi por completo (JSON-RPC Application.Quit). */
private const val QUIT_KODI = "Application.Quit"

/** Radio del círculo OK como fracción del lado menor. Usado por el dibujo y la zona táctil. */
private const val OK_RADIUS_FRACTION = 0.22f

/** Ventana para acumular toques en el OK (1 = Select, 2 = "Ok2", 3 = "Ok3"). */
private const val MULTI_TAP_MS = 250L

/** Acumulador de la corona para emitir un paso por "muesca" y no inundar el canal. */
private class CrownState {
    var acc = 0f
    var lastMs = 0L
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(messenger: KodiMessenger) {
    var connected by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { messenger.onConnected = { connected = it } }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val haptics = LocalHapticFeedback.current

    fun act(token: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        messenger.send(token)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> DpadPage(active = pagerState.currentPage == 0, connected = connected, act = ::act)
                else -> ActionsPage(act = ::act)
            }
        }
        PageDots(current = pagerState.currentPage, count = 2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DpadPage(active: Boolean, connected: Boolean, act: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    val crown = remember { CrownState() }
    val scope = rememberCoroutineScope()
    // Job y contador de toques en el OK (para distinguir 1/2/3 toques).
    var okPendingJob by remember { mutableStateOf<Job?>(null) }
    val okTaps = remember { intArrayOf(0) }
    // Eje de la corona: true = vertical (Up/Down), false = horizontal (Left/Right).
    var crownVertical by remember { mutableStateOf(true) }

    LaunchedEffect(active) { if (active) focusRequester.requestFocus() }

    // Colores de los sectores y del centro.
    val wedgeA = Color(0xFF2C2C2E)
    val wedgeB = Color(0xFF232325)
    val divider = Color(0x22FFFFFF)
    val okColor = if (connected) MaterialTheme.colors.primary else Color(0xFF6E6E6E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                if (!active) return@onRotaryScrollEvent false
                crown.acc += event.verticalScrollPixels
                val now = System.currentTimeMillis()
                if (kotlin.math.abs(crown.acc) >= 40f && now - crown.lastMs >= 90) {
                    val forward = crown.acc > 0
                    act(
                        if (crownVertical) (if (forward) "Input.Down" else "Input.Up")
                        else (if (forward) "Input.Right" else "Input.Left")
                    )
                    crown.acc = 0f
                    crown.lastMs = now
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(crownVertical) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val centerR = minOf(size.width, size.height) * OK_RADIUS_FRACTION

                fun region(pos: Offset): String {
                    val dx = pos.x - cx
                    val dy = pos.y - cy
                    if (hypot(dx, dy) < centerR) return "OK"
                    // ángulo en grados: 0 = derecha, 90 = abajo, ±180 = izquierda, -90 = arriba
                    val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    return when {
                        a >= -45 && a < 45 -> "Input.Right"
                        a >= 45 && a < 135 -> "Input.Down"
                        a >= -135 && a < -45 -> "Input.Up"
                        else -> "Input.Left"
                    }
                }

                detectTapGestures(
                    onLongPress = { pos ->
                        // Mantener pulsado el CENTRO alterna el eje de la corona.
                        if (hypot(pos.x - cx, pos.y - cy) < centerR) {
                            okPendingJob?.cancel(); okPendingJob = null; okTaps[0] = 0
                            crownVertical = !crownVertical
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onTap = { pos ->
                        val r = region(pos)
                        if (r != "OK") {
                            // Las direcciones se disparan al instante (sin esperar toques en OK).
                            okPendingJob?.cancel(); okPendingJob = null; okTaps[0] = 0
                            act(r)
                            return@detectTapGestures
                        }
                        // OK: cuenta toques (1/2/3) dentro de una ventana corta y, al
                        // estabilizarse, manda el GESTO. El móvil decide la acción:
                        //   1 toque  -> Input.Select (entrar)
                        //   2 toques -> "Ok2"  (acción configurable; por defecto Atrás)
                        //   3 toques -> "Ok3"  (acción configurable; por defecto ninguna)
                        okPendingJob?.cancel()
                        okTaps[0] += 1
                        okPendingJob = scope.launch {
                            kotlinx.coroutines.delay(MULTI_TAP_MS)
                            when (okTaps[0].coerceAtMost(3)) {
                                1 -> act("Input.Select")
                                2 -> act("Ok2")
                                3 -> act("Ok3")
                            }
                            okTaps[0] = 0
                            okPendingJob = null
                        }
                    },
                )
            },
    ) {
        // Dibujo: 4 sectores triangulares que rellenan TODA la superficie (hasta las
        // esquinas, sin huecos), divididos por las diagonales centro→esquina. Encima, el
        // único círculo es el OK central.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Offset(w / 2f, h / 2f)
            val tl = Offset(0f, 0f)
            val tr = Offset(w, 0f)
            val br = Offset(w, h)
            val bl = Offset(0f, h)

            fun wedge(p1: Offset, p2: Offset, color: Color) {
                val path = Path().apply {
                    moveTo(c.x, c.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close()
                }
                drawPath(path, color)
            }
            // cada sector va del centro a dos esquinas adyacentes
            wedge(tl, tr, wedgeA) // Arriba
            wedge(tr, br, wedgeB) // Derecha
            wedge(br, bl, wedgeA) // Abajo
            wedge(bl, tl, wedgeB) // Izquierda

            // líneas divisorias = las diagonales completas (centro → cada esquina)
            listOf(tl, tr, br, bl).forEach { corner ->
                drawLine(divider, c, corner, strokeWidth = 2f)
            }
            // único círculo: el OK central
            val centerR = minOf(w, h) * OK_RADIUS_FRACTION
            drawCircle(okColor, centerR, c)
        }

        // Glifos de las flechas en cada sector. Se resalta el eje que moverá la corona
        // (brillante) y se atenúa el otro, para ver de un vistazo la dirección del scroll.
        val vAxis = if (crownVertical) Color.White else Color(0x66FFFFFF)
        val hAxis = if (crownVertical) Color(0x66FFFFFF) else Color.White
        Text("▲", fontSize = 30.sp, color = vAxis,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
        Text("▼", fontSize = 30.sp, color = vAxis,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp))
        Text("◀", fontSize = 30.sp, color = hAxis,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 26.dp))
        Text("▶", fontSize = 30.sp, color = hAxis,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 26.dp))

        // Etiqueta del centro: OK + eje actual de la corona (claramente visible: indica
        // hacia dónde moverá el scroll de la corona).
        Column(
            modifier = Modifier.align(Alignment.Center)
                .semantics { contentDescription = "OK. Mantén pulsado para cambiar el eje de la corona" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                if (crownVertical) "↕" else "↔",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ActionsPage(act: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActionChip("↩  Atrás") { act("Input.Back") }
        ActionChip("⌂  Inicio") { act("Input.Home") }
        ActionChip("⏯  Play / Pausa") { act("Player.PlayPause") }
        ActionChip("ⓘ  OSD reproducción") { act("Input.ShowOSD") }
        ActionChip("▶  Abrir Kodi") { act(OPEN_KODI) }
        ActionChip("★  Palantir") { act(ADDON_SHORTCUT) }
        // Cerrar Kodi pierde la reproducción: pide confirmación (pulsar dos veces).
        // Icono vectorial (no glifo): el símbolo ⏻ no está en la fuente del reloj.
        ConfirmActionChip("Salir de Kodi", "¿Salir? Pulsa otra vez", R.drawable.ic_power) { act(QUIT_KODI) }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontSize = 15.sp) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Chip de acción destructiva: el primer toque "arma" (cambia la etiqueta y el color); el
 *  segundo, dentro de [CONFIRM_MS], ejecuta. Si no se confirma a tiempo, vuelve a su estado. */
@Composable
private fun ConfirmActionChip(label: String, confirmLabel: String, iconRes: Int, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var revertJob by remember { mutableStateOf<Job?>(null) }
    Chip(
        onClick = {
            if (armed) {
                revertJob?.cancel(); revertJob = null; armed = false
                onConfirm()
            } else {
                armed = true
                revertJob?.cancel()
                revertJob = scope.launch {
                    kotlinx.coroutines.delay(CONFIRM_MS)
                    armed = false; revertJob = null
                }
            }
        },
        label = { Text(if (armed) confirmLabel else label, fontSize = 15.sp) },
        icon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(ChipDefaults.IconSize),
            )
        },
        colors = if (armed) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Ventana para confirmar una acción destructiva con el segundo toque. */
private const val CONFIRM_MS = 3000L

@Composable
private fun PageDots(current: Int, count: Int) {
    Box(Modifier.fillMaxSize().padding(bottom = 4.dp), contentAlignment = Alignment.BottomCenter) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(count) { i ->
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (i == current) Color.White else Color(0xFF555555))
                )
            }
        }
    }
}
