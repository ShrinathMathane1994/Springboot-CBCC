package com.qa.cbcc.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TestContext - thread-local storage + optional global namespaced cache.
 *
 * - Thread-local (CONTEXT) keeps per-thread values (existing behavior).
 * - GLOBAL_STORE is a ConcurrentMap used for caching data shared by different threads
 *   but namespaced by testCaseId or runId to avoid collisions.
 *
 * Usage patterns:
 * - CONTEXT (set/get) : quick per-thread values (no changes needed).
 * - setXmlContentForTestCase / getXmlContentForTestCase : store XML content globally but namespaced by testCaseId.
 * - setXmlContentForRun / getXmlContentForRun : store XML content globally namespaced by runId (UUID).
 */
public final class TestContext {

    // Per-thread context (existing behavior)
    private static final ThreadLocal<Map<String, Object>> CONTEXT = ThreadLocal.withInitial(HashMap::new);

    // Global namespaced cache for sharing across threads (safe for parallel runs)
    private static final ConcurrentMap<String, Object> GLOBAL_STORE = new ConcurrentHashMap<>();

    // ---------- Existing TestCaseId convenience ----------
    public static void setTestCaseId(Long id) {
        CONTEXT.get().put("testCaseId", id);
    }

    public static Long getTestCaseId() {
        Object val = CONTEXT.get().get("testCaseId");
        return (val instanceof Long) ? (Long) val : null;
    }

    // ---------- Optional runId (recommended to set per run) ----------
    public static void setRunId(String runId) {
        CONTEXT.get().put("runId", runId);
    }

    public static String getRunId() {
        Object val = CONTEXT.get().get("runId");
        return (val instanceof String) ? (String) val : null;
    }

    // ---------- Thread-local generic setters/getters (unchanged) ----------
    public static void set(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        return (T) CONTEXT.get().get(key);
    }

    public static void remove(String key) {
        CONTEXT.get().remove(key);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    // ---------- Global store helpers (concurrent, namespaced) ----------
    public static void setGlobal(String key, Object value) {
        if (value == null) {
            GLOBAL_STORE.remove(key);
        } else {
            GLOBAL_STORE.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getGlobal(String key) {
        return (T) GLOBAL_STORE.get(key);
    }

    public static void removeGlobal(String key) {
        GLOBAL_STORE.remove(key);
    }

    public static void clearGlobal() {
        GLOBAL_STORE.clear();
    }

    // ---------- Namespacing helpers (public so callers can build keys if needed) ----------
    public static String nsKeyByTestCase(Long testCaseId, String key) {
        return "tc::" + (testCaseId == null ? "null" : testCaseId.toString()) + "::" + key;
    }

    public static String nsKeyByRun(String runId, String key) {
        return "run::" + (runId == null ? "null" : runId) + "::" + key;
    }

    // ---------- Convenience API for XML content caching ----------
    // Use testCase-level cache (good when same testCase is processed by different threads but you want shared cache)
    public static void setXmlContentForTestCase(Long testCaseId, String relativePath, String xmlContent) {
        String key = nsKeyByTestCase(testCaseId, "xml::" + (relativePath == null ? "null" : relativePath));
        setGlobal(key, xmlContent);
    }

    public static String getXmlContentForTestCase(Long testCaseId, String relativePath) {
        String key = nsKeyByTestCase(testCaseId, "xml::" + (relativePath == null ? "null" : relativePath));
        return getGlobal(key);
    }

    public static void removeXmlContentForTestCase(Long testCaseId, String relativePath) {
        String key = nsKeyByTestCase(testCaseId, "xml::" + (relativePath == null ? "null" : relativePath));
        removeGlobal(key);
    }

    // Use run-level cache (preferred when you want to isolate cache to a single runId)
    public static void setXmlContentForRun(String runId, String relativePath, String xmlContent) {
        String key = nsKeyByRun(runId, "xml::" + (relativePath == null ? "null" : relativePath));
        setGlobal(key, xmlContent);
    }

    public static String getXmlContentForRun(String runId, String relativePath) {
        String key = nsKeyByRun(runId, "xml::" + (relativePath == null ? "null" : relativePath));
        return getGlobal(key);
    }

    public static void removeXmlContentForRun(String runId, String relativePath) {
        String key = nsKeyByRun(runId, "xml::" + (relativePath == null ? "null" : relativePath));
        removeGlobal(key);
    }
}
