package com.qa.cbcc.service;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseExecutionHistory;
import com.qa.cbcc.repository.TestCaseExecutionHistoryRepository;
import com.qa.cbcc.repository.TestCaseRepository;

import io.cucumber.core.cli.Main;

@Service
public class TestCaseRunService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseRunService.class);
	private final TestCaseRepository testCaseRepository;
	private final TestCaseExecutionHistoryRepository historyRepository;
	@Autowired
	private ObjectMapper objectMapper;

	public TestCaseRunService(TestCaseRepository testCaseRepository,
			TestCaseExecutionHistoryRepository historyRepository) {
		this.testCaseRepository = testCaseRepository;
		this.historyRepository = historyRepository;
		this.objectMapper = new ObjectMapper();
		this.objectMapper.registerModule(new JavaTimeModule()); // ✅ Register for LocalDateTime
	}

	public List<LinkedHashMap<String, Object>> runFromDTO(List<TestCaseDTO> testCases) throws Exception {
		List<LinkedHashMap<String, Object>> results = new ArrayList<>();

		for (TestCaseDTO testCase : testCases) {
			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				if (fs.getScenarioBlocks() == null || fs.getScenarioBlocks().isEmpty()) {
					List<String> blocks = new ArrayList<>();
					for (String scenarioName : fs.getScenarios()) {
						String featureFilePath = "src/test/resources/features/" + fs.getFeature();
						try {
							String block = extractScenarioBlock(featureFilePath, scenarioName);
							if (!block.isBlank()) {
								blocks.add(block);
							} else {
								logger.warn("Scenario '{}' not found in feature file '{}'", scenarioName,
										featureFilePath);
							}
						} catch (IOException e) {
							logger.error("Error reading feature file: {}", featureFilePath, e);
						}
					}
					fs.setScenarioBlocks(blocks);
				}
			}

			logger.info("Running test case: {}", testCase.getTcName());
			File featureFile = generateTempFeatureFile(testCase);

			String[] argv = new String[] { "--glue", "com.qa.cbcc.stepdefinitions", featureFile.getAbsolutePath(),
					"--plugin", "pretty" };

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream originalOut = System.out;
			System.setOut(new PrintStream(baos));

			byte exitStatus;
			try {
				exitStatus = Main.run(argv, Thread.currentThread().getContextClassLoader());
			} finally {
				System.out.flush();
				System.setOut(originalOut);
				featureFile.delete(); // ✅ Clean up temp file
			}

			String fullOutput = baos.toString();
//			logger.info("===== Begin Test Output =====\n{}\n===== End Test Output =====", fullOutput);
			
			// Strip known redundant lines (like assertion errors)
			String filteredOutput = removeDuplicateDiff(fullOutput);

			logger.info("===== Begin Test Output =====\n{}\n===== End Test Output =====", filteredOutput);

			Map<String, String> scenarioToFeatureMap = new HashMap<>();
			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				String featureName = fs.getFeature();
				if (fs.getScenarios() != null) {
					for (String scenario : fs.getScenarios()) {
						scenarioToFeatureMap.put(scenario.trim(), featureName);
					}
				}
			}

			List<Map<String, Object>> executedScenarios = new ArrayList<>();

			String[] lines = fullOutput.split("\n");

			String currentScenario = null;
			boolean scenarioFailed = false;
			boolean inScenario = false;
			StringBuilder diffBuffer = new StringBuilder();

			for (String line : lines) {
				line = removeAnsiCodes(line).trim();

				if (line.startsWith("Scenario:")) {
					if (currentScenario != null) {
						executedScenarios
								.add(createScenarioResult(currentScenario, diffBuffer.toString(), !scenarioFailed,
										scenarioToFeatureMap.getOrDefault(currentScenario, "Unknown.feature")));
					}

					currentScenario = line.replace("Scenario:", "").split("#")[0].trim();
					diffBuffer.setLength(0);
					scenarioFailed = false;
					inScenario = true;
				}

				if (inScenario) {
					// Only collect lines that are relevant or meaningful
					if (line.startsWith("Scenario:")) {
					    diffBuffer.append(line).append("\n");
					} else if (scenarioFailed) {
					    // Only collect failure info if scenario failed
					    if (line.startsWith("Expected") || line.contains("comparing") || line.contains("AssertionError")) {
					        diffBuffer.append(line).append("\n");
					    }
					}
				}

				if (line.matches(".*\\(.*failed.*\\).*")) {
					scenarioFailed = true;
				}
			}

			if (currentScenario != null) {
				executedScenarios.add(createScenarioResult(currentScenario, diffBuffer.toString(), !scenarioFailed,
						scenarioToFeatureMap.getOrDefault(currentScenario, "Unknown.feature")));
			}

			boolean anyFailed = executedScenarios.stream().anyMatch(s -> "Failed".equals(s.get("status")));
			boolean allFailed = executedScenarios.stream().allMatch(s -> "Failed".equals(s.get("status")));

			String overallStatus = allFailed ? "Failed" : anyFailed ? "Partially Passed" : "Passed";

			LocalDateTime executedOn = LocalDateTime.now();
			TestCaseExecutionHistory history = new TestCaseExecutionHistory();
			history.setTestCaseId(testCase.getTcId());
			history.setExecutionOn(executedOn);
			history.setStatus(overallStatus);
			history.setOutputLog(fullOutput.length() > 30000 ? fullOutput.substring(0, 30000) + "\n...[truncated]" : fullOutput);
			historyRepository.save(history);

			testCase.setExecutionOn(executedOn);

			// ✅ Update executionOn in DB
			testCaseRepository.findById(testCase.getTcId()).ifPresent(tc -> {
				tc.setExecutionOn(executedOn);
				testCaseRepository.save(tc);
			});

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("testCaseId", testCase.getTcId());
			result.put("testCaseName", testCase.getTcName());
			result.put("executionOn", executedOn); // ✅ Now safely serialized
			result.put("executedScenarios", executedScenarios);
			result.put("tcStatus", overallStatus);

			results.add(result);
		}

		return results;
	}

	private String removeDuplicateDiff(String original) {
	    // Normalize ANSI color codes to simplify parsing (optional)
	    String cleaned = original.replaceAll("\u001B\\[[;\\d]*m", ""); // Remove ANSI escape codes

	    // Marker to detect start of the verbose diff output
	    String marker = "java.lang.AssertionError: Expected XML files to be equal, but got:";
	    int idx = cleaned.indexOf(marker);

	    if (idx != -1) {
	        // Return only the content before the error block
	        return cleaned.substring(0, idx).trim();
	    }

	    return cleaned.trim();
	}


	private Map<String, Object> createScenarioResult(String scenarioName, String differences, boolean passed,
	        String featureFile) {

	    Map<String, Object> scenarioResult = new HashMap<>();
	    scenarioResult.put("scenarioName", scenarioName);
	    scenarioResult.put("featureFile", featureFile);
	    scenarioResult.put("status", passed ? "Passed" : "Failed");

	    if (!passed) {
	     //  scenarioResult.put("rawDifferences", differences.trim());
	        scenarioResult.put("parsedDifferences", extractXmlDifferences(differences));
	    }

	    return scenarioResult;
	}


	private String removeAnsiCodes(String input) {
		return input.replaceAll("\\u001B\\[[;\\d]*m", "");
	}
	
//	private List<Map<String, String>> extractXmlDifferences(String diffOutput) {
//	    Set<String> seenDescriptions = new LinkedHashSet<>();
//	    List<Map<String, String>> parsedDiffs = new ArrayList<>();
//
//	    String[] lines = diffOutput.split("\n");
//
//	    for (String line : lines) {
//	        line = line.trim();
//	        if (line.startsWith("Expected") && line.contains("comparing")) {
//	            // Normalize line to remove variable parts (optional, depending on your tool)
//	            String normalized = line.replaceAll("expected \\[.*?\\] but found \\[.*?\\]", "").trim();
//
//	            if (seenDescriptions.add(normalized)) { // adds only if not already seen
//	                Map<String, String> diff = new HashMap<>();
//
//	                if (line.contains("child nodelist length")) {
//	                    diff.put("type", "Child Count Mismatch");
//	                } else if (line.contains("text value")) {
//	                    diff.put("type", "Text Mismatch");
//	                } else if (line.contains("Expected child")) {
//	                    diff.put("type", "Missing/Extra Node");
//	                } else {
//	                    diff.put("type", "Other");
//	                }
//
//	                diff.put("description", line); // keep original description for UI clarity
//	                parsedDiffs.add(diff);
//	            }
//	        }
//	    }
//
//	    return parsedDiffs;
//	}

	private List<Map<String, Object>> extractXmlDifferences(String diffOutput) {
	    Set<String> seenDescriptions = new LinkedHashSet<>();
	    List<Map<String, Object>> parsedDiffs = new ArrayList<>();
	    String[] lines = diffOutput.split("\n");

	    for (String line : lines) {
	        line = line.trim();
	        if (line.startsWith("Expected") && line.contains("comparing")) {
	            // Remove ANSI, normalize, and de-dupe
	            String normalized = line.replaceAll("expected \\[.*?\\] but found \\[.*?\\]", "").trim();
	            if (!seenDescriptions.add(normalized)) continue;

	            Map<String, Object> diff = new HashMap<>();

	            // Match various known patterns
	            if (line.contains("text value")) {
	                diff.put("differenceType", "Text Mismatch");
	                diff.put("expected", extractBetween(line, "Expected text value '", "' but was '"));
	                diff.put("actual", extractBetween(line, "but was '", "' - comparing"));
	                diff.put("xpath", extractXPath(line));
	                diff.put("node", extractNodeNameFromXPath(diff.get("xpath")));
	            } else if (line.contains("attribute value")) {
	                diff.put("differenceType", "Attribute Mismatch");
	                diff.put("expected", extractBetween(line, "Expected attribute value '", "' but was '"));
	                diff.put("actual", extractBetween(line, "but was '", "' - comparing"));
	                diff.put("attribute", extractAttributeName(line));
	                diff.put("xpath", extractXPath(line));
	                diff.put("node", extractNodeNameFromXPath(diff.get("xpath")));
	            } else if (line.contains("tag name")) {
	                diff.put("differenceType", "Tag Mismatch");
	                diff.put("expected", extractBetween(line, "Expected element tag name '", "' but was '"));
	                diff.put("actual", extractBetween(line, "but was '", "' - comparing"));
	                diff.put("xpath", extractXPath(line));
	            } else if (line.contains("child nodelist length")) {
	                diff.put("differenceType", "Child Count Mismatch");
	                diff.put("description", line);
	                diff.put("xpath", extractXPath(line));
	            } else if (line.contains("Expected child")) {
	                diff.put("differenceType", "Missing/Extra Node");
	                diff.put("description", line);
	                diff.put("xpath", extractXPath(line));
	            } else {
	                diff.put("differenceType", "Other");
	                diff.put("description", line);
	                diff.put("xpath", extractXPath(line));
	            }

	            parsedDiffs.add(diff);
	        }
	    }

	    return parsedDiffs;
	}

	private String extractBetween(String text, String start, String end) {
	    int s = text.indexOf(start);
	    int e = text.indexOf(end, s + start.length());
	    return (s != -1 && e != -1) ? text.substring(s + start.length(), e) : "";
	}

	private String extractXPath(String line) {
	    int idx = line.lastIndexOf(" at ");
	    if (idx != -1) {
	        String xpathPart = line.substring(idx + 4);
	        int space = xpathPart.indexOf(" ");
	        return space == -1 ? xpathPart : xpathPart.substring(0, space);
	    }
	    return "Unknown";
	}

	private String extractNodeNameFromXPath(Object xpath) {
	    if (xpath instanceof String && ((String) xpath).contains("/")) {
	        String[] parts = ((String) xpath).split("/");
	        String last = parts[parts.length - 1];
	        return last.replaceAll("\\[\\d+\\]", ""); // remove [1]
	    }
	    return "Unknown";
	}

	private String extractAttributeName(String line) {
	    int atIndex = line.lastIndexOf("/@");
	    if (atIndex != -1) {
	        return line.substring(atIndex + 2).split(" ")[0].trim().replaceAll("[^\\w-]", "");
	    }
	    return "Unknown";
	}



	private File generateTempFeatureFile(TestCaseDTO testCase) throws IOException {
		StringBuilder content = new StringBuilder();
		content.append("Feature: ").append(testCase.getTcName()).append("\n\n");

		for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
			if (fs.getScenarioBlocks() != null) {
				for (String block : fs.getScenarioBlocks()) {
					content.append(block).append("\n\n");
				}
			} else if (fs.getScenarios() != null) {
				for (String scenario : fs.getScenarios()) {
					content.append("Scenario: ").append(scenario).append("\n\n");
				}
			}
		}

		File tempFile = File.createTempFile("testcase_", ".feature");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
			writer.write(content.toString());
		}

		return tempFile;
	}

	private TestCaseDTO convertToDTO(TestCase testCase) {
		TestCaseDTO dto = new TestCaseDTO();
		dto.setTcId(testCase.getIdTC()); // ✅ FIX: Ensure ID is passed
		dto.setTcName(testCase.getTcName());
		dto.setDescription(testCase.getDescription());

		try {
			List<TestCaseDTO.FeatureScenario> parsed = objectMapper.readValue(testCase.getFeatureScenarioJson(),
					new TypeReference<List<TestCaseDTO.FeatureScenario>>() {
					});
			dto.setFeatureScenarios(parsed);
		} catch (Exception e) {
			throw new RuntimeException("Failed to parse featureScenarioJson", e);
		}

		return dto;
	}

	public String extractScenarioBlock(String featureFilePath, String scenarioName) throws IOException {
		List<String> lines = Files.readAllLines(Paths.get(featureFilePath));
		StringBuilder block = new StringBuilder();
		boolean capture = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.trim().startsWith("Scenario") || line.trim().startsWith("Scenario Outline")) {
				if (line.contains(scenarioName)) {
					capture = true;
				} else if (capture) {
					break;
				}
			}
			if (capture) {
				block.append(line).append("\n");
			}
		}

		return block.toString().trim();
	}

	public List<LinkedHashMap<String, Object>> runByIds(List<Long> testCaseIds) {
		List<TestCaseDTO> testCases = testCaseRepository.findByIdTCIn(testCaseIds).stream().map(this::convertToDTO)
				.collect(Collectors.toList());

		try {
			return runFromDTO(testCases);
		} catch (Exception e) {
			logger.error("Test execution failed", e);
			return Collections.emptyList();
		}
	}
}
