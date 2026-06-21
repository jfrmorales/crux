// evgrab: abre uno o varios /dev/input/eventX, los AGARRA en exclusiva
// (EVIOCGRAB) para que Android deje de procesarlos (oculta el puntero del raton
// y evita que los botones disparen acciones del sistema), y emite cada evento
// como "TIPO CODIGO VALOR" en decimal con signo por stdout.
//
// Uso: evgrab /dev/input/eventX [/dev/input/eventY ...]
//
// Pensado para encadenar con bridge_num.awk:
//   evgrab $MOUSE $KEYS | awk -f bridge_num.awk

#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define MAXDEV 8

int main(int argc, char **argv) {
    int n = argc - 1;
    if (n <= 0) {
        fprintf(stderr, "usage: evgrab <dev> [dev...]\n");
        return 1;
    }
    if (n > MAXDEV) n = MAXDEV;

    struct pollfd fds[MAXDEV];
    for (int i = 0; i < n; i++) {
        int fd = open(argv[i + 1], O_RDONLY);
        if (fd < 0) {
            fprintf(stderr, "evgrab: open %s: %s\n", argv[i + 1], strerror(errno));
            return 1;
        }
        if (ioctl(fd, EVIOCGRAB, (void *)1) < 0) {
            // Sin grab seguiriamos funcionando, pero el cursor no se ocultaria.
            fprintf(stderr, "evgrab: grab %s: %s\n", argv[i + 1], strerror(errno));
        }
        fds[i].fd = fd;
        fds[i].events = POLLIN;
        fds[i].revents = 0;
    }
    fprintf(stderr, "evgrab: %d device(s) agarrados\n", n);

    struct input_event ev;
    for (;;) {
        int r = poll(fds, n, -1);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = 0; i < n; i++) {
            if (fds[i].revents & POLLIN) {
                ssize_t sz = read(fds[i].fd, &ev, sizeof(ev));
                if (sz == (ssize_t)sizeof(ev)) {
                    // EV_SYN se omite; no lo necesita el mapeo.
                    if (ev.type != EV_SYN) {
                        printf("%u %u %d\n", ev.type, ev.code, ev.value);
                        fflush(stdout);
                    }
                }
            }
            fds[i].revents = 0;
        }
    }
    return 0;
}
