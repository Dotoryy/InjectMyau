package myau.inject;

import myau.Myau;

public final class Bootstrap {
    private static volatile boolean requested;
    private static volatile boolean started;
    private static volatile boolean stopRequested;
    private Bootstrap() {
    }
    public static void requestStart() {
        requested = true;
    }

    public static void requestStop() {
        stopRequested = true;
    }

    public static void tick() {
        if (stopRequested) {
            stopRequested = false;
            requested = false;
            if (started) {
                try {
                    log("unloading client");
                    Myau.shutdown();
                    log("client unloaded -- run the loader again to bring it back");
                } catch (Throwable t) {
                    Log.throwable("client failed to unload cleanly", t);
                } finally {
                    started = false;
                }
            }
            return;
        }
        if (!requested || started) {
            return;
        }
        started = true;
        try {
            log("constructing client on the game thread");
            new Myau();
            log("client ready");
        } catch (Throwable t) {
            Log.throwable("client failed to start", t);
        }
    }
    public static boolean isStarted() {
        return started;
    }
    private static void log(String message) {
        Log.line(message);
    }
}
