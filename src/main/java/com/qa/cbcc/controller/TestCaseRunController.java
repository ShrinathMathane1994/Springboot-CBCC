package com.qa.cbcc.controller;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.cbcc.dto.TestCaseRunHistoryDTO;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.service.TestCaseRunService;
import com.qa.cbcc.utils.CucumberLogUtils;

@RestController
@RequestMapping("/api/test-cases")
public class TestCaseRunController {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseRunController.class);

	@Autowired
	private TestCaseRunService testCaseRunService;

	@Autowired
	private TestCaseRunHistoryRepository historyRepository;

	@PostMapping("/run")
	public List<Map<String, Object>> runTestCasesByIds(
			@RequestBody java.util.LinkedHashMap<String, List<Integer>> payload) {
		List<Integer> testCaseIds = payload.get("testCaseIds");
		logger.info("Received request to run test case IDs: {}", testCaseIds);

		try {
			List<Long> longIds = testCaseIds.stream().map(Integer::longValue).collect(Collectors.toList());

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

//    @GetMapping("/{tcId}/run-history")
//    public List<TestCaseRunHistory> getExecutionHistory(@PathVariable Long tcId) {
//        return historyRepository.findAll().stream()
//                .filter(h -> h.getTestCase().getIdTC().equals(tcId))
//                .collect(Collectors.toList());
//    }

	@GetMapping("/{tcId}/run-history")
	public List<TestCaseRunHistoryDTO> getExecutionHistory(@PathVariable Long tcId) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());

		 return historyRepository.findAll().stream()
			        .filter(h -> h.getTestCase().getIdTC().equals(tcId))
			        .sorted(Comparator.comparing(TestCaseRunHistory::getRunTime).reversed()) // 🔁 Sort by runTime DESC
			        .map(h -> {TestCaseRunHistoryDTO dto = new TestCaseRunHistoryDTO();
			dto.setId(h.getId());
			dto.setTestCaseId(h.getTestCase().getIdTC());
			dto.setRunTime(h.getRunTime());
			dto.setRunStatus(h.getRunStatus());
			dto.setExecutedScenarios(h.getExecutedScenarios());
			dto.setUnexecutedScenarios(h.getUnexecutedScenarios());
			List<String> rawLog = h.getRawCucumberLog() != null ? Arrays.asList(h.getRawCucumberLog().split("\\r?\\n"))
					: null;

//			dto.setRawCucumberLog(rawLog);

			if (rawLog != null) {
				dto.setRawCucumberLogGrouped(CucumberLogUtils.groupRawCucumberLog(rawLog));
			}

			dto.setXmlDiffStatus(h.getXmlDiffStatus());

			// ✅ Parse JSON strings into objects
			try {
				if (h.getOutputLog() != null) {
					dto.setOutputLog(mapper.readValue(h.getOutputLog(), Object.class));
				}
				if (h.getXmlParsedDifferencesJson() != null) {
					dto.setXmlParsedDifferencesJson(mapper.readValue(h.getXmlParsedDifferencesJson(), Object.class));
				}
			} catch (Exception e) {
				logger.warn("Failed to parse stored JSON", e);
			}

			return dto;
		}).collect(Collectors.toList());
	}
	
	@GetMapping("/{tcId}/run-history/latest")
	public ResponseEntity<TestCaseRunHistoryDTO> getLatestExecution(@PathVariable Long tcId) {
	    ObjectMapper mapper = new ObjectMapper();
	    mapper.registerModule(new JavaTimeModule());

	    Optional<TestCaseRunHistory> latest = historyRepository.findAll().stream()
	        .filter(h -> h.getTestCase().getIdTC().equals(tcId))
	        .max(Comparator.comparing(TestCaseRunHistory::getRunTime)); // 🕒 latest one

	    if (latest.isEmpty()) return ResponseEntity.notFound().build();

	    TestCaseRunHistory h = latest.get();
	    TestCaseRunHistoryDTO dto = new TestCaseRunHistoryDTO();
	    dto.setId(h.getId());
	    dto.setTestCaseId(h.getTestCase().getIdTC());
	    dto.setRunTime(h.getRunTime());
	    dto.setRunStatus(h.getRunStatus());
	    dto.setExecutedScenarios(h.getExecutedScenarios());
	    dto.setUnexecutedScenarios(h.getUnexecutedScenarios());

	    List<String> rawLog = h.getRawCucumberLog() != null
	        ? Arrays.asList(h.getRawCucumberLog().split("\\r?\\n"))
	        : null;
	    // dto.setRawCucumberLog(rawLog);

	    if (rawLog != null) {
	        dto.setRawCucumberLogGrouped(CucumberLogUtils.groupRawCucumberLog(rawLog));
	    }

	    dto.setXmlDiffStatus(h.getXmlDiffStatus());

	    try {
	        if (h.getOutputLog() != null) {
	            dto.setOutputLog(mapper.readValue(h.getOutputLog(), Object.class));
	        }
	        if (h.getXmlParsedDifferencesJson() != null) {
	            dto.setXmlParsedDifferencesJson(mapper.readValue(h.getXmlParsedDifferencesJson(), Object.class));
	        }
	    } catch (Exception e) {
	        logger.warn("Failed to parse stored JSON", e);
	    }

	    return ResponseEntity.ok(dto);
	}


}
