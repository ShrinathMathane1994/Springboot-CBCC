package com.qa.cbcc.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.dto.ScenarioDTO;
import com.qa.cbcc.service.FeatureService;

@RestController
@RequestMapping("/api")
public class FeatureController {

    private static final Logger logger = LogManager.getLogger(FeatureController.class);
    private final FeatureService featureService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "status", 404,
                        "message", "No matching scenarios found for tags: " + String.join(", ", tags)
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
            logger.error("IOException while fetching scenarios: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", 500,
                    "message", "Error processing feature files"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error while fetching scenarios: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", 500,
                    "message", "Internal server error"
            ));
        }
    }

    @GetMapping("/sync-features")
    public ResponseEntity<Map<String, Object>> syncFeatures() {
        try {
            featureService.syncGitAndParseFeatures();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "Features synced and parsed successfully");
            response.put("totalScenarios", featureService.getCachedScenarios().size());
            response.put("source", featureService.getFeatureSource()); // ✅ included

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            logger.error("Error during feature sync: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", 500);
            error.put("message", "Failed to sync features: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    @GetMapping("/git-config")
    public ResponseEntity<GitConfigDTO> getGitConfig() {
        return ResponseEntity.ok(featureService.getGitConfig());
    }
    
    @PostMapping("/git-config")
    public ResponseEntity<Map<String, Object>> updateGitConfig(@RequestBody GitConfigDTO configDTO) {
        Map<String, Object> response = featureService.updateGitConfig(configDTO);
        int status = (int) response.getOrDefault("status", 500);
        return ResponseEntity.status(status).body(response);
    }

}
