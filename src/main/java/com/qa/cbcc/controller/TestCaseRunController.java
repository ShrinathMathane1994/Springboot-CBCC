package com.qa.cbcc.controller; 

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.qa.cbcc.dto.TestCaseRunResultDTO;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.service.TestCaseRunService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@RestController
@RequestMapping("/api/test-cases")
public class TestCaseRunController {

    private static final Logger logger = LoggerFactory.getLogger(TestCaseRunController.class);

    @Autowired
    private TestCaseRunService testCaseRunService;

    @Autowired
    private TestCaseRunHistoryRepository historyRepository;

    @PostMapping("/run")
    public List<Map<String, Object>> runTestCasesByIds(@RequestBody java.util.LinkedHashMap<String, List<Integer>> payload) {
        List<Integer> testCaseIds = payload.get("testCaseIds");
        logger.info("Received request to run test case IDs: {}", testCaseIds);

        try {
            List<Long> longIds = testCaseIds.stream()
                    .map(Integer::longValue)
                    .collect(Collectors.toList());

            List<Map<String, Object>> results = testCaseRunService.runByIds(longIds);

            // Optional: JSON logging for debugging
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
//            logger.info("Execution completed: {}", mapper.writeValueAsString(results));

            return results;

        } catch (Exception e) {
            logger.error("Execution failed: {}", e.getMessage(), e);
            throw new RuntimeException("Execution failed", e);
        }
    }

    @GetMapping("/{tcId}/history")
    public List<TestCaseRunHistory> getExecutionHistory(@PathVariable Long tcId) {
        return historyRepository.findAll().stream()
                .filter(h -> h.getTestCase().getIdTC().equals(tcId))
                .collect(Collectors.toList());
    }

}
