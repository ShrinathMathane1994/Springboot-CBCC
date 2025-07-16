package com.qa.cbcc.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.dto.TestCaseResponseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseHistory;
import com.qa.cbcc.repository.TestCaseHistoryRepository;
import com.qa.cbcc.repository.TestCaseRepository;

@Service
public class TestCaseService {

	@Autowired
	private TestCaseRepository repository;

	@Autowired
	private TestCaseHistoryRepository historyRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Transactional
	public TestCase saveTestCase(TestCaseDTO dto, MultipartFile inputFile, MultipartFile outputFile) {
		try {
			TestCase testCase = new TestCase();
			testCase.setTcName(dto.getTcName());
			testCase.setDescription(dto.getDescription());

			String json = objectMapper.writeValueAsString(dto.getFeatureScenarios());
			testCase.setFeatureScenarioJson(json);

			LocalDateTime now = LocalDateTime.now();
			testCase.setCreatedOn(now);
			testCase.setModifiedOn(now);
			testCase.setIsActive(true);

			TestCase saved = repository.save(testCase);

			// ✅ Call your centralized method to store files in the correct path
			String inputFileName = inputFile.getOriginalFilename();
			String outputFileName = outputFile.getOriginalFilename();

			// Save files first
			storeTestFiles(saved.getIdTC(), inputFile, outputFile);

			// Save relative paths
			saved.setInputFile("testData/" + saved.getIdTC() + "/" + inputFileName);
			saved.setOutputFile("testData/" + saved.getIdTC() + "/" + outputFileName);

			TestCase finalSaved = repository.save(saved);

			buildHistory(finalSaved, "CREATE");
			return finalSaved;

		} catch (IOException e) {
			throw new RuntimeException("Failed to store test files: " + e.getMessage(), e);
		}
	}


	public TestCase getTestCaseById(Long id) {
	    return repository.findByIdTCAndIsActiveTrue(id).orElse(null);
	}


	public List<TestCase> getAllTestCases() {
		return repository.findByIsActiveTrue();
	}

	public TestCase save(TestCase testCase) {
		testCase.setModifiedOn(LocalDateTime.now());
		return repository.save(testCase);
	}

	public void updateExecutionTime(Long testCaseId) {
		TestCase tc = getTestCaseById(testCaseId);
		if (tc != null) {
			tc.setExecutionOn(LocalDateTime.now());
			repository.save(tc);
		}
	}
	
	public void storeTestFiles(Long testCaseId, MultipartFile inputFile, MultipartFile outputFile) throws IOException {
	    String baseDir = System.getProperty("user.dir") + File.separator +
	                     "src" + File.separator +
	                     "main" + File.separator +
	                     "resources" + File.separator +
	                     "testData";

	    File dir = new File(baseDir + File.separator + testCaseId);
	    if (!dir.exists()) {
	        dir.mkdirs();
	    }

	    String inputFileName = inputFile.getOriginalFilename();
	    String outputFileName = outputFile.getOriginalFilename();

	    File inputDest = new File(dir, inputFileName);
	    File outputDest = new File(dir, outputFileName);

	    inputFile.transferTo(inputDest);
	    outputFile.transferTo(outputDest);
	}



	@Transactional
	public TestCase updateTestCase(Long id, TestCaseDTO dto, MultipartFile inputFile, MultipartFile outputFile) {
		try {
			TestCase existing = getTestCaseById(id);
			if (existing == null) {
				throw new RuntimeException("Test case with ID " + id + " not found.");
			}

			existing.setTcName(dto.getTcName());
			existing.setDescription(dto.getDescription());
			String json = objectMapper.writeValueAsString(dto.getFeatureScenarios());
			existing.setFeatureScenarioJson(json);
			existing.setModifiedOn(LocalDateTime.now());

			// Update files and set new paths
			String inputFileName = inputFile.getOriginalFilename();
			String outputFileName = outputFile.getOriginalFilename();

			storeTestFiles(id, inputFile, outputFile);

			existing.setInputFile("testData/" + id + "/" + inputFileName);
			existing.setOutputFile("testData/" + id + "/" + outputFileName);

			TestCase updated = repository.save(existing);

			// ✅ Save history after update
			buildHistory(updated, "UPDATE");

			return updated;
		} catch (IOException e) {
			throw new RuntimeException("Failed to update test case: " + e.getMessage(), e);
		}
	}

	@Transactional
	public void deleteTestCase(Long id) {
		TestCase existing = getTestCaseById(id);
		if (existing == null) {
			throw new RuntimeException("Test case with ID " + id + " not found.");
		}

		existing.setIsActive(false);
		existing.setModifiedOn(LocalDateTime.now());
		repository.save(existing);

		// ✅ Save history after soft-delete
		buildHistory(existing, "DELETE");
	}

	// ✅ Updated to save history record
	private TestCaseHistory buildHistory(TestCase testCase, String changeType) {
		TestCaseHistory history = new TestCaseHistory();
		history.setTestCaseId(testCase.getIdTC());
		history.setTcName(testCase.getTcName());
		history.setDescription(testCase.getDescription());
		history.setFeatureScenarioJson(testCase.getFeatureScenarioJson()); // ✅ this is good
		history.setInputFile(testCase.getInputFile());
		history.setOutputFile(testCase.getOutputFile());
		history.setModifiedOn(LocalDateTime.now());
		history.setChangeType(changeType);
		return historyRepository.save(history); // ✅ make sure you are saving it
	}

	public List<TestCaseHistory> getTestCaseHistory(Long testCaseId) {
		return historyRepository.findByTestCaseIdOrderByModifiedOnDesc(testCaseId);
	}

	public List<TestCase> getDeletedTestCases() {
		return repository.findByIsActiveFalse();
	}
	
	public TestCase getTestCaseByIdIncludingInactive(Long idTC) {
	    return repository.findByIdTC(idTC).orElse(null);
	}


	public TestCaseResponseDTO toResponseDTO(TestCase testCase) {
		TestCaseResponseDTO dto = new TestCaseResponseDTO();
		dto.setId(testCase.getIdTC());
		dto.setTcName(testCase.getTcName());
		dto.setDescription(testCase.getDescription());
		dto.setFeatureScenarioJson(testCase.getFeatureScenarioJson());
		dto.setInputFile(testCase.getInputFile());
		dto.setOutputFile(testCase.getOutputFile());
		dto.setCreatedOn(testCase.getCreatedOn());
		dto.setModifiedOn(testCase.getModifiedOn());
		dto.setIsActive(testCase.getIsActive());
		return dto;
	}

}
