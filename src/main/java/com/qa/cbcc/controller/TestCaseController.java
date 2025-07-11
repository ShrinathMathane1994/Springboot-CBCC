package com.qa.cbcc.controller;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.service.TestCaseService;
import com.qa.cbcc.service.TestMethodService;

@RestController
@RequestMapping("/api/test-cases")
public class TestCaseController {

	@Autowired
	private TestCaseService testCaseService;

	@Autowired
	private TestMethodService testMethodService;

	@PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<TestCase> createTestCase(@RequestPart("data") String dataJson,
			@RequestPart("inputFile") MultipartFile inputFile, @RequestPart("outputFile") MultipartFile outputFile) {

		try {
			ObjectMapper objectMapper = new ObjectMapper();
			TestCaseDTO data = objectMapper.readValue(dataJson, TestCaseDTO.class);

			TestCase saved = testCaseService.saveTestCase(data, inputFile, outputFile);
			return ResponseEntity.ok(saved);
		} catch (Exception e) {
			return ResponseEntity.badRequest().build();
		}
	}

	@PostMapping("/run")
	public ResponseEntity<List<Map<String, Object>>> runMultipleTestCases(
	        @RequestBody Map<String, List<Long>> payload) {
	    List<Long> ids = payload.get("testCaseIds");
	    List<Map<String, Object>> results = new ArrayList<>();

	    for (Long id : ids) {
	        TestCase testCase = testCaseService.getTestCaseById(id);
	        if (testCase == null) {
	            Map<String, Object> error = new HashMap<>();
	            error.put("testCaseId", id);
	            error.put("status", "Not Found");
	            results.add(error);
	            continue;
	        }

	        String[] methodArray = testCase.getMethods().split(",");
	        for (String method : methodArray) {
	            Map<String, Object> result = new HashMap<>();
	            try {
	                String basePath = System.getProperty("user.dir") + File.separator + "src"
	                        + File.separator + "main" + File.separator + "resources";
	                String inputPath = Paths.get(basePath, testCase.getInputFile()).toString();
	                String outputPath = Paths.get(basePath, testCase.getOutputFile()).toString();

	                File inputFile = new File(inputPath);
	                File outputFile = new File(outputPath);

	                List<String> missing = new ArrayList<>();
	                if (!inputFile.exists()) {
	                    missing.add("Input file not found: " + inputPath);
	                }
	                if (!outputFile.exists()) {
	                    missing.add("Output file not found: " + outputPath);
	                }

	                if (!missing.isEmpty()) {
	                    result.put("status", "Failure");
	                    result.put("output", String.join(", ", missing));
	                } else {
	                    String methodWithParams = method.trim() + "###" + inputPath + "###" + outputPath;
	                    result = testMethodService.runTestMethod(methodWithParams); // now returns Map<String, Object>
	                }

	            } catch (Exception e) {
	                result.put("status", "Error");
	                result.put("output", "Exception: " + e.getMessage());
	            }

	            result.put("testCaseId", id);
	            result.put("testCaseName", testCase.getTcName());
	            result.put("method", method.trim());
	            result.put("inputFile", testCase.getInputFile());
	            result.put("outputFile", testCase.getOutputFile());
	            results.add(result);
	        }
	    }

	    return ResponseEntity.ok(results);
	}


}
