package com.qa.cbcc.service;

import com.qa.cbcc.dto.ExampleDTO;
import com.qa.cbcc.dto.ScenarioDTO;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Service
public class FeatureService {

	private static final String FEATURES_DIR = "src/test/resources/features";

	public List<ScenarioDTO> getScenariosByTags(List<String> tagsToMatch) throws IOException {
		List<ScenarioDTO> scenarios = new ArrayList<>();

		Files.walk(Paths.get(FEATURES_DIR)).filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".feature")).forEach(path -> {
					try {
						List<String> lines = Files.readAllLines(path);
						Set<String> featureLevelTags = new LinkedHashSet<>();
						Set<String> tagBuffer = new LinkedHashSet<>();
						String currentFeature = null;

						for (int i = 0; i < lines.size(); i++) {
							String line = lines.get(i).trim();

							if (line.startsWith("@")) {
								List<String> tags = Arrays.asList(line.split("\\s+"));
								tagBuffer.addAll(tags);
								if (currentFeature == null) {
									featureLevelTags.addAll(tags);
								}

							} else if (line.startsWith("Feature:")) {
								currentFeature = line.substring("Feature:".length()).trim();

							} else if (line.startsWith("Scenario") && line.contains(":")) {
								String scenarioName = line.substring(line.indexOf(":") + 1).trim();
								String scenarioType = line.startsWith("Scenario Outline") ? "Scenario Outline"
										: "Scenario";

								// Combine and deduplicate tags
								Set<String> combinedTags = new LinkedHashSet<>(featureLevelTags);
								combinedTags.addAll(tagBuffer);

								// Match all requested tags
								boolean allTagsMatch = tagsToMatch.stream().allMatch(
										tag -> combinedTags.stream().anyMatch(t -> t.equalsIgnoreCase("@" + tag)));

								if (allTagsMatch) {
									ScenarioDTO dto = new ScenarioDTO();
									dto.setFeature(currentFeature);
									dto.setScenario(scenarioName);
									dto.setType(scenarioType);
									dto.setFile(path.getFileName().toString());
									dto.setFilePath(path.toAbsolutePath().toString());
									dto.setTags(new ArrayList<>(combinedTags));

									if (scenarioType.equals("Scenario Outline")) {
										List<ExampleDTO> examples = extractExamples(lines, i);
										dto.setExamples(examples);
									}

									scenarios.add(dto);
								}

								tagBuffer.clear(); // Reset for next scenario
							}
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				});

		return scenarios;
	}

	private List<ExampleDTO> extractExamples(List<String> lines, int startIndex) {
		List<ExampleDTO> examples = new ArrayList<>();
		List<String> headers = null;

		boolean insideExamples = false;

		for (int i = startIndex + 1; i < lines.size(); i++) {
			String line = lines.get(i).trim();

			if (line.isEmpty())
				continue;
			if (line.toLowerCase().startsWith("examples")) {
				insideExamples = true;
				continue;
			}

			if (insideExamples && line.startsWith("|")) {
				List<String> columns = extractColumns(line);

				if (headers == null) {
					headers = columns;
				} else {
					ExampleDTO example = new ExampleDTO();
					example.setIndex(examples.size() + 1);
					example.setLineNumber(i + 1);
					Map<String, String> values = new LinkedHashMap<>();

					for (int j = 0; j < headers.size(); j++) {
						String key = headers.get(j);
						String val = j < columns.size() ? columns.get(j) : "";
						values.put(key, val);
					}

					example.setValues(values);
					examples.add(example);
				}
			} else if (insideExamples && !line.startsWith("|")) {
				break; // End of examples
			}
		}

		return examples;
	}

	private List<String> extractColumns(String line) {
		List<String> result = new ArrayList<>();
		String[] tokens = line.split("\\|");
		for (String token : tokens) {
			String val = token.trim();
			if (!val.isEmpty())
				result.add(val);
		}
		return result;
	}

	public Map<String, Object> runScenario(String featurePath, String scenarioName, String inputPath, String outputPath) {
	    Map<String, Object> result = new HashMap<>();

	    try {
	        // Log input
	        System.out.println("Running scenario:");
	        System.out.println("Feature: " + featurePath);
	        System.out.println("Scenario: " + scenarioName);
	        System.out.println("Input file: " + inputPath);
	        System.out.println("Output file: " + outputPath);

	        // TODO: Actually run the scenario using Cucumber or custom logic

	        // Mock execution result
	        boolean executionSuccessful = true;

	        if (executionSuccessful) {
	            result.put("status", "Success");
	            result.put("output", "Scenario '" + scenarioName + "' from feature '" + featurePath + "' executed successfully.");
	        } else {
	            result.put("status", "Failure");
	            result.put("output", "Scenario failed to execute.");
	        }

	    } catch (Exception e) {
	        result.put("status", "Error");
	        result.put("output", "Exception: " + e.getMessage());
	    }

	    return result;
	}


}
