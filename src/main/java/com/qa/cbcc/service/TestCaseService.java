package com.qa.cbcc.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.repository.TestCaseRepository;

@Service
public class TestCaseService {

	@Autowired
	private TestCaseRepository repository;

	public TestCase saveTestCase(TestCaseDTO dto, MultipartFile inputFile, MultipartFile outputFile) {
		TestCase testCase = new TestCase();
		testCase.setTcName(dto.getTcName());
		testCase.setDescription(dto.getDescription());
		testCase.setMethods(String.join(",", dto.getMethods()));

		// Save first to get the generated ID
		TestCase saved = repository.save(testCase);

		// Store files
		storeTestFiles(saved.getId(), inputFile, outputFile);

		// Update file paths
		String folder = "testData/" + saved.getId();
		saved.setInputFile(folder + "/input.json");
		saved.setOutputFile(folder + "/output.json");

		return repository.save(saved); // save again with file paths
	}

	public void storeTestFiles(Long id, MultipartFile inputFile, MultipartFile outputFile) {
		Path dir = Paths.get("src/main/resources/testData", String.valueOf(id));
		try {
			Files.createDirectories(dir);
			inputFile.transferTo(dir.resolve("input.json"));
			outputFile.transferTo(dir.resolve("output.json"));
		} catch (IOException e) {
			throw new RuntimeException("Failed to store test files for TC ID " + id, e);
		}
	}

	public TestCase getTestCaseById(Long id) {
	    return repository.findById(id).orElse(null);
	}


}
