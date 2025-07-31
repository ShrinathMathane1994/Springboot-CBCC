package com.qa.cbcc.utils;

public class LoggingGuard {
    private static final ThreadLocal<Boolean> hasLogged = ThreadLocal.withInitial(() -> false);

    public static boolean shouldLog() {
        return !hasLogged.get();
    }

    public static void markLogged() {
        hasLogged.set(true);
    }

    public static void reset() {
        hasLogged.remove(); // optional if each test runs in its own thread
    }
}
