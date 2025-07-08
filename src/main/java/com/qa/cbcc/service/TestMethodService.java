package com.qa.cbcc.service;

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

		try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("com.qa.cbcc") // package
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
	        String className = "com.qa.cbcc." + parts[0];
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
}
