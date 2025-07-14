package com.qa.cbcc.controller;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import com.qa.cbcc.service.FeatureService;
import com.qa.cbcc.service.TestCaseService;

@RestController
@RequestMapping("/api/test-cases")
public class TestCaseController {

	private static final Logger logger = LogManager.getLogger(TestCaseController.class);
	
    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private FeatureService featureService;

    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createTestCase(
            @RequestPart("data") String dataJson,
            @RequestPart("inputFile") MultipartFile inputFile,
            @RequestPart("outputFile") MultipartFile outputFile) {

        logger.info("Received create test case request.");

        try {
            ObjectMapper mapper = new ObjectMapper();
            TestCaseDTO dto = mapper.readValue(dataJson, TestCaseDTO.class);
            logger.info("Parsed DTO: {}", mapper.writeValueAsString(dto));

            TestCase saved = testCaseService.saveTestCase(dto, inputFile, outputFile);

            // Build success response manually
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", saved.getId());
            response.put("tcName", saved.getTcName());
            response.put("description", saved.getDescription());
            response.put("featureScenarioJson", saved.getFeatureScenarioJson());
            response.put("inputFile", saved.getInputFile());
            response.put("outputFile", saved.getOutputFile());

            logger.info("Test case saved successfully with ID: {}", saved.getId());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error saving test case: {}", e.getMessage(), e);

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "Failed");
            error.put("error", e.getClass().getSimpleName());
            error.put("message", e.getMessage());

            return ResponseEntity.badRequest().body(error);
        }
    }


    @PostMapping("/run")
    public ResponseEntity<List<Map<String, Object>>> runMultipleTestCases(@RequestBody Map<String, List<Long>> payload) {
        List<Long> ids = payload.get("testCaseIds");
        logger.info("Running test cases for IDs: {}", ids);

        List<Map<String, Object>> results = new ArrayList<>();

        for (Long id : ids) {
            logger.debug("Processing test case ID: {}", id);

            TestCase testCase = testCaseService.getTestCaseById(id);
            Map<String, Object> result = new LinkedHashMap<>();

            if (testCase == null) {
                logger.warn("Test case not found for ID: {}", id);
                result.put("testCaseId", id);
                result.put("status", "Not Found");
                results.add(result);
                continue;
            }

            try {
                String inputPath = Paths.get("src/main/resources", testCase.getInputFile()).toString();
                String outputPath = Paths.get("src/main/resources", testCase.getOutputFile()).toString();

                ObjectMapper mapper = new ObjectMapper();
                List<Map<String, Object>> scenarios = mapper.readValue(testCase.getFeatureScenarioJson(), List.class);

                for (Map<String, Object> entry : scenarios) {
                    String feature = entry.get("feature").toString();
                    List<String> scenarioList = (List<String>) entry.get("scenarios");

                    for (String scenario : scenarioList) {
                        logger.info("Running scenario '{}' from feature '{}' for test case ID: {}", scenario, feature, id);

                        Map<String, Object> res = featureService.runScenario(
                                "src/test/resources/features/" + feature,
                                scenario,
                                inputPath,
                                outputPath
                        );

                        res.put("testCaseId", id);
                        res.put("testCaseName", testCase.getTcName());
                        res.put("featureFile", feature);
                        res.put("scenarioName", scenario);
                        res.put("inputFile", testCase.getInputFile());
                        res.put("outputFile", testCase.getOutputFile());

                        results.add(res);
                    }
                }

            } catch (Exception e) {
                logger.error("Error running test case ID {}: {}", id, e.getMessage(), e);
                result.put("testCaseId", id);
                result.put("status", "Error");
                result.put("output", "Exception: " + e.getMessage());
                results.add(result);
            }
        }

        return ResponseEntity.ok(results);
    }

}
