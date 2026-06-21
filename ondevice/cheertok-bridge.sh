#!/system/bin/sh
# Lanzador del puente CheerTok -> Kodi (on-device).
#
# Requiere ejecutarse con privilegios de `shell` (cualquiera de estos sirve):
#   - LADB (app) / depuracion inalambrica adb-a-si-mismo   <- funciona en OPPO y Samsung
#   - Shizuku `rish`                                        <- Samsung (en OPPO ColorOS lo bloquea)
#   - adb shell desde un PC
#
# Resuelve los devices del CheerTok por NOMBRE (el numero de eventN cambia al
# reconectar) y reinicia el puente si el mando se desconecta/reconecta.
#
# Ajustes por variable de entorno (opcionales):
#   PORT (8080)  AUTH (base64 de user:pass, def kodi:kodi)  STEP (160)  WHEEL (240)  TAPMOVE (40)

DIR="$(dirname "$0")"
[ -f "$DIR/evgrab" ] || DIR=/data/local/tmp

PORT="${PORT:-8080}"
AUTH="${AUTH:-a29kaTprb2Rp}"     # base64("kodi:kodi")
STEP="${STEP:-160}"
WHEEL="${WHEEL:-240}"
TAPMOVE="${TAPMOVE:-40}"

resolve() {
    getevent -i 2>/dev/null | tr -d '\r' | awk '
        /add device/ { p = $0; sub(/.*: /, "", p) }
        /name:/ {
            if ($0 ~ /CheerTok Air Mouse/) print p
            else if ($0 ~ /CheerTok Air Consumer Control/) print p
        }'
}

echo "Puente CheerTok -> Kodi (Ctrl-C para salir)"
while true; do
    DEVS=$(resolve)
    if [ -n "$DEVS" ]; then
        echo "CheerTok conectado: $(echo $DEVS | tr '\n' ' ')"
        # shellcheck disable=SC2086
        "$DIR/evgrab" $DEVS | awk \
            -v STEP="$STEP" -v WHEEL="$WHEEL" -v TAPMOVE="$TAPMOVE" \
            -v PORT="$PORT" -v AUTH="$AUTH" -f "$DIR/bridge_num.awk"
        echo "Puente detenido (¿CheerTok desconectado?). Reintentando..."
    else
        echo "Esperando al CheerTok por Bluetooth..."
    fi
    sleep 2
done
