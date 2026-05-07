# koci samples

Each file in this module is a self-contained function that demonstrates one feature end-to-end. They are deliberately the smallest possible programs that exercise the API. For a tour of the variants each operation supports, see the **Usage** section in the [root README](../README.md).

## A note on JVM exit

A sample may appear to hang for up to 60 seconds after `Koci.close()` returns. This is OkHttp behavior, not a leak. OkHttp's `Dispatcher` has worker threads that are non-daemon and idle for 60 seconds before terminating, and the JVM waits on them.

`Koci.close()` calls `client.close()`, which gracefully shuts the engine down, but graceful shutdown lets the workers idle out their keep-alive. To exit the moment work is done, end `main` with `kotlin.system.exitProcess(0)`:

Long-lived apps (services, Android, desktop) don't see this; `Koci` is held for the process lifetime and the dispatcher threads are reused.
