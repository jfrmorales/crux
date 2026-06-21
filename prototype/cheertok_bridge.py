#!/usr/bin/env python3
"""
Prototipo del puente CheerTok -> cruceta de Kodi (Fase 0).

Lee los eventos HID del CheerTok via `adb shell getevent -lt`, los traduce a
acciones de navegacion y las envia a Kodi por JSON-RPC (Input.Up/Down/Left/
Right/Select/Back/ContextMenu/Home y Player.PlayPause).

Sirve para VALIDAR y AFINAR el mapeo gesto->navegacion antes de portarlo a la
app Android (que hara lo mismo on-device via Shizuku). Con --dry-run imprime la
accion que enviaria sin necesitar Kodi accesible.

Mapeo (valores por defecto, todos afinables por flags):
  - Swipe del touchpad  -> Up/Down/Left/Right  (1 paso por cada STEP unidades
                           acumuladas en el eje dominante; un swipe = varios pasos)
  - Rueda de borde      -> Up/Down (vertical) / Left/Right (horizontal),
                           1 paso por muesca (240 hi-res)
  - Tap (BTN_MOUSE)     -> Select (OK)
  - BTN_RIGHT           -> Back
  - KEY_BACK            -> Back        KEY_MENU      -> ContextMenu
  - KEY_HOMEPAGE        -> Home        KEY_PLAYPAUSE -> Player.PlayPause
"""
import argparse
import base64
import json
import re
import subprocess
import sys
import time
import urllib.request

# --- Nombres HID del CheerTok (estables; el nº de /dev/input/eventN cambia al
#     reconectar, por eso resolvemos SIEMPRE por nombre). ---
DEV_MOUSE_NAME = "CheerTok Air Mouse"
DEV_KEYS_NAME = "CheerTok Air Consumer Control"

# Mapa tecla-HID -> metodo JSON-RPC de Kodi
KEY_ACTIONS = {
    "KEY_BACK": ("Input.Back", None),
    "KEY_MENU": ("Input.ContextMenu", None),
    "KEY_HOMEPAGE": ("Input.Home", None),
    "KEY_PLAYPAUSE": ("Player.PlayPause", {"playerid": 1}),
}

# Con `getevent -l` el VALOR de las EV_KEY es simbolico (DOWN/UP/DOWN_REPEAT),
# mientras que el de EV_REL/EV_ABS es hex (p.ej. ffffffff = -1). Por eso el ultimo
# grupo es un token libre y se interpreta segun el tipo de evento.
LINE_RE = re.compile(
    r"\[\s*([0-9.]+)\]\s+(/dev/input/event\d+):\s+(\w+)\s+(\w+)\s+(\S+)"
)


def s32(hexval):
    v = int(hexval, 16)
    return v - 2**32 if v >= 2**31 else v


def key_val(token):
    """DOWN/DOWN_REPEAT -> 1 (pulsado), UP -> 0. Tolera valor hex por si acaso."""
    if token in ("DOWN", "DOWN_REPEAT"):
        return 1
    if token == "UP":
        return 0
    try:
        return 1 if int(token, 16) else 0
    except ValueError:
        return 0


class Kodi:
    def __init__(self, host, port, user, pw, dry):
        self.url = f"http://{host}:{port}/jsonrpc"
        self.dry = dry
        self.hdr = {"Content-Type": "application/json"}
        if user:
            tok = base64.b64encode(f"{user}:{pw}".encode()).decode()
            self.hdr["Authorization"] = f"Basic {tok}"
        self._id = 0

    def call(self, method, params=None):
        self._id += 1
        if self.dry:
            print(f"  -> {method} {params or ''}", flush=True)
            return
        body = {"jsonrpc": "2.0", "method": method, "id": self._id}
        if params:
            body["params"] = params
        req = urllib.request.Request(
            self.url, data=json.dumps(body).encode(), headers=self.hdr
        )
        try:
            with urllib.request.urlopen(req, timeout=2) as r:
                r.read()
        except Exception as e:  # no romper el bucle por un fallo de red puntual
            print(f"  !! JSON-RPC {method} fallo: {e}", file=sys.stderr)


def resolve_devices(serial):
    """Devuelve {path: nombre} de los devices del CheerTok via `getevent -i`."""
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["shell", "getevent", "-i"]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout.replace("\r", "")
    devices = {}
    cur = None
    for ln in out.splitlines():
        m = re.search(r"add device \d+:\s+(/dev/input/event\d+)", ln)
        if m:
            cur = m.group(1)
        m = re.search(r'name:\s+"([^"]+)"', ln)
        if m and cur:
            devices[cur] = m.group(1)
            cur = None
    paths = {}
    for path, name in devices.items():
        if name == DEV_MOUSE_NAME:
            paths["mouse"] = path
        elif name == DEV_KEYS_NAME:
            paths["keys"] = path
    return paths


def main():
    ap = argparse.ArgumentParser(description="Puente CheerTok -> Kodi (prototipo)")
    ap.add_argument("--serial", help="serial/IP:puerto adb del dispositivo")
    ap.add_argument("--kodi-host", default="127.0.0.1")
    ap.add_argument("--kodi-port", default="8080")
    ap.add_argument("--kodi-user", default="kodi")
    ap.add_argument("--kodi-pass", default="kodi")
    ap.add_argument("--dry-run", action="store_true",
                    help="imprime la accion en vez de enviarla a Kodi")
    ap.add_argument("--step", type=int, default=160,
                    help="unidades acumuladas por paso de cruceta (def 160)")
    ap.add_argument("--wheel-step", type=int, default=240,
                    help="hi-res por muesca de rueda (def 240)")
    ap.add_argument("--tap-ms", type=int, default=400,
                    help="duracion max de un tap (def 400ms)")
    ap.add_argument("--tap-move", type=int, default=40,
                    help="movimiento max durante un tap (def 40u)")
    ap.add_argument("--replay", metavar="FILE",
                    help="reproduce un volcado de `getevent -lt` (test offline)")
    ap.add_argument("--debug", action="store_true",
                    help="imprime contadores y primeras lineas crudas a stderr")
    args = ap.parse_args()

    if args.replay:
        # En replay aceptamos cualquier event* presente en el volcado.
        paths = {"mouse": None, "keys": None, "replay": True}
        print(f"REPLAY desde {args.replay}", file=sys.stderr)
    else:
        paths = resolve_devices(args.serial)
        if "mouse" not in paths:
            print("ERROR: no encuentro 'CheerTok Air Mouse'. ¿Conectado por BT?",
                  file=sys.stderr)
            sys.exit(1)
        print(f"Devices: {paths}", file=sys.stderr)

    kodi = Kodi(args.kodi_host, args.kodi_port, args.kodi_user, args.kodi_pass,
                args.dry_run)

    # Estado de acumuladores
    ax = ay = 0          # swipe touchpad
    wv = wh = 0          # rueda vertical/horizontal (hi-res)
    btn_t0 = None        # inicio de pulsacion BTN_MOUSE
    btn_dx = btn_dy = 0  # movimiento durante la pulsacion

    def nav(method, params=None, label=""):
        # flush=True: en modo vivo stdout va a fichero (block-buffered); sin flush
        # las acciones se perderian al matar el proceso con SIGTERM (timeout).
        print(f"[{label}] -> {method}", flush=True)
        kodi.call(method, params)

    if args.replay:
        proc = None
        source = open(args.replay)
        accept = None  # acepta cualquier device del volcado
    else:
        cmd = ["adb"]
        if args.serial:
            cmd += ["-s", args.serial]
        cmd += ["shell", "getevent", "-lt"]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, bufsize=1)
        source = proc.stdout
        accept = (paths.get("mouse"), paths.get("keys"))
    print("Puente activo. Mueve el CheerTok... (Ctrl-C para salir)",
          file=sys.stderr)
    n_raw = n_match = n_dev = 0
    try:
        for raw in source:
            n_raw += 1
            if args.debug and n_raw <= 3:
                print(f"RAW[{n_raw}]: {raw.rstrip()}", file=sys.stderr)
            if args.debug and n_raw % 200 == 0:
                print(f"DBG raw={n_raw} match={n_match} dev_ok={n_dev}",
                      file=sys.stderr)
            m = LINE_RE.search(raw.replace("\r", ""))
            if not m:
                continue
            n_match += 1
            t, dev, ev, code, val = m.groups()
            t = float(t)
            if accept is not None and dev not in accept:
                continue
            n_dev += 1

            # ---- Teclas (consumer control + botones del raton) ----
            if ev == "EV_KEY":
                v = key_val(val)
                if code in KEY_ACTIONS and v == 1:
                    method, params = KEY_ACTIONS[code]
                    nav(method, params, code)
                elif code == "BTN_RIGHT" and v == 1:
                    nav("Input.Back", None, "BTN_RIGHT")
                elif code == "BTN_MOUSE":
                    if v == 1:  # press
                        btn_t0 = t
                        btn_dx = btn_dy = 0
                    elif v == 0 and btn_t0 is not None:  # release -> ¿tap?
                        dt = (t - btn_t0) * 1000
                        if dt <= args.tap_ms and abs(btn_dx) <= args.tap_move \
                                and abs(btn_dy) <= args.tap_move:
                            nav("Input.Select", None, "TAP")
                        btn_t0 = None
                continue

            # ---- Movimiento relativo (touchpad + rueda) ----
            if ev == "EV_REL":
                v = s32(val)
                if code == "REL_X":
                    ax += v
                    if btn_t0 is not None:
                        btn_dx += v
                elif code == "REL_Y":
                    ay += v
                    if btn_t0 is not None:
                        btn_dy += v
                elif code == "REL_WHEEL_HI_RES":
                    wv += v
                elif code == "REL_HWHEEL_HI_RES":
                    wh += v

                # Paso de cruceta por swipe: eje dominante, reset de ambos
                if abs(ax) >= args.step or abs(ay) >= args.step:
                    if abs(ax) >= abs(ay):
                        nav("Input.Right" if ax > 0 else "Input.Left",
                            None, "swipe→" + ("R" if ax > 0 else "L"))
                    else:
                        # En pantalla, REL_Y positivo = hacia abajo
                        nav("Input.Down" if ay > 0 else "Input.Up",
                            None, "swipe→" + ("D" if ay > 0 else "U"))
                    ax = ay = 0

                # Rueda vertical -> Up/Down
                while abs(wv) >= args.wheel_step:
                    up = wv > 0  # rueda arriba = subir en lista
                    nav("Input.Up" if up else "Input.Down", None, "wheel")
                    wv += -args.wheel_step if up else args.wheel_step
                # Rueda horizontal -> Left/Right
                while abs(wh) >= args.wheel_step:
                    right = wh > 0
                    nav("Input.Right" if right else "Input.Left", None, "hwheel")
                    wh += -args.wheel_step if right else args.wheel_step
    except KeyboardInterrupt:
        pass
    finally:
        print(f"FIN: raw={n_raw} match={n_match} dev_ok={n_dev}", file=sys.stderr)
        if proc is not None:
            proc.terminate()


if __name__ == "__main__":
    main()
