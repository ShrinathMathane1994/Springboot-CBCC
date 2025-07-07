package com.example.testscanner.service;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.testng.TestNG;
import org.testng.annotations.Test;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

@Service
public class TestMethodService {

	public List<String> getTestMethods() {
		List<String> testMethods = new ArrayList<>();

		try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("com.example.testscanner") // package
				.scan()) {
			scanResult.getAllClasses().forEach(classInfo -> {
				try {
					Class<?> clazz = Class.forName(classInfo.getName());
					for (Method method : clazz.getDeclaredMethods()) {
						if (method.isAnnotationPresent(Test.class)) {
							testMethods.add(clazz.getSimpleName() + "." + method.getName());
						}
					}
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}
			});
		}

		return testMethods;
	}
	

	public Map<String, String> runTestMethod(String methodName) {
	    Map<String, String> response = new HashMap<>();
	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	    PrintStream originalOut = System.out;
	    System.setOut(new PrintStream(outputStream));

	    try {
	        String[] parts = methodName.split("\\.");
	        String className = "com.example.testscanner." + parts[0];
	        String method = parts[1];

	        XmlSuite suite = new XmlSuite();
	        suite.setName("DynamicSuite");

	        XmlTest test = new XmlTest(suite);
	        test.setName("DynamicTest");

	        XmlClass xmlClass = new XmlClass(className);
	        xmlClass.setExcludedMethods(Collections.singletonList(method));
	        test.setXmlClasses(Collections.singletonList(xmlClass));

	        TestNG testng = new TestNG();
	        testng.setXmlSuites(Collections.singletonList(suite));
	        testng.run();

	        response.put("status", "success");
	    } catch (Exception e) {
	        response.put("status", "error: " + e.getMessage());
	    } finally {
	        System.setOut(originalOut);
	    }

	    response.put("output", outputStream.toString().replace("\r\n", " , "));

	    return response;
	}

//    public List<String> getTestMethods() {
//        List<String> testMethods = new ArrayList<>();
//        try {
//            File testDir = new File("target/test-classes/com/example/testscanner");
//            System.out.println("Scanning directory: " + testDir.getAbsolutePath());
//
//            URL[] urls = {testDir.toURI().toURL()};
//            try (URLClassLoader classLoader = new URLClassLoader(urls)) {
//                File[] files = testDir.listFiles((dir, name) -> name.endsWith(".class"));
//                if (files != null) {
//                    for (File file : files) {
//                        System.out.println("Found class file: " + file.getName());
//                        String className = "com.example.testscanner." + file.getName().replace(".class", "");
//                        Class<?> clazz = classLoader.loadClass(className);
//                        for (Method method : clazz.getDeclaredMethods()) {
//                            if (method.isAnnotationPresent(Test.class)) {
//                                testMethods.add(clazz.getSimpleName() + "." + method.getName());
//                            }
//                        }
//                    }
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return testMethods;
//    }

//	public String runTestMethod(String methodName) {
//	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//	    PrintStream originalOut = System.out;
//	    System.setOut(new PrintStream(outputStream));
//
//	    try {
//	        String[] parts = methodName.split("\\.");
//	        String className = "com.example.testscanner." + parts[0];
//	        String method = parts[1];
//
//	        File testDir = new File("target/test-classes/com/example/testscanner");
//	        URL[] urls = {testDir.toURI().toURL()};
//	        try (URLClassLoader classLoader = new URLClassLoader(urls)) {
//	            Class<?> clazz = classLoader.loadClass(className);
//	            Object instance = clazz.getDeclaredConstructor().newInstance();
//	            Method testMethod = clazz.getDeclaredMethod(method);
//	            testMethod.invoke(instance);
//	        }
//
//	        System.setOut(originalOut); // Restore original output
//	        String logs = outputStream.toString();
//	        return "Executed " + methodName + " successfully.\n\nConsole Output:\n" + logs;
//
//	    } catch (Exception e) {
//	        System.setOut(originalOut); // Restore even on error
//	        return "Error executing " + methodName + ": " + e.getMessage();
//	    }
//	}

//	public String runTestMethod(String methodName) {
//	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//	    PrintStream originalOut = System.out;
//	    System.setOut(new PrintStream(outputStream));
//
//	    String status;
//	    try {
//	        String[] parts = methodName.split("\\.");
//	        String className = "com.example.testscanner." + parts[0];
//	        String method = parts[1];
//
//	        File testDir = new File("target/test-classes/com/example/testscanner");
//	        URL[] urls = {testDir.toURI().toURL()};
//	        try (URLClassLoader classLoader = new URLClassLoader(urls)) {
//	            Class<?> clazz = classLoader.loadClass(className);
//	            Object instance = clazz.getDeclaredConstructor().newInstance();
//	            Method testMethod = clazz.getDeclaredMethod(method);
//	            testMethod.invoke(instance);
//	        }
//
//	        status = "success";
//	    } catch (Exception e) {
//	        status = "error: " + e.getMessage();
//	    } finally {
//	        System.setOut(originalOut); // Restore original output
//	    }
//
//	    String logs = outputStream.toString();
//
//	    return "{\n" +
//	           "  \"status\": \"" + status + "\",\n" +
//	           "  \"output\": \"" + logs.replace("\n", "\\n").replace("\"", "\\\"") + "\"\n" +
//	           "}";
//	}

//	public Map<String, String> runTestMethod(String methodName) {
//	    Map<String, String> response = new HashMap<>();
//	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//	    PrintStream originalOut = System.out;
//	    System.setOut(new PrintStream(outputStream));
//
//	    try {
//	        String[] parts = methodName.split("\\.");
//	        String className = "com.example.testscanner." + parts[0];
//	        String method = parts[1];
//
//	        File testDir = new File("target/test-classes/com/example/testscanner");
//	        URL[] urls = {testDir.toURI().toURL()};
//	        try (URLClassLoader classLoader = new URLClassLoader(urls)) {
//	            Class<?> clazz = classLoader.loadClass(className);
//	            Object instance = clazz.getDeclaredConstructor().newInstance();
//	            Method testMethod = clazz.getDeclaredMethod(method);
//	            testMethod.invoke(instance);
//	        }
//
//	        response.put("status", "success");
//	    } catch (Exception e) {
//	        response.put("status", "error: " + e.getMessage());
//	    } finally {
//	        System.setOut(originalOut);
//	    }
//
////	    response.put("output", outputStream.toString());
//	    response.put("output", outputStream.toString().replace("\r\n", " , "));
//	    return response;
//	}
	


}
