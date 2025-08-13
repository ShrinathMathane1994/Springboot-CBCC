package com.qa.cbcc.controller;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.dto.TestCaseHistoryDTO;
import com.qa.cbcc.dto.TestCaseResponseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.service.TestCaseService;

@RestController
@RequestMapping("/api/test-cases")
public class TestCaseController {

	private static final Logger logger = LogManager.getLogger(TestCaseController.class);

	@Autowired
	private TestCaseService testCaseService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@PostMapping(value = "/create", consumes = { "multipart/form-data" })
	public ResponseEntity<?> createTestCase(@RequestPart("data") String dtoJson,
			@RequestPart("inputFile") MultipartFile inputFile, @RequestPart("outputFile") MultipartFile outputFile) {

		logger.info("Received request to create a new test case.");

		try {
			TestCaseDTO dto = objectMapper.readValue(dtoJson, TestCaseDTO.class);
			logger.info("Parsed DTO: {}", objectMapper.writeValueAsString(dto));

			TestCase created = testCaseService.saveTestCase(dto, inputFile, outputFile);
			logger.info("Test case created with ID: {}", created.getIdTC());

			return ResponseEntity.ok(testCaseService.toResponseDTO(created));
		} catch (Exception e) {
			logger.error("Failed to create test case: {}", e.getMessage(), e);
			return ResponseEntity.badRequest()
					.body(Map.of("error", "Failed to create test case", "details", e.getMessage()));
		}
	}

	@PutMapping(value = "/{id}", consumes = { "multipart/form-data" })
	public ResponseEntity<?> updateTestCase(@PathVariable Long id, @RequestPart("data") String dtoJson,
			@RequestPart("inputFile") MultipartFile inputFile, @RequestPart("outputFile") MultipartFile outputFile) {

		logger.info("Received request to update test case ID: {}", id);

		try {
			TestCaseDTO dto = objectMapper.readValue(dtoJson, TestCaseDTO.class);
			logger.debug("Parsed DTO: {}", objectMapper.writeValueAsString(dto));

			TestCase updated = testCaseService.updateTestCase(id, dto, inputFile, outputFile);
			logger.info("Updated test case ID: {}", id);

			TestCaseResponseDTO response = testCaseService.toResponseDTO(updated);
			ObjectMapper mapper = new ObjectMapper();
			mapper.registerModule(new JavaTimeModule());
			mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
			logger.info("Updated test case details: {}", mapper.writeValueAsString(response));
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			logger.error("Update failed for test case ID {}: {}", id, e.getMessage(), e);
			return ResponseEntity.badRequest()
					.body(Map.of("error", "Failed to update test case", "details", e.getMessage()));
		}
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> getTestCaseById(@PathVariable Long id) {
		logger.info("Fetching test case ID: {}", id);
		TestCase testCase = testCaseService.getTestCaseById(id);
		if (testCase == null) {
			logger.warn("Test case ID {} not found.", id);
			return ResponseEntity.status(404).body(Map.of("message", "Test case not found."));
		}
		return ResponseEntity.ok(testCaseService.toResponseDTO(testCase));
	}

	// ✅ UPDATED: filter by country, region, pod
	@GetMapping
	public ResponseEntity<?> getAllTestCases(@RequestParam(required = false) String country,
			@RequestParam(required = false) String region, @RequestParam(required = false) String pod) {

		logger.info("Fetching test cases with filters -> country: {}, region: {}, pod: {}", country, region, pod);
		List<TestCaseResponseDTO> result = testCaseService.getFilteredTestCases(country, region, pod);

		// Sort in ascending order by id
		result.sort(Comparator.comparing(TestCaseResponseDTO::getId));

		if (result.isEmpty()) {
			logger.warn("No test cases found with given filters.");
			return ResponseEntity.ok(Map.of("message", "No test cases found."));
		}

		return ResponseEntity.ok(result);
	}

	@GetMapping("/deleted")
	public ResponseEntity<List<TestCaseResponseDTO>> getDeletedTestCases() {
		logger.info("Fetching deleted test cases.");

		List<TestCaseResponseDTO> deletedDtos = testCaseService.getDeletedTestCases().stream()
				.map(testCaseService::toResponseDTO).sorted(Comparator.comparing(TestCaseResponseDTO::getId))// ascending
				.collect(Collectors.toList());

		return ResponseEntity.ok(deletedDtos);
	}

	@DeleteMapping("/{id}/delete")
	public ResponseEntity<?> deleteTestCase(@PathVariable Long id) {
		logger.info("Deleting test case ID: {}", id);
		try {
			testCaseService.deleteTestCase(id);
			TestCase deleted = testCaseService.getTestCaseByIdIncludingInactive(id);

			if (deleted == null) {
				logger.warn("Test case ID {} not found after delete.", id);
				return ResponseEntity.notFound().build();
			}

			TestCaseResponseDTO responseDTO = testCaseService.toResponseDTO(deleted);

			ObjectMapper mapper = new ObjectMapper();
			mapper.registerModule(new JavaTimeModule());
			mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
			logger.info("Deleted test case details: {}", mapper.writeValueAsString(responseDTO));

			return ResponseEntity.ok(responseDTO);
		} catch (Exception e) {
			logger.error("Failed to delete test case ID {}: {}", id, e.getMessage(), e);
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		}
	}

	// Old Method/
//	@GetMapping("/{id}/history")
//	public ResponseEntity<?> getTestCaseHistory(@PathVariable Long id) {
//		logger.info("Fetching history for test case ID: {}", id);
//		List<TestCaseHistory> historyList = testCaseService.getTestCaseHistory(id);
//
//		try {
//			ObjectMapper mapper = new ObjectMapper();
//			mapper.registerModule(new JavaTimeModule());
//			mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
//			logger.info("History for test case ID {}: {}", id, mapper.writeValueAsString(historyList));
//		} catch (Exception e) {
//			logger.error("Failed to serialize history for logging: {}", e.getMessage(), e);
//		}
//		return ResponseEntity.ok(historyList);
//	}

	@GetMapping("/{id}/history")
	public ResponseEntity<?> getTestCaseHistory(@PathVariable Long id) {
		logger.info("Fetching history for test case ID: {}", id);

		List<TestCaseHistoryDTO> dtoList = testCaseService.getTestCaseHistoryDTOs(id);

		// Sort by id ascending
		dtoList.sort(Comparator.comparing(TestCaseHistoryDTO::getId));

		try {
			ObjectMapper mapper = new ObjectMapper();
			mapper.registerModule(new JavaTimeModule());
			mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
			logger.info("History DTO for test case ID {}: {}", id, mapper.writeValueAsString(dtoList));
		} catch (Exception e) {
			logger.error("Failed to serialize history DTO for logging: {}", e.getMessage(), e);
		}

		return ResponseEntity.ok(dtoList);
	}

	@PutMapping("/{id}/executed")
	public ResponseEntity<?> updateExecutionTimestamp(@PathVariable Long id) {
		logger.info("Marking test case ID {} as executed.", id);
		testCaseService.updateExecutionTime(id);
		TestCase updated = testCaseService.getTestCaseById(id);
		return ResponseEntity.ok(testCaseService.toResponseDTO(updated));
	}

	@GetMapping("/{id}/download")
	public ResponseEntity<?> downloadFile(@PathVariable Long id, @RequestParam String fileType // "input" or "output"
	) {
		try {
			TestCase testCase = testCaseService.getTestCaseByIdIncludingInactive(id);
			if (testCase == null) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Test case not found"));
			}

			String relativePath = fileType.equalsIgnoreCase("input") ? testCase.getInputFile()
					: testCase.getOutputFile();

			if (relativePath == null) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST)
						.body(Map.of("error", fileType + " file not found for this test case"));
			}

			String baseDir = System.getProperty("user.dir") + File.separator + "src" + File.separator + "main"
					+ File.separator + "resources" + File.separator;

			File file = new File(baseDir + relativePath);
			if (!file.exists()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(Map.of("error", "File does not exist on server"));
			}

			Resource resource = new FileSystemResource(file);
			String contentDisposition = "attachment; filename=\"" + file.getName() + "\"";

			return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
					.contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);

		} catch (Exception e) {
			logger.error("Failed to download {} file for test case {}: {}", fileType, id, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", "Download failed", "details", e.getMessage()));
		}
	}
}
