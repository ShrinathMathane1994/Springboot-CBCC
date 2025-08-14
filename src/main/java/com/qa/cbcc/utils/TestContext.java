package com.qa.cbcc.utils;

import java.util.HashMap;
import java.util.Map;

public class TestContext {

    // Store generic key/value pairs per thread
    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(HashMap::new);

    // Existing behavior for testCaseId
    public static void setTestCaseId(Long id) {
        CONTEXT.get().put("testCaseId", id);
    }

    public static Long getTestCaseId() {
        Object val = CONTEXT.get().get("testCaseId");
        return (val instanceof Long) ? (Long) val : null;
    }

    // New generic setters/getters
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
}
