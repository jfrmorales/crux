// cheertok-kodi bridge — binario nativo AUTOCONTENIDO (arm64 Android).
//
// Hace TODO el puente, sin dependencias (ni awk, ni nc, ni getevent):
//   1. Encuentra los devices del CheerTok por NOMBRE (EVIOCGNAME sobre /dev/input/*).
//   2. Los AGARRA en exclusiva (EVIOCGRAB) -> oculta el cursor del sistema y evita
//      que los botones disparen acciones del SO.
//   3. Lee los eventos, mapea gestos a navegacion de cruceta.
//   4. Envia acciones a Kodi por JSON-RPC (HTTP, socket propio).
//
// El MISMO binario lo lanza la app (via Shizuku, en Samsung) o LADB (en OPPO).
//
// Uso:
//   bridge --host 127.0.0.1 --port 8080 --auth <base64 user:pass> \
//          [--step 160] [--wheel 240] [--tapms 400] [--tapmove 40] \
//          [--volup TOKEN] [--voldown TOKEN]
//   TOKEN: none | Input.Home | Input.ContextMenu | Player.PlayPause |
//          VolUp | VolDown | addon:<id>
//
// Imprime por stdout: "READY devices=N" y luego, por accion, "ACT <metodo> <0|1>".

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <poll.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netdb.h>
#include <linux/input.h>

#define MAXDEV 4

// ---- config ----
static char host[128] = "127.0.0.1";
static int  port = 8080;
static char auth[256] = "";
static int  STEP = 160, WHEEL = 240, TAPMS = 400, TAPMOVE = 40;
static char volup[160]  = "VolUp";
static char voldown[160] = "VolDown";

// ---- estado del mapeo ----
static long ax = 0, ay = 0, wv = 0, wh = 0;
static int  btnDown = 0; static long btnT0 = 0, btnDx = 0, btnDy = 0;
static long lastKeyMs = -100000;            // último botón físico pulsado
#define KEY_TAP_GUARD 300                   // ms: ignora taps junto a un botón

static long now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

// Construye el cuerpo JSON-RPC para un "token" de accion. Devuelve 0 si es "none".
static int body_for(const char *action, char *out, size_t n) {
    if (!action[0] || strcmp(action, "none") == 0) return 0;
    if (strncmp(action, "addon:", 6) == 0) {
        snprintf(out, n,
            "{\"jsonrpc\":\"2.0\",\"method\":\"Addons.ExecuteAddon\",\"params\":{\"addonid\":\"%s\"},\"id\":1}",
            action + 6);
    } else if (strcmp(action, "Player.PlayPause") == 0) {
        snprintf(out, n, "{\"jsonrpc\":\"2.0\",\"method\":\"Player.PlayPause\",\"params\":{\"playerid\":1},\"id\":1}");
    } else if (strcmp(action, "VolUp") == 0) {
        snprintf(out, n, "{\"jsonrpc\":\"2.0\",\"method\":\"Application.SetVolume\",\"params\":{\"volume\":\"increment\"},\"id\":1}");
    } else if (strcmp(action, "VolDown") == 0) {
        snprintf(out, n, "{\"jsonrpc\":\"2.0\",\"method\":\"Application.SetVolume\",\"params\":{\"volume\":\"decrement\"},\"id\":1}");
    } else {
        snprintf(out, n, "{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"id\":1}", action);
    }
    return 1;
}

// POST JSON-RPC a Kodi. Devuelve 1 si respondio 2xx.
static int kodi_post(const char *json) {
    struct addrinfo hints, *res = NULL;
    char ports[16]; snprintf(ports, sizeof(ports), "%d", port);
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, ports, &hints, &res) != 0) return 0;
    int ok = 0, fd = -1;
    for (struct addrinfo *p = res; p; p = p->ai_next) {
        fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) continue;
        if (connect(fd, p->ai_addr, p->ai_addrlen) == 0) break;
        close(fd); fd = -1;
    }
    freeaddrinfo(res);
    if (fd < 0) return 0;

    char req[1024];
    int len = (int) strlen(json);
    int hn = snprintf(req, sizeof(req),
        "POST /jsonrpc HTTP/1.0\r\nHost: %s\r\nAuthorization: Basic %s\r\n"
        "Content-Type: application/json\r\nContent-Length: %d\r\n\r\n%s",
        host, auth, len, json);
    if (write(fd, req, hn) == hn) {
        char buf[256];
        int r = (int) read(fd, buf, sizeof(buf) - 1);
        if (r > 0) { buf[r] = 0; if (strstr(buf, " 200") || strstr(buf, " 2")) ok = 1; }
    }
    close(fd);
    return ok;
}

static void act(const char *action) {
    char json[512];
    if (!body_for(action, json, sizeof(json))) return; // "none"
    int ok = kodi_post(json);
    printf("ACT %s %d\n", action, ok);
    fflush(stdout);
}

static void on_key(int code, int value) {
    // Botones físicos del CheerTok: marcan lastKeyMs para el anti-rebote del tap.
    if (value == 1 && code != 272) lastKeyMs = now_ms();
    switch (code) {
        case 158: if (value == 1) act("Input.Back"); break;        // KEY_BACK
        case 139: if (value == 1) act("Input.ContextMenu"); break; // KEY_MENU
        case 172: if (value == 1) act("Input.Home"); break;        // KEY_HOMEPAGE
        case 164: if (value == 1) act("Player.PlayPause"); break;   // KEY_PLAYPAUSE
        case 273: if (value == 1) act("Input.Back"); break;        // BTN_RIGHT
        case 115: if (value == 1) act(volup); break;               // KEY_VOLUMEUP
        case 114: if (value == 1) act(voldown); break;             // KEY_VOLUMEDOWN
        case 272:                                                  // BTN_MOUSE (tap)
            if (value == 1) { btnDown = 1; btnT0 = now_ms(); btnDx = btnDy = 0; }
            else if (value == 0 && btnDown) {
                long now = now_ms(), dt = now - btnT0;
                // Tap válido: corto, sin apenas movimiento y NO junto a un botón
                // físico (evita falsos play/pausa al rozar el touchpad).
                if (dt <= TAPMS && labs(btnDx) <= TAPMOVE && labs(btnDy) <= TAPMOVE
                        && (now - lastKeyMs) > KEY_TAP_GUARD && (btnT0 - lastKeyMs) > KEY_TAP_GUARD)
                    act("Input.Select");
                btnDown = 0;
            }
            break;
    }
}

static void on_rel(int code, int value) {
    switch (code) {
        case 0:  ax += value; if (btnDown) btnDx += value; break;  // REL_X
        case 1:  ay += value; if (btnDown) btnDy += value; break;  // REL_Y
        case 11: wv += value; break;                               // REL_WHEEL_HI_RES
        case 12: wh += value; break;                               // REL_HWHEEL_HI_RES
    }
    if (labs(ax) >= STEP || labs(ay) >= STEP) {
        if (labs(ax) >= labs(ay)) act(ax > 0 ? "Input.Right" : "Input.Left");
        else                      act(ay > 0 ? "Input.Down"  : "Input.Up");
        ax = ay = 0;
    }
    while (labs(wv) >= WHEEL) { if (wv > 0) { act("Input.Up");   wv -= WHEEL; } else { act("Input.Down");  wv += WHEEL; } }
    while (labs(wh) >= WHEEL) { if (wh > 0) { act("Input.Right"); wh -= WHEEL; } else { act("Input.Left"); wh += WHEEL; } }
}

// Encuentra y abre+agarra los devices del CheerTok. Devuelve nº de fds abiertos.
static int open_cheertok(int *fds) {
    int n = 0;
    DIR *d = opendir("/dev/input");
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d)) && n < MAXDEV) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64]; snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0 &&
            (strcmp(name, "CheerTok Air Mouse") == 0 ||
             strcmp(name, "CheerTok Air Consumer Control") == 0)) {
            ioctl(fd, EVIOCGRAB, (void *) 1);
            fds[n++] = fd;
            fprintf(stderr, "bridge: agarrado %s (%s)\n", path, name);
        } else {
            close(fd);
        }
    }
    closedir(d);
    return n;
}

int main(int argc, char **argv) {
    for (int i = 1; i + 1 < argc; i += 2) {
        const char *k = argv[i], *v = argv[i + 1];
        if      (!strcmp(k, "--host"))    strncpy(host, v, sizeof(host) - 1);
        else if (!strcmp(k, "--port"))    port = atoi(v);
        else if (!strcmp(k, "--auth"))    strncpy(auth, v, sizeof(auth) - 1);
        else if (!strcmp(k, "--step"))    STEP = atoi(v);
        else if (!strcmp(k, "--wheel"))   WHEEL = atoi(v);
        else if (!strcmp(k, "--tapms"))   TAPMS = atoi(v);
        else if (!strcmp(k, "--tapmove")) TAPMOVE = atoi(v);
        else if (!strcmp(k, "--volup"))   strncpy(volup, v, sizeof(volup) - 1);
        else if (!strcmp(k, "--voldown")) strncpy(voldown, v, sizeof(voldown) - 1);
    }

    // Bucle externo: (re)conecta al CheerTok. Si se desconecta, vuelve a buscarlo.
    int fds[MAXDEV];
    struct input_event ev;
    for (;;) {
        int n = open_cheertok(fds);
        if (n == 0) { fprintf(stderr, "bridge: esperando CheerTok...\n"); sleep(2); continue; }
        printf("READY devices=%d\n", n); fflush(stdout);

        struct pollfd pfd[MAXDEV];
        for (int i = 0; i < n; i++) { pfd[i].fd = fds[i]; pfd[i].events = POLLIN; }

        int lost = 0;
        while (!lost) {
            int r = poll(pfd, n, -1);
            if (r < 0) { if (errno == EINTR) continue; lost = 1; break; }
            for (int i = 0; i < n; i++) {
                if (pfd[i].revents & (POLLERR | POLLHUP | POLLNVAL)) { lost = 1; break; }
                if (!(pfd[i].revents & POLLIN)) continue;
                ssize_t sz = read(pfd[i].fd, &ev, sizeof(ev));
                if (sz != (ssize_t) sizeof(ev)) { lost = 1; break; }
                if (ev.type == EV_KEY) on_key(ev.code, ev.value);
                else if (ev.type == EV_REL) on_rel(ev.code, ev.value);
            }
        }
        for (int i = 0; i < n; i++) close(fds[i]);
        ax = ay = wv = wh = 0; btnDown = 0;
        fprintf(stderr, "bridge: CheerTok desconectado, reintentando...\n");
        sleep(1);
    }
    return 0;
}
