package com.qa.cbcc.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.cbcc.dto.ScenarioExampleRunDTO;
import com.qa.cbcc.dto.TestCaseRunHistoryDTO;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.service.TestCaseReportService;
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

	@Autowired
	private TestCaseReportService caseReportService;

//    @PostMapping("/run")
//    public List<Map<String, Object>> runTestCasesByIds(
//            @RequestBody java.util.LinkedHashMap<String, List<Integer>> payload) {
//        List<Integer> testCaseIds = payload.get("testCaseIds");
//        logger.info("Received request to run test case IDs: {}", testCaseIds);
//
//        try {
//            List<Long> longIds = testCaseIds.stream().map(Integer::longValue).collect(Collectors.toList());
//
//            List<Map<String, Object>> results = testCaseRunService.runByIds(longIds);
//
//            ObjectMapper mapper = new ObjectMapper();
//            mapper.registerModule(new JavaTimeModule());
//            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
//            // logger.info("Execution completed: {}", mapper.writeValueAsString(results));
//
//            return results;
//
//        } catch (Exception e) {
//            logger.error("Execution failed: {}", e.getMessage(), e);
//            throw new RuntimeException("Execution failed", e);
//        }
//    }

	@PostMapping("/run")
	public ResponseEntity<?> runTestCasesByIds(@RequestBody LinkedHashMap<String, List<Integer>> payload) {
		List<Integer> testCaseIds = payload.get("testCaseIds");
		logger.info("Received request to run test case IDs: {}", testCaseIds);

		try {
			List<Long> longIds = testCaseIds.stream().map(Integer::longValue).collect(Collectors.toList());
			List<Map<String, Object>> results = testCaseRunService.runByIds(longIds);
			return ResponseEntity.ok(results);
		} catch (Exception e) {
			logger.error("Execution failed: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Collections.singletonMap("error", "Execution failed: " + e.getMessage()));
		}
	}

	@GetMapping("/{tcId}/run-history")
	public List<TestCaseRunHistoryDTO> getExecutionHistory(@PathVariable Long tcId) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());

		return historyRepository.findAll().stream().filter(h -> h.getTestCase().getIdTC().equals(tcId))
				.sorted(Comparator.comparing(TestCaseRunHistory::getRunTime).reversed()) // 🔁 Sort by runTime DESC
				.map(h -> {
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
							dto.setXmlParsedDifferencesJson(mapper.readValue(h.getXmlParsedDifferencesJson(),
									new TypeReference<List<Map<String, Object>>>() {
									}));
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

		// if (latest.isEmpty()) return ResponseEntity.notFound().build(); // ❌ Java 11+
		if (!latest.isPresent()) {
			return ResponseEntity.notFound().build();
		}

		TestCaseRunHistory h = latest.get();
		TestCaseRunHistoryDTO dto = new TestCaseRunHistoryDTO();
		dto.setId(h.getId());
		dto.setTestCaseId(h.getTestCase().getIdTC());
		dto.setRunTime(h.getRunTime());
		dto.setRunStatus(h.getRunStatus());
		dto.setExecutedScenarios(h.getExecutedScenarios());
		dto.setUnexecutedScenarios(h.getUnexecutedScenarios());

		List<String> rawLog = h.getRawCucumberLog() != null ? Arrays.asList(h.getRawCucumberLog().split("\\r?\\n"))
				: null;
		// dto.setRawCucumberLog(rawLog);

		if (rawLog != null) {
			dto.setRawCucumberLogGrouped(CucumberLogUtils.groupRawCucumberLog(rawLog));
		}

		dto.setXmlDiffStatus(h.getXmlDiffStatus());
		dto.setInputXmlContent(h.getInputXmlContent());
		dto.setOutputXmlContent(h.getOutputXmlContent());

		try {
			if (h.getOutputLog() != null) {
				dto.setOutputLog(mapper.readValue(h.getOutputLog(), Object.class));
			}
			if (h.getXmlParsedDifferencesJson() != null) {
				List<Map<String, Object>> xmlDiffs = mapper.readValue(h.getXmlParsedDifferencesJson(),
						new TypeReference<List<Map<String, Object>>>() {
						});
				dto.setXmlParsedDifferencesJson(xmlDiffs);
			}

		} catch (Exception e) {
			logger.warn("Failed to parse stored JSON", e);
		}

		return ResponseEntity.ok(dto);
	}

//	@GetMapping("/{tcId}/html-latest")
//	public ResponseEntity<String> getLatestExecutionHtml(@PathVariable Long tcId) throws IOException {
//		ResponseEntity<TestCaseRunHistoryDTO> latestExecution = getLatestExecution(tcId);
//
//		if (!latestExecution.getStatusCode().is2xxSuccessful() || latestExecution.getBody() == null) {
//			return ResponseEntity.notFound().build();
//		}
//
//		String html = caseReportService.generateHtmlReport(latestExecution.getBody());
//
//		HttpHeaders headers = new HttpHeaders();
//		headers.setContentType(MediaType.TEXT_HTML);
//		return new ResponseEntity<>(html, headers, HttpStatus.OK);
//	}

	@GetMapping("/{tcId}/html-latest")
	public ResponseEntity<String> getLatestExecutionHtml(@PathVariable Long tcId,
			@RequestParam(name = "exampleId", required = false) Long exampleId // optional: focus on a specific example
	) throws IOException {

		ResponseEntity<TestCaseRunHistoryDTO> latestExecution = getLatestExecution(tcId);

		if (!latestExecution.getStatusCode().is2xxSuccessful() || latestExecution.getBody() == null) {
			return ResponseEntity.notFound().build();
		}

		TestCaseRunHistoryDTO historyDto = latestExecution.getBody();

		// Determine run/execution id from history DTO — adjust getter if field named
		// differently
		Long runId = historyDto.getId(); // or historyDto.getRunId()
		if (runId == null) {
			// guard — if there is no run id, return history-level report without examples
			String html = caseReportService.generateHtmlReport(historyDto, (ScenarioExampleRunDTO) null,
					Collections.emptyList());
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.TEXT_HTML);
			return new ResponseEntity<>(html, headers, HttpStatus.OK);
		}

		// Fetch example rows for this execution with full XML content
		List<ScenarioExampleRunDTO> exampleRows = testCaseRunService.getExampleRowsForExecution(runId, true);

		// Pick scDTO:
		ScenarioExampleRunDTO scDTO = null;
		if (exampleId != null) {
			// prefer explicit exampleId if provided
			for (ScenarioExampleRunDTO e : exampleRows) {
				if (e.getId() != null && e.getId().equals(exampleId)) {
					scDTO = e;
					break;
				}
			}
		}
		if (scDTO == null && !exampleRows.isEmpty()) {
			// default to the first example row
			scDTO = exampleRows.get(0);
		}

		// Pass history dto, the chosen single-example DTO (may be null), and the full
		// list
		String html = caseReportService.generateHtmlReport(historyDto, scDTO,
				exampleRows == null ? Collections.emptyList() : exampleRows);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_HTML);
		return new ResponseEntity<>(html, headers, HttpStatus.OK);
	}
	
	@GetMapping("/run-history/{historyId}/html")
	public ResponseEntity<String> getHistoryExecutionHtml(
	        @PathVariable Long historyId,
	        @RequestParam(name = "exampleId", required = false) Long exampleId
	) throws IOException {

	    // Load the history entry
	    Optional<TestCaseRunHistory> historyOpt = historyRepository.findById(historyId);
	    if (!historyOpt.isPresent()) {
	        return ResponseEntity.notFound().build();
	    }

	    TestCaseRunHistory history = historyOpt.get();

	    // Build DTO (same style as other endpoints)
	    ObjectMapper mapper = new ObjectMapper();
	    mapper.registerModule(new JavaTimeModule());

	    TestCaseRunHistoryDTO dto = new TestCaseRunHistoryDTO();
	    dto.setId(history.getId());
	    dto.setTestCaseId(history.getTestCase() != null ? history.getTestCase().getIdTC() : null);
	    dto.setRunTime(history.getRunTime());
	    dto.setRunStatus(history.getRunStatus());
	    dto.setExecutedScenarios(history.getExecutedScenarios());
	    dto.setUnexecutedScenarios(history.getUnexecutedScenarios());
	    dto.setXmlDiffStatus(history.getXmlDiffStatus());
	    dto.setInputXmlContent(history.getInputXmlContent());
	    dto.setOutputXmlContent(history.getOutputXmlContent());

	    // raw log grouping
	    if (history.getRawCucumberLog() != null) {
	        List<String> rawLog = Arrays.asList(history.getRawCucumberLog().split("\\r?\\n"));
	        dto.setRawCucumberLogGrouped(CucumberLogUtils.groupRawCucumberLog(rawLog));
	    }

	    try {
	        if (history.getOutputLog() != null) {
	            dto.setOutputLog(mapper.readValue(history.getOutputLog(), Object.class));
	        }
	        if (history.getXmlParsedDifferencesJson() != null) {
	            List<Map<String, Object>> xmlDiffs = mapper.readValue(
	                    history.getXmlParsedDifferencesJson(),
	                    new TypeReference<List<Map<String, Object>>>() {}
	            );
	            dto.setXmlParsedDifferencesJson(xmlDiffs);
	        }
	    } catch (Exception e) {
	        logger.warn("Failed to parse stored JSON for history {}: {}", historyId, e.getMessage());
	    }

	    // Fetch example rows for this execution
	    List<ScenarioExampleRunDTO> exampleRows = Collections.emptyList();
	    try {
	        exampleRows = testCaseRunService.getExampleRowsForExecution(historyId, true);
	    } catch (Exception ex) {
	        logger.warn("Failed to fetch example rows for history {}: {}", historyId, ex.getMessage(), ex);
	    }

	    // Pick example row
	    ScenarioExampleRunDTO scDTO = null;
	    if (exampleId != null && exampleRows != null) {
	        for (ScenarioExampleRunDTO e : exampleRows) {
	            if (e.getId() != null && e.getId().equals(exampleId)) {
	                scDTO = e;
	                break;
	            }
	        }
	    }
	    if (scDTO == null && exampleRows != null && !exampleRows.isEmpty()) {
	        scDTO = exampleRows.get(0);
	    }

	    // Generate HTML
	    String html = caseReportService.generateHtmlReport(
	            dto,
	            scDTO,
	            exampleRows == null ? Collections.emptyList() : exampleRows
	    );

	    HttpHeaders headers = new HttpHeaders();
	    headers.setContentType(MediaType.TEXT_HTML);
	    return new ResponseEntity<>(html, headers, HttpStatus.OK);
	}


}
