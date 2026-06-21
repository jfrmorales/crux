# Puente CheerTok -> Kodi, 100% on-device (one-true-awk de Android).
# Lee `getevent -lt` por stdin, mapea gestos a cruceta y los envia a Kodi por
# JSON-RPC usando `nc` (sin curl). Misma logica que el prototipo y la app.
#
# Variables (-v):
#   P8=ruta del 'CheerTok Air Mouse'   P9=ruta del 'CheerTok Air Consumer Control'
#   STEP=160  WHEEL=240  TAPMOVE=40  PORT=8080  AUTH=<base64 user:pass>
#
# El awk de Android no tiene strtonum(), asi que el hex se convierte a mano.

function hex2int(s,   i,c,v,n) {
    v = 0; n = length(s)
    for (i = 1; i <= n; i++) { c = substr(s, i, 1); v = v * 16 + HEX[c] }
    if (v >= 2147483648) v -= 4294967296
    return v
}

function absi(x) { return x < 0 ? -x : x }

function act(method,   body, req, cmd) {
    if (method == "Player.PlayPause")
        body = "{\"jsonrpc\":\"2.0\",\"method\":\"Player.PlayPause\",\"params\":{\"playerid\":1},\"id\":1}"
    else
        body = "{\"jsonrpc\":\"2.0\",\"method\":\"" method "\",\"id\":1}"
    req = sprintf("POST /jsonrpc HTTP/1.0\r\nHost: 127.0.0.1\r\nAuthorization: Basic %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s", AUTH, length(body), body)
    cmd = "nc 127.0.0.1 " PORT " >/dev/null 2>&1"
    print req | cmd
    close(cmd)
    print method > "/dev/stderr"
    fflush("/dev/stderr")
}

BEGIN {
    for (i = 0; i <= 9; i++) HEX["" i] = i
    HEX["a"]=10; HEX["b"]=11; HEX["c"]=12; HEX["d"]=13; HEX["e"]=14; HEX["f"]=15
    HEX["A"]=10; HEX["B"]=11; HEX["C"]=12; HEX["D"]=13; HEX["E"]=14; HEX["F"]=15
    ax=0; ay=0; wv=0; wh=0; btnt=0; btndx=0; btndy=0
    print "bridge.awk activo (P8=" P8 " P9=" P9 ")" > "/dev/stderr"
}

{
    dev = $3; sub(/:$/, "", dev)
    if (dev != P8 && dev != P9) next
    ev = $4; code = $5; val = $6

    if (ev == "EV_KEY") {
        pressed = (val == "DOWN" || val == "DOWN_REPEAT") ? 1 : ((val == "UP") ? 0 : -1)
        if (code == "KEY_BACK" && pressed == 1) act("Input.Back")
        else if (code == "KEY_MENU" && pressed == 1) act("Input.ContextMenu")
        else if (code == "KEY_HOMEPAGE" && pressed == 1) act("Input.Home")
        else if (code == "KEY_PLAYPAUSE" && pressed == 1) act("Player.PlayPause")
        else if (code == "BTN_RIGHT" && pressed == 1) act("Input.Back")
        else if (code == "BTN_MOUSE") {
            if (pressed == 1) { btnt = 1; btndx = 0; btndy = 0 }
            else if (pressed == 0 && btnt == 1) {
                if (absi(btndx) <= TAPMOVE && absi(btndy) <= TAPMOVE) act("Input.Select")
                btnt = 0
            }
        }
        next
    }

    if (ev == "EV_REL") {
        v = hex2int(val)
        if (code == "REL_X") { ax += v; if (btnt == 1) btndx += v }
        else if (code == "REL_Y") { ay += v; if (btnt == 1) btndy += v }
        else if (code == "REL_WHEEL_HI_RES") wv += v
        else if (code == "REL_HWHEEL_HI_RES") wh += v

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
}
