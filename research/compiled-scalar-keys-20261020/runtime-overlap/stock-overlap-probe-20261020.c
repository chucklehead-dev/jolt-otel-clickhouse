#define _POSIX_C_SOURCE 200809L
#include <stdatomic.h>
#include <errno.h>
#include <time.h>

static atomic_int phase;
int overlap_probe_phase(void) { return atomic_load(&phase); }
int overlap_probe_reset(void) { atomic_store(&phase, 0); return 0; }
int overlap_probe_wait(int millis) {
    if (millis < 0 || millis > 5000) return -1;
    struct timespec wait = { millis / 1000, (millis % 1000) * 1000000L };
    atomic_store(&phase, 1);
    while (nanosleep(&wait, &wait) != 0) {
        if (errno != EINTR) { atomic_store(&phase, 3); return -2; }
    }
    atomic_store(&phase, 2);
    return 0;
}
