package com.qa.cbcc.utils;


public class TestContext {
 private static final ThreadLocal<Long> testCaseId = new ThreadLocal<>();

 public static void setTestCaseId(Long id) {
     testCaseId.set(id);
 }

 public static Long getTestCaseId() {
     return testCaseId.get();
 }

 public static void clear() {
     testCaseId.remove();
 }
}
