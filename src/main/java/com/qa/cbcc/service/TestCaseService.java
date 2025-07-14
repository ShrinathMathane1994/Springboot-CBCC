package com.qa.cbcc.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.repository.TestCaseRepository;

@Service
public class TestCaseService {

	@Autowired
	private TestCaseRepository repository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Transactional
	public TestCase saveTestCase(TestCaseDTO dto, MultipartFile inputFile, MultipartFile outputFile) {
		try {
			TestCase testCase = new TestCase();
			testCase.setTcName(dto.getTcName());
			testCase.setDescription(dto.getDescription());

			String json = objectMapper.writeValueAsString(dto.getFeatureScenarios());
			testCase.setFeatureScenarioJson(json);

			TestCase saved = repository.save(testCase);

			Path dir = Paths.get("src/main/resources/testData", String.valueOf(saved.getId()));
			Files.createDirectories(dir);

			Path inputPath = dir.resolve("input.json");
			Path outputPath = dir.resolve("output.json");

			inputFile.transferTo(inputPath);
			outputFile.transferTo(outputPath);

			saved.setInputFile("testData/" + saved.getId() + "/input.json");
			saved.setOutputFile("testData/" + saved.getId() + "/output.json");

			return repository.save(saved);
		} catch (IOException e) {
			throw new RuntimeException("Failed to store test files: " + e.getMessage(), e);
		}
	}

	public void storeTestFiles(Long id, MultipartFile inputFile, MultipartFile outputFile) throws IOException {
		// Construct absolute path to resources/testData/{id}/
		String basePath = System.getProperty("user.dir") + File.separator + "src" + File.separator + "main"
				+ File.separator + "resources" + File.separator + "testData";
		Path dir = Paths.get(basePath, String.valueOf(id));

		// Create directory if not exists
		Files.createDirectories(dir);

		// Resolve file paths
		Path inputPath = dir.resolve("input.json");
		Path outputPath = dir.resolve("output.json");

		// Save files
		inputFile.transferTo(inputPath.toFile());
		outputFile.transferTo(outputPath.toFile());
	}

	public TestCase getTestCaseById(Long id) {
		return repository.findById(id).orElse(null);
	}
}
