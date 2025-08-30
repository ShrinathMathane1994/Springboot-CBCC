package com.qa.cbcc.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.testng.TestNG;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlInclude;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

@Service
public class TestMethodService {

    public List<Map<String, String>> getTestMethods() {
        List<Map<String, String>> testMethods = new ArrayList<>();

        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("com.qa.cbcc").scan()) {
            scanResult.getAllClasses().forEach(classInfo -> {
                try {
                    Class<?> clazz = Class.forName(classInfo.getName());
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (method.isAnnotationPresent(org.testng.annotations.Test.class)) {
                            Map<String, String> methodDetails = new HashMap<>();
                            methodDetails.put("className", clazz.getSimpleName());
                            methodDetails.put("methodName", method.getName());
                            methodDetails.put("qualifiedName", clazz.getSimpleName() + "." + method.getName());
                            testMethods.add(methodDetails);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            });
        }

        return testMethods;
    }

    public Map<String, String> runTestMethod2(String methodNameWithParams) {
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
                // includeMethod.setParameters(Map.of("json1", parameters.get(0), "json2", parameters.get(1))); // ❌ Java 9+
                Map<String, String> paramMap = new HashMap<>();
                paramMap.put("json1", parameters.get(0));
                paramMap.put("json2", parameters.get(1));
                includeMethod.setParameters(paramMap);
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

        String cleanedOutput = outputStream.toString().replaceAll("[\\r\\n]+", ", ").replaceAll("=+", "")
                .replaceAll(",\\s*,", ", ").replaceAll("(^,\\s*)|(,\\s*$)", "").trim();

        response.put("output", cleanedOutput);
        return response;
    }

    public Map<String, Object> runTestMethod(String methodNameWithParams) {
        Map<String, Object> response = new HashMap<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try {
            System.out.println("Received methodNameWithParams: " + methodNameWithParams);
            String[] allParts = methodNameWithParams.split("###");

            if (allParts.length < 3) {
                response.put("status", "Error: Invalid method parameter format.");
                return response;
            }

            String fullMethod = allParts[0];
            String inputPath = allParts[1];
            String outputPath = allParts[2];

            if (!new File(inputPath).exists()) {
                System.out.println("❌ Input file not found: " + inputPath);
            }
            if (!new File(outputPath).exists()) {
                System.out.println("❌ Output file not found: " + outputPath);
            }

            String[] parts = fullMethod.split("\\.");
            String className = "com.qa.cbcc." + parts[0];
            String method = parts[1];

            XmlSuite suite = new XmlSuite();
            suite.setName(parts[0] + "." + method);

            XmlTest test = new XmlTest(suite);
            test.setName("Run_" + method);
            test.addParameter("json1", inputPath);
            test.addParameter("json2", outputPath);

            XmlClass xmlClass = new XmlClass(className);
            xmlClass.setIncludedMethods(Collections.singletonList(new XmlInclude(method)));
            test.setXmlClasses(Collections.singletonList(xmlClass));

            TestNG testng = new TestNG();
            testng.setUseDefaultListeners(false);
            testng.setXmlSuites(Collections.singletonList(suite));
            testng.run();

            boolean hasFailure = !outputStream.toString().contains("Failures: 0");
            response.put("status", hasFailure ? "Failure" : "Success");

        } catch (Exception e) {
            response.put("status", "Error: " + e.getMessage());
        } finally {
            System.setOut(originalOut);
        }

        // Final raw output (with newlines preserved)
        String rawOutput = outputStream.toString();

        // Attempt to extract the JSON diff block
        String jsonDiff = "";
        Matcher matcher = Pattern.compile("(\\[\\s*\\{.*?\\}\\s*\\])", Pattern.DOTALL).matcher(rawOutput);
        if (matcher.find()) {
            jsonDiff = matcher.group(1);
            try {
                // Try to parse to JSON array for proper formatting
                ObjectMapper mapper = new ObjectMapper();
                Object json = mapper.readValue(jsonDiff, Object.class);
                response.put("jsonDifferences", json);
            } catch (IOException e) {
                response.put("jsonDifferences", jsonDiff); // fallback as string
            }
        }

        response.put("output", rawOutput);
        return response;
    }
}
