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
import org.testng.xml.XmlInclude;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

@Service
public class TestMethodService {

	public List<String> getTestMethods() {
		List<String> testMethods = new ArrayList<>();

		try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("com.qa.cbcc")
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

//	public Map<String, String> runTestMethod(String methodName) {
//	    Map<String, String> response = new HashMap<>();
//	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//	    PrintStream originalOut = System.out;
//	    System.setOut(new PrintStream(outputStream));
//
//	    try {
//	        String[] parts = methodName.split("\\.");
//	        String className = "com.qa.cbcc." + parts[0];
//	        String method = parts[1];
//
//	        String suiteName = parts[0] + "." + method; 
//	        String testName = "Test for " + method;     
//
//	        XmlSuite suite = new XmlSuite();
//	        suite.setName(suiteName); 
//
//	        XmlTest test = new XmlTest(suite);
//	        test.setName(testName); 
//	        
//	        XmlClass xmlClass = new XmlClass(className);
//	        XmlInclude includeMethod = new XmlInclude(method);
//	        xmlClass.setIncludedMethods(Collections.singletonList(includeMethod));
//	        test.setXmlClasses(Collections.singletonList(xmlClass));
//
//	        TestNG testng = new TestNG();
//	        testng.setUseDefaultListeners(false);
//	        testng.setXmlSuites(Collections.singletonList(suite));
//	        testng.run();
//
//	        response.put("status", "Success");
//	    } catch (Exception e) {
//	        response.put("status", "Error: " + e.getMessage());
//	    } finally {
//	        System.setOut(originalOut);
//	    }
//
//	    String cleanedOutput = outputStream.toString()
//	        .replaceAll("[\\r\\n]+", ", ")
//	        .replaceAll("=+", "")
//	        .replaceAll(",\\s*,", ", ")
//	        .replaceAll("(^,\\s*)|(,\\s*$)", "")
//	        .trim();
//
//	    response.put("output", cleanedOutput);
//	    return response;
//	}


	public Map<String, String> runTestMethod(String methodNameWithParams) {
	    Map<String, String> response = new HashMap<>();
	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	    PrintStream originalOut = System.out;
	    System.setOut(new PrintStream(outputStream));

	    try {
	        // Split method name and parameters: class.method:param1:param2
	        String[] allParts = methodNameWithParams.split(":");
	        String fullMethod = allParts[0]; // e.g. JsonComparatorTest.jsonCompareTest
	        String[] parts = fullMethod.split("\\.");
	        String className = "com.qa.cbcc." + parts[0];
	        String method = parts[1];

	        String suiteName = parts[0] + "." + method;
	        String testName = "Test for " + method;

	        XmlSuite suite = new XmlSuite();
	        suite.setName(suiteName);

	        XmlTest test = new XmlTest(suite);
	        test.setName(testName);

	        XmlClass xmlClass = new XmlClass(className);
	        XmlInclude includeMethod = new XmlInclude(method);

	        // 💡 Pass method parameters to TestNG via XmlInclude
	        if (allParts.length > 2) {
	            List<String> parameters = new ArrayList<>();
	            for (int i = 1; i < allParts.length; i++) {
	                parameters.add(allParts[i]);
	            }
	            includeMethod.setParameters(Map.of(
	                "json1", parameters.get(0),
	                "json2", parameters.get(1)
	            ));
	        }

	        xmlClass.setIncludedMethods(Collections.singletonList(includeMethod));
	        test.setXmlClasses(Collections.singletonList(xmlClass));

	        TestNG testng = new TestNG();
	        testng.setUseDefaultListeners(false);
	        testng.setXmlSuites(Collections.singletonList(suite));
	        testng.run();

	        if (outputStream.toString().contains("Failures: 0")) {
	            response.put("status", "Success");
	        } else {
	            response.put("status", "Failure");
	        }

	    } catch (Exception e) {
	        response.put("status", "Error: " + e.getMessage());
	    } finally {
	        System.setOut(originalOut);
	    }

	    String cleanedOutput = outputStream.toString()
	        .replaceAll("[\\r\\n]+", ", ")
	        .replaceAll("=+", "")
	        .replaceAll(",\\s*,", ", ")
	        .replaceAll("(^,\\s*)|(,\\s*$)", "")
	        .trim();

	    response.put("output", cleanedOutput);
	    return response;
	}

}
