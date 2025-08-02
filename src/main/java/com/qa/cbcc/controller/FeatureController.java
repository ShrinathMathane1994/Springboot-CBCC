package com.qa.cbcc.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.ScenarioDTO;
import com.qa.cbcc.service.FeatureService;

@RestController
@RequestMapping("/api")
public class FeatureController {

    private static final Logger logger = LogManager.getLogger(FeatureController.class);
    private final FeatureService featureService;
    private final ObjectMapper objectMapper = new ObjectMapper(); // for JSON logging

    public FeatureController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @GetMapping("/scenarios")
    public ResponseEntity<?> getScenarios(@RequestParam List<String> tags) {
        logger.info("Received request to fetch scenarios for tags: {}", tags);

        try {
            List<ScenarioDTO> scenarios = featureService.getScenariosByTags(tags);
            logger.debug("Scenarios found: {}", scenarios.size());

            if (scenarios.isEmpty()) {
                logger.warn("No scenarios found for tags: {}", tags);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "message", "No matching scenarios found for tags: " + String.join(", ", tags),
                                "status", 404
                        ));
            }

            try {
                String jsonOutput = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(scenarios);
                logger.info("Successfully retrieved {} scenarios:\n{}", scenarios.size(), jsonOutput);
            } catch (JsonProcessingException e) {
                logger.warn("Failed to serialize scenarios for logging.");
            }

            return ResponseEntity.ok(scenarios);

        } catch (IOException e) {
            logger.error("IOException while fetching scenarios for tags {}: {}", tags, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error processing feature files", "status", 500));
        } catch (Exception e) {
            logger.error("Unexpected error while fetching scenarios: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Internal server error", "status", 500));
        }
    }

    @GetMapping("/sync-features")
    public ResponseEntity<?> syncFeaturesFromGit() {
        try {
            featureService.syncGitAndParseFeatures();
            int total = featureService.getScenariosByTags(List.of()).size();
            return ResponseEntity.ok(Map.of(
                    "message", "Features synced and parsed successfully",
                    "totalScenarios", total,
                    "status", 200
            ));
        } catch (IOException e) {
            logger.error("Git sync failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Git sync error: " + e.getMessage(), "status", 500));
        }
    }
}
