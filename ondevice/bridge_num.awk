# Puente CheerTok -> Kodi, variante NUMERICA (para encadenar tras `evgrab`).
# Entrada por stdin: lineas "TIPO CODIGO VALOR" en decimal con signo.
#   TIPO: 1=EV_KEY 2=EV_REL
#   codigos (numericos):
#     REL_X=0 REL_Y=1 REL_WHEEL_HI_RES=11 REL_HWHEEL_HI_RES=12
#     BTN_MOUSE=272 BTN_RIGHT=273
#     KEY_BACK=158 KEY_MENU=139 KEY_HOMEPAGE=172 KEY_PLAYPAUSE=164
#   valor de tecla: 1=pulsada 0=soltada 2=repeticion
# Variables (-v): STEP WHEEL TAPMOVE PORT AUTH

function absi(x) { return x < 0 ? -x : x }

function act(method,   body, req, cmd) {
    if (method == "Player.PlayPause")
        body = "{\"jsonrpc\":\"2.0\",\"method\":\"Player.PlayPause\",\"params\":{\"playerid\":1},\"id\":1}"
    else if (method == "VolUp")
        body = "{\"jsonrpc\":\"2.0\",\"method\":\"Application.SetVolume\",\"params\":{\"volume\":\"increment\"},\"id\":1}"
    else if (method == "VolDown")
        body = "{\"jsonrpc\":\"2.0\",\"method\":\"Application.SetVolume\",\"params\":{\"volume\":\"decrement\"},\"id\":1}"
    else
        body = "{\"jsonrpc\":\"2.0\",\"method\":\"" method "\",\"id\":1}"
    req = sprintf("POST /jsonrpc HTTP/1.0\r\nHost: 127.0.0.1\r\nAuthorization: Basic %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s", AUTH, length(body), body)
    cmd = "nc 127.0.0.1 " PORT " >/dev/null 2>&1"
    print req | cmd
    close(cmd)
    print method > "/dev/stderr"
    fflush("/dev/stderr")
}

BEGIN { ax=0; ay=0; wv=0; wh=0; btnt=0; btndx=0; btndy=0 }

$1 == 1 {                                  # EV_KEY
    code = $2; v = $3
    if      (code == 158 && v == 1) act("Input.Back")
    else if (code == 139 && v == 1) act("Input.ContextMenu")
    else if (code == 172 && v == 1) act("Input.Home")
    else if (code == 164 && v == 1) act("Player.PlayPause")
    else if (code == 273 && v == 1) act("Input.Back")
    else if (code == 115 && v == 1) act("VolUp")      # KEY_VOLUMEUP
    else if (code == 114 && v == 1) act("VolDown")    # KEY_VOLUMEDOWN
    else if (code == 272) {                # BTN_MOUSE (tap)
        if (v == 1) { btnt = 1; btndx = 0; btndy = 0 }
        else if (v == 0 && btnt == 1) {
            if (absi(btndx) <= TAPMOVE && absi(btndy) <= TAPMOVE) act("Input.Select")
            btnt = 0
        }
    }
    next
}

$1 == 2 {                                  # EV_REL
    code = $2; v = $3
    if      (code == 0)  { ax += v; if (btnt == 1) btndx += v }   # REL_X
    else if (code == 1)  { ay += v; if (btnt == 1) btndy += v }   # REL_Y
    else if (code == 11) wv += v                                   # REL_WHEEL_HI_RES
    else if (code == 12) wh += v                                   # REL_HWHEEL_HI_RES

    if (absi(ax) >= STEP || absi(ay) >= STEP) {
        if (absi(ax) >= absi(ay)) act(ax > 0 ? "Input.Right" : "Input.Left")
        else act(ay > 0 ? "Input.Down" : "Input.Up")
        ax = 0; ay = 0
    }
    while (absi(wv) >= WHEEL) {
        if (wv > 0) { act("Input.Up"); wv -= WHEEL } else { act("Input.Down"); wv += WHEEL }
    }
    while (absi(wh) >= WHEEL) {
        if (wh > 0) { act("Input.Right"); wh -= WHEEL } else { act("Input.Left"); wh += WHEEL }
    }
}
