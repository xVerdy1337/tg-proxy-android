#include <string.h>

#include <jni.h>
#include <getopt.h>
#include <stdlib.h>

#include "byedpi/error.h"
#include "main.h"

extern int server_fd;

// Failures that belong to THIS shim rather than to byedpi's main(), kept outside the range main()
// can produce. main() answers 0 on success, -1 when init()/run() fails — which on Android means the
// bind lost, i.e. the port really is taken — and parse_args' status - 1 (so -2) for a malformed
// command. Returning a bare -1 from here made "another run already owns the singleton"
// indistinguishable from "port busy", and the VPN then told the user to go close ByeByeDPI and
// other SOCKS apps when the truth was an auto-tune sweep still holding the engine inside this very
// process: nothing to close, no way to act on the advice. Keep these in sync with ByeDpiExit
// (core/ByeDpiProxy.kt), which is where the Kotlin side reads them.
#define BYEDPI_ENGINE_BUSY (-11)
#define BYEDPI_ARGV_OOM    (-12)

// Plain int, read and written only through the __atomic_* builtins: they need a type they can
// operate on, and the SEQ_CST ordering is what keeps this flag consistent with the server_fd stores
// it is paired with. A plain store here would let the epilogue's "not running" become visible ahead
// of the invalidation that precedes it, and a start slipping through that window would inherit a
// descriptor the allocator may already have handed to somebody else.
static int g_proxy_running = 0;

// Which start owns the two globals above. jniStartProxy takes the next number once it has won the
// gate and re-checks it on the way out: a run that a later start has superseded must clear nothing,
// or it wipes the live run's descriptor and lowers its flag while unwinding, after which that run's
// own stop/force-close find nothing to act on and its thread plus its listening port leak for the
// life of the process. Now that admission is a single test-and-set, only the owning run can lower
// the flag and no other start gets in while it is raised, so this is the backstop for an ordering
// we no longer expect to break rather than a race we expect to hit.
static unsigned g_proxy_generation = 0;

// server_fd is byedpi's own global (byedpi/proxy.c): it reads 0 until start_event_loop() publishes
// the listener and it keeps that number after destroy_pool() closed the socket on the way out of
// main(). The stale case is the dangerous one: the kernel hands freed descriptors straight back
// out, so a late teardown call would shutdown()/close() whatever socket another thread opened in
// the meantime. We park it at -1 whenever we know there is no listener and treat anything <= 0 as
// "nothing to tear down" (fd 0 is never ours: stdio owns it in an app process). The engine does
// not care: the only other readers are start_event_loop(), which overwrites it unconditionally on
// the next run, and the SIGINT/SIGTERM handler, whose shutdown() on -1 is a harmless EBADF and is
// never reached under Android anyway.
static void clear_server_fd(void) {
    __atomic_store_n(&server_fd, -1, __ATOMIC_SEQ_CST);
}

struct params default_params = {
        .await_int = 10,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
            .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
            .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_com_tgwsproxy_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    // Claim the run in one step, before any shared state is touched. Reading the flag here and
    // storing it further down left the whole argv marshalling loop inside the window: a second
    // caller could pass the guard after the first had already bound, wipe its live descriptor from
    // this prologue and then publish its own listener over the global, stranding the first run's
    // thread and its auth-less SOCKS port where no teardown call can reach them. A caller that
    // loses the exchange leaves every global exactly as it found it.
    int idle = 0;
    if (!__atomic_compare_exchange_n(&g_proxy_running, &idle, 1, 0,
            __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {
        LOG(LOG_S, "proxy already running");
        return BYEDPI_ENGINE_BUSY;
    }

    // Taken under the gate, so "running" always implies server_fd belongs to *this* run. Dropping
    // the previous number matters because a command that dies in argument parsing never reaches
    // start_event_loop(), and the teardown that follows (StrategyTester's finally block fires the
    // moment the loop thread exits) would otherwise act on the number the last listener left here.
    clear_server_fd();
    unsigned generation = __atomic_add_fetch(&g_proxy_generation, 1u, __ATOMIC_SEQ_CST);

    // A NULL args array only reaches here from a caller bug; GetArrayLength on NULL is UB, so
    // refuse before touching it.
    if (!args) {
        LOG(LOG_S, "args array is null");
        __atomic_store_n(&g_proxy_running, 0, __ATOMIC_SEQ_CST);
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, args);
    // argc + 1 so argv[argc] stays NULL per the POSIX convention: byedpi stops at argc, but any
    // code that scans argv to the NULL terminator would read past the allocation otherwise.
    char **argv = calloc(argc + 1, sizeof(char *));

    if (!argv) {
        LOG(LOG_S, "failed to allocate memory for argv");
        // main() never runs, so the epilogue that lowers the flag never runs either: give the gate
        // back here or every later start is refused for the life of the process.
        __atomic_store_n(&g_proxy_running, 0, __ATOMIC_SEQ_CST);
        return BYEDPI_ARGV_OOM;
    }

    // Any failure in this loop must abort the whole start, not leave a hole in argv: a NULL
    // entry goes straight into main()/getopt_long and dereferences as a segfault. A pending JNI
    // exception additionally forbids arbitrary JNI calls, so check for and clear it before
    // unwinding (ExceptionCheck/ExceptionClear/DeleteLocalRef are legal with one pending).
    int marshal_failed = 0;
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            if (arg) (*env)->DeleteLocalRef(env, arg);
            marshal_failed = 1;
            break;
        }
        if (!arg) {
            marshal_failed = 1;
            break;
        }

        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, arg);
            marshal_failed = 1;
            break;
        }
        if (!arg_str) {
            (*env)->DeleteLocalRef(env, arg);
            marshal_failed = 1;
            break;
        }

        argv[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);

        if (!argv[i]) {
            marshal_failed = 1;
            break;
        }
    }

    if (marshal_failed) {
        LOG(LOG_S, "failed to marshal args");
        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        // Same as the calloc failure above: main() never runs, so give the gate back here.
        __atomic_store_n(&g_proxy_running, 0, __ATOMIC_SEQ_CST);
        return -1;
    }

    LOG(LOG_S, "starting proxy with %d args", argc);
    reset_params();
    optind = 1;

    int result = main(argc, argv);

    LOG(LOG_S, "proxy return code %d", result);
    // destroy_pool() has already closed the listener by now. Invalidate before clearing the flag,
    // never after: a teardown call that read the flag one instruction too early must then find -1
    // instead of a number the allocator may have reissued. Both stores belong to whoever still owns
    // the generation; once a newer start has taken it, that state is the newer run's, not ours.
    if (__atomic_load_n(&g_proxy_generation, __ATOMIC_SEQ_CST) == generation) {
        clear_server_fd();
        __atomic_store_n(&g_proxy_running, 0, __ATOMIC_SEQ_CST);
    } else {
        LOG(LOG_S, "proxy run %u superseded, leaving teardown to the current run", generation);
    }

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    return result;
}

/**
 * Is the engine claimed right now?
 *
 * The busy flag is raised by jniStartProxy's gate and lowered only by its epilogue — jniStopProxy
 * and jniForceClose deliberately leave it raised, because main() still owns the event loop when they
 * return. That made occupancy unobservable from Kotlin: a teardown that timed out looked exactly
 * like a clean finish, so a wedged run was reported as "done" and every later start was refused with
 * no explanation. Exposing the flag is what lets the callers tell those two apart, and it is a plain
 * SEQ_CST load — an answer that is already stale by the time it is read, so treat it as "was busy a
 * moment ago" and never as a reservation. Claiming is still jniStartProxy's test-and-set alone.
 */
JNIEXPORT jboolean JNICALL
Java_com_tgwsproxy_core_ByeDpiProxy_jniIsRunning(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    return __atomic_load_n(&g_proxy_running, __ATOMIC_SEQ_CST) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_tgwsproxy_core_ByeDpiProxy_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "send shutdown to proxy");

    // Do NOT clear g_proxy_running here: main() still owns the event loop. Clearing early lets a
    // second jniStartProxy race the first main() and fail with bind/"already running". The flag
    // is lowered only by the run that raised it, on its way out of jniStartProxy.
    if (!__atomic_load_n(&g_proxy_running, __ATOMIC_SEQ_CST)) {
        LOG(LOG_S, "proxy is not running");
        return -1;
    }

    // The flag alone is not proof of a listener: it is raised before main() even parses argv, so a
    // run that bails on a bad command leaves us here with no socket to shut down. Take and
    // invalidate the descriptor in a single exchange: between a plain load and the shutdown() the
    // event pool could close the fd and the kernel reissue the number to an unrelated socket,
    // which we would then shut down (the TOCTOU jniForceClose already guards this way).
    // shutdown() does not free the descriptor — the pool's own close() still owns that — and
    // destroy_pool() never reads server_fd, so invalidating here cannot double-close; it only
    // turns a later jniForceClose into a harmless no-op.
    int fd = __atomic_exchange_n(&server_fd, -1, __ATOMIC_SEQ_CST);
    if (fd <= 0) {
        LOG(LOG_S, "no listening socket to shut down");
        return -1;
    }

    shutdown(fd, SHUT_RDWR);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_tgwsproxy_core_ByeDpiProxy_jniForceClose(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    // Same as stop: only tear down the listen fd so main() can unwind; leave g_proxy_running set.
    // Deliberately NOT gated on that flag: this is the escalation callers reach for after the
    // flag-driven teardown has already failed them, so refusing whenever the flag reads 0 would
    // forfeit the last escape hatch for a listener that outlived its run.

    // Take the descriptor and invalidate it in the same step. close() returns the number to the
    // allocator immediately, so a second pass through here (the VPN teardown and the strategy
    // tester run on different threads but share this one native global) would close a socket that
    // by then belongs to somebody else.
    int fd = __atomic_exchange_n(&server_fd, -1, __ATOMIC_SEQ_CST);
    if (fd <= 0) {
        LOG(LOG_S, "no listening socket to close");
        return -1;
    }

    LOG(LOG_S, "closing server socket (fd: %d)", fd);
    if (close(fd) == -1) {
        LOG(LOG_S, "failed to close server socket (fd: %d)", fd);
        return -1;
    }

    LOG(LOG_S, "proxy socket force close");
    return 0;
}
