package com.qa.cbcc.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.dto.ScenarioExampleRunDTO;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.ScenarioExampleRun;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.ScenarioExampleRunRepository;
import com.qa.cbcc.repository.TestCaseRepository;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.utils.StepDefCompiler;
import com.qa.cbcc.utils.TestContext;
import com.qa.cbcc.utils.XmlComparator;

import io.cucumber.core.cli.Main;

@Service
public class TestCaseRunService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseRunService.class);

	@Autowired
	private FeatureService featureService;

	@Value("${cleanup.temp.feature:true}")
	private boolean cleanupTempFeature;

	private final TestCaseRepository testCaseRepository;
	private final TestCaseRunHistoryRepository historyRepository;
	private final ObjectMapper objectMapper;
	private final ObjectWriter prettyWriter;
	@Autowired
	private ScenarioExampleRunRepository scenarioExampleRunRepository;

	// now you can save rows:
	public void saveScenarioExampleRun(ScenarioExampleRun row) {
		scenarioExampleRunRepository.save(row);
	}

	// or fetch by execution:
	public List<ScenarioExampleRun> getDetailsForExecution(Long executionId) {
		return scenarioExampleRunRepository.findByExecutionIdOrderByIdAsc(executionId);
	}

	public TestCaseRunService(TestCaseRepository testCaseRepository, TestCaseRunHistoryRepository historyRepository) {
		this.testCaseRepository = testCaseRepository;
		this.historyRepository = historyRepository;
		this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
		this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
	}

	private Optional<Path> findFeatureFileRecursive(Path rootDir, String featureFileName) {
		try (Stream<Path> paths = Files.walk(rootDir)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equalsIgnoreCase(featureFileName)).findFirst();
		} catch (IOException e) {
			logger.error("Error walking feature directory", e);
			return Optional.empty();
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> safeList(Object obj) {
		if (obj instanceof List<?>) {
			return ((List<?>) obj).stream().map(String::valueOf).collect(Collectors.toList());
		}
		return new ArrayList<>();
	}

	public static void loadSystemPropertiesFromConfig(String configFilePath) {
		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream(configFilePath)) {
			props.load(fis);
			for (String key : props.stringPropertyNames()) {
				System.setProperty(key, props.getProperty(key));
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load config properties from " + configFilePath, e);
		}
	}

	/**
	 * Derive a per-example XML diff status for a scenario row. Possible returned
	 * values: "Matched", "Mismatched", "No Content", "Unexecuted", "Error", "N/A"
	 */
	/**
	 * Returns one of: "Matched", "Mismatched", "No Content", "Unexecuted", "Error",
	 * "N/A"
	 */
	private String derivePerExampleXmlStatus(String scenarioName, String inputXml, String outputXml,
			String differencesJson, Map<String, Map<String, Object>> xmlDetailByScenario,
			List<Map<String, Object>> skippedScenarios) {
		try {
			// 1) If the scenario is in skippedScenarios -> Unexecuted
			if (skippedScenarios != null && scenarioName != null) {
				boolean isSkipped = skippedScenarios.stream()
						.anyMatch(m -> scenarioName.equals(String.valueOf(m.get("scenarioName"))));
				if (isSkipped)
					return "Unexecuted";
			}

			// 2) If xmlComparisonDetails had an explicit detail for this scenario, prefer
			// it
			if (xmlDetailByScenario != null && scenarioName != null && xmlDetailByScenario.containsKey(scenarioName)) {
				Map<String, Object> detail = xmlDetailByScenario.get(scenarioName);
				Object message = detail.get("message");
				if (message != null) {
					String msg = String.valueOf(message).toLowerCase();
					if (msg.contains("equal") || msg.contains("identical"))
						return "Matched";
					if (msg.contains("missing") || msg.contains("empty"))
						return "No Content";
					return "Mismatched";
				}
				Object diffCountObj = detail.get("diffCount");
				if (diffCountObj != null) {
					try {
						int dc = Integer.parseInt(String.valueOf(diffCountObj));
						return dc > 0 ? "Mismatched" : "Matched";
					} catch (Exception ignore) {
					}
				}
				Object diffs = detail.get("differences");
				if (diffs instanceof List) {
					return ((List<?>) diffs).size() > 0 ? "Mismatched" : "Matched";
				}
			}

			// 3) If differencesJson exists, parse it defensively
			if (differencesJson != null && !differencesJson.trim().isEmpty()) {
				try {
					Object parsed = objectMapper.readValue(differencesJson, Object.class);
					if (parsed instanceof List) {
						return ((List<?>) parsed).size() > 0 ? "Mismatched" : "Matched";
					} else if (parsed instanceof Map) {
						return "Mismatched";
					} else {
						return "Mismatched";
					}
				} catch (Exception e) {
					// ignore parse error and continue
				}
			}

			// 4) If both input and output xml are empty -> No Content
			boolean emptyIn = inputXml == null || inputXml.trim().isEmpty();
			boolean emptyOut = outputXml == null || outputXml.trim().isEmpty();
			if (emptyIn && emptyOut)
				return "No Content";

			// Default assumption: Matched
			return "Matched";
		} catch (Exception ex) {
			logger.warn("Failed to derive per-example xml status for '{}': {}", scenarioName, ex.getMessage());
			return "Error";
		}
	}

	// import statements assumed present
	// e.g. import java.time.LocalDateTime; import java.util.*;

	private void persistPerExampleRows(TestCase testCaseEntity, TestCaseRunHistory history,
			List<Map<String, Object>> trulyExecutedScenarios, List<Map<String, Object>> skippedScenarios,
			List<Map<String, Object>> xmlComparisonDetails) {
		if (history == null)
			return;

		try {
			List<ScenarioExampleRun> rowsToSave = new ArrayList<>();

			// Quick lookup xml details by scenarioName (if available)
			Map<String, Map<String, Object>> xmlDetailByScenario = new HashMap<>();
			if (xmlComparisonDetails != null) {
				for (Map<String, Object> d : xmlComparisonDetails) {
					Object name = d.get("scenarioName");
					if (name != null) {
						xmlDetailByScenario.put(String.valueOf(name).trim(), d);
					}
				}
			}

			// merge executed + skipped into one iterable
			List<Map<String, Object>> allScenarios = new ArrayList<>();
			if (trulyExecutedScenarios != null)
				allScenarios.addAll(trulyExecutedScenarios);
			if (skippedScenarios != null)
				allScenarios.addAll(skippedScenarios);

			for (Map<String, Object> scenario : allScenarios) {
				try {
					String scenarioName = safeStringFromMap(scenario, "scenarioName");
					String scenarioType = safeStringFromMap(scenario, "scenarioType");
					String featureFileName = safeStringFromMap(scenario, "featureFileName");
					String featureFilePath = safeStringFromMap(scenario, "featureFilePath");
					String status = safeStringFromMap(scenario, "status");

					Integer exampleIndex = null;
					if (scenario.containsKey("exampleIndex") && scenario.get("exampleIndex") != null) {
						Object idxObj = scenario.get("exampleIndex");
						try {
							if (idxObj instanceof Number) {
								exampleIndex = ((Number) idxObj).intValue();
							} else {
								exampleIndex = Integer.valueOf(String.valueOf(idxObj));
							}
						} catch (Exception ignore) {
						}
					}

					String exampleHeaderJson = toJsonOrString(scenario.get("exampleHeader"));
					String exampleValuesJson = toJsonOrString(scenario.get("exampleValues"));

					// Input/output xml: try few places
					String inputXml = tryExtractXml(scenario, xmlDetailByScenario, "inputXml");
					String outputXml = tryExtractXml(scenario, xmlDetailByScenario, "outputXml");

					// differences / diff summary / errors
					String differencesJson = toJsonOrString(scenario.get("parsedDifferences"));
					if ((differencesJson == null || differencesJson.isEmpty()) && scenarioName != null
							&& xmlDetailByScenario.containsKey(scenarioName)) {
						differencesJson = toJsonOrString(xmlDetailByScenario.get(scenarioName).get("differences"));
					}

					String errorsJson = toJsonOrString(scenario.get("errors"));

					// Build entity row
					ScenarioExampleRun row = new ScenarioExampleRun();
					row.setExecution(history);
					row.setTestCase(testCaseEntity);
					row.setFeatureFileName(featureFileName);
					row.setFeatureFilePath(featureFilePath);
					row.setScenarioName(scenarioName);
					row.setScenarioType(scenarioType);
					row.setExampleHeaderJson(exampleHeaderJson);
					row.setExampleValuesJson(exampleValuesJson);
					row.setStatus(status);

					if (scenario.get("durationMs") != null) {
						try {
							Object d = scenario.get("durationMs");
							if (d instanceof Number) {
								row.setDurationMs(((Number) d).longValue());
							} else {
								row.setDurationMs(Long.valueOf(String.valueOf(d)));
							}
						} catch (Exception ignore) {
						}
					}

					row.setInputXml(inputXml);
					row.setOutputXml(outputXml);
					row.setDifferencesJson(differencesJson);
					row.setErrorsJson(errorsJson);
					row.setCreatedAt(LocalDateTime.now());

//					// --- NEW: derive per-example xmlDiffStatus and set it ---
//					String xmlStatus = derivePerExampleXmlStatus(scenarioName, inputXml, outputXml, differencesJson,
//							xmlDetailByScenario, skippedScenarios);
//					row.setXmlDiffStatus(xmlStatus);

					// prefer explicit xmlDiffStatus from xmlDetailByScenario if present
					String xmlStatusFromDetail = null;
					if (scenarioName != null && xmlDetailByScenario.containsKey(scenarioName)) {
					    Object s = xmlDetailByScenario.get(scenarioName).get("xmlDiffStatus");
					    xmlStatusFromDetail = s == null ? null : String.valueOf(s);
					}
					String xmlStatus = xmlStatusFromDetail != null ? xmlStatusFromDetail
					        : derivePerExampleXmlStatus(scenarioName, inputXml, outputXml, differencesJson, xmlDetailByScenario, skippedScenarios);
					row.setXmlDiffStatus(xmlStatus);

					rowsToSave.add(row);
				} catch (Exception rowEx) {
					logger.error("Failed to persist one scenario/example row (will continue): {}", rowEx.getMessage(),
							rowEx);
				}
			}

			if (!rowsToSave.isEmpty()) {
				scenarioExampleRunRepository.saveAll(rowsToSave);
			}
		} catch (Exception ex) {
			logger.error("Failed to persist per-example rows: {}", ex.getMessage(), ex);
		}
	}

	/* Helpers (place these in same class) */

	private String safeStringFromMap(Map<String, Object> m, String key) {
		if (m == null || !m.containsKey(key) || m.get(key) == null)
			return null;
		return String.valueOf(m.get(key));
	}

	private String toJsonOrString(Object obj) {
		if (obj == null)
			return null;
		if (obj instanceof String)
			return (String) obj;
		// If it's a char[] or byte[] or Clob, handle explicitly
		if (obj instanceof char[])
			return new String((char[]) obj);
		if (obj instanceof byte[])
			return new String((byte[]) obj);
		// javax.sql.Clob or java.sql.Clob -> try to read
		try {
			if (obj instanceof java.sql.Clob) {
				java.sql.Clob cl = (java.sql.Clob) obj;
				long len = cl.length();
				return cl.getSubString(1, (int) Math.min(len, Integer.MAX_VALUE));
			}
		} catch (Throwable t) {
			// ignore reading clob if it fails
			logger.debug("toJsonOrString: failed to read Clob: {}", t.getMessage());
		}
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (Exception e) {
			return String.valueOf(obj);
		}
	}

	private String objectToStringNoCast(Object obj) {
		// similar to above but guarantees not to cast String->char[]
		if (obj == null)
			return null;
		if (obj instanceof String)
			return (String) obj;
		if (obj instanceof char[])
			return new String((char[]) obj);
		if (obj instanceof byte[])
			return new String((byte[]) obj);
		try {
			if (obj instanceof java.sql.Clob) {
				java.sql.Clob cl = (java.sql.Clob) obj;
				long len = cl.length();
				return cl.getSubString(1, (int) Math.min(len, Integer.MAX_VALUE));
			}
		} catch (Throwable t) {
			logger.debug("objectToStringNoCast: failed to read Clob: {}", t.getMessage());
		}
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (Exception e) {
			return String.valueOf(obj);
		}
	}

	private String tryExtractXml(Map<String, Object> scenario, Map<String, Map<String, Object>> xmlDetailByScenario,
			String key) {
		// first check direct key
		if (scenario != null && scenario.containsKey(key) && scenario.get(key) != null) {
			Object o = scenario.get(key);
			// log type to help debugging if it's something weird
			logger.debug("tryExtractXml: scenario '{}' key '{}' runtime class = {}", scenario.get("scenarioName"), key,
					o == null ? "null" : o.getClass().getName());
			return objectToStringNoCast(o);
		}
		// then check xml detail mapping
		if (scenario != null && scenario.get("scenarioName") != null) {
			Map<String, Object> xmlDet = xmlDetailByScenario.get(String.valueOf(scenario.get("scenarioName")));
			if (xmlDet != null && xmlDet.get(key) != null) {
				Object o = xmlDet.get(key);
				logger.debug("tryExtractXml: xmlDetail '{}' key '{}' runtime class = {}", scenario.get("scenarioName"),
						key, o == null ? "null" : o.getClass().getName());
				return objectToStringNoCast(o);
			}
		}
		// fallback to TestContext
		Object fromCtx = TestContext.get(key.equals("inputXml") ? "inputXmlContent" : "outputXmlContent");
		if (fromCtx != null) {
			logger.debug("tryExtractXml: from TestContext key '{}' runtime class = {}", key,
					fromCtx.getClass().getName());
			return objectToStringNoCast(fromCtx);
		}
		return null;
	}

	private ScenarioExampleRunDTO toDto(ScenarioExampleRun entity, boolean includeXml) {
		ScenarioExampleRunDTO dto = new ScenarioExampleRunDTO();
		dto.setId(entity.getId());
		dto.setFeatureFileName(entity.getFeatureFileName());
		dto.setFeatureFilePath(entity.getFeatureFilePath());
		dto.setScenarioName(entity.getScenarioName());
		dto.setScenarioType(entity.getScenarioType());
		dto.setStatus(entity.getStatus());
		dto.setDurationMs(entity.getDurationMs());

		// parse exampleHeaderJson (List<String>)
		List<String> headerList = null;
		try {
			String headerJson = toStringSafe(entity.getExampleHeaderJson());
			if (headerJson != null && !headerJson.trim().isEmpty()) {
				headerList = objectMapper.readValue(headerJson, new TypeReference<List<String>>() {
				});
				dto.setExampleHeader(headerList);
			}
		} catch (Exception ex) {
			logger.warn("Failed to parse exampleHeaderJson for row {}: {}", entity.getId(), ex.getMessage());
		}

		// parse exampleValuesJson: SUPPORT both object and array shapes
		try {
			String valuesJson = toStringSafe(entity.getExampleValuesJson());
			if (valuesJson != null && !valuesJson.trim().isEmpty()) {
				// parse as generic Object first
				Object parsed = objectMapper.readValue(valuesJson, Object.class);

				if (parsed instanceof Map) {
					// already the ideal shape
					// noinspection unchecked
					dto.setExampleValues((Map<String, Object>) parsed);
				} else if (parsed instanceof List) {
					List<?> rawList = (List<?>) parsed;
					Map<String, Object> mapped = new LinkedHashMap<>();

					// If headerList is available and sizes match, zip header->value
					if (headerList != null && headerList.size() == rawList.size()) {
						for (int i = 0; i < headerList.size(); i++) {
							mapped.put(headerList.get(i), rawList.get(i));
						}
					} else {
						// fallback: numeric keys so UI can still show values
						for (int i = 0; i < rawList.size(); i++) {
							mapped.put(String.valueOf(i), rawList.get(i));
						}
					}
					dto.setExampleValues(mapped);
				} else {
					// primitive (string/number) — put under single key "value"
					Map<String, Object> mapped = new LinkedHashMap<>();
					mapped.put("value", parsed);
					dto.setExampleValues(mapped);
				}
			}
		} catch (Exception ex) {
			logger.warn("Failed to parse exampleValuesJson for row {}: {}", entity.getId(), ex.getMessage());
		}

		// ---- differences (attempt to produce List<Map<String,Object>>) ----
		try {
			String diffsJson = toStringSafe(entity.getDifferencesJson());
			if (diffsJson != null && !diffsJson.trim().isEmpty()) {
				Object parsed = objectMapper.readValue(diffsJson, Object.class);

				if (parsed instanceof List) {
					List<?> rawList = (List<?>) parsed;
					List<Map<String, Object>> mapped = new ArrayList<>();
					for (Object o : rawList) {
						if (o instanceof Map) {
							// noinspection unchecked
							mapped.add((Map<String, Object>) o);
						} else {
							Map<String, Object> m = new LinkedHashMap<>();
							m.put("value", o);
							mapped.add(m);
						}
					}
					dto.setDifferences(mapped);
				} else if (parsed instanceof Map) {
					// noinspection unchecked
					dto.setDifferences(Collections.singletonList((Map<String, Object>) parsed));
				} else {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("value", parsed);
					dto.setDifferences(Collections.singletonList(m));
				}
			}
		} catch (Exception ex) {
			logger.warn("Failed to parse differencesJson for row {}: {}", entity.getId(), ex.getMessage());
		}

		// ---- errors (attempt to produce List<String>) ----
		try {
			String errorsJson = toStringSafe(entity.getErrorsJson());
			if (errorsJson != null && !errorsJson.trim().isEmpty()) {
				Object parsed = objectMapper.readValue(errorsJson, Object.class);

				if (parsed instanceof List) {
					List<?> rawList = (List<?>) parsed;
					List<String> out = new ArrayList<>();
					for (Object o : rawList) {
						out.add(o == null ? null : String.valueOf(o));
					}
					dto.setErrors(out);
				} else if (parsed instanceof Map) {
					dto.setErrors(Collections.singletonList(objectMapper.writeValueAsString(parsed)));
				} else {
					dto.setErrors(Collections.singletonList(String.valueOf(parsed)));
				}
			}
		} catch (Exception ex) {
			logger.warn("Failed to parse errorsJson for row {}: {}", entity.getId(), ex.getMessage());
		}
		dto.setXmlDiffStatus(entity.getXmlDiffStatus());

		if (includeXml) {
			dto.setInputXml(toStringSafe(entity.getInputXml()));
			dto.setOutputXml(toStringSafe(entity.getOutputXml()));
		}

		return dto;
	}

	/**
	 * Try to safely convert multiple possible JSON-storage types into a String.
	 * Handles: null, String, char[], StringBuilder, StringBuffer, Clob, byte[].
	 */
	private String toStringSafe(Object obj) {
		if (obj == null)
			return null;
		try {
			if (obj instanceof String) {
				return (String) obj;
			}
			if (obj instanceof char[]) {
				return new String((char[]) obj);
			}
			if (obj instanceof StringBuilder) {
				return obj.toString();
			}
			if (obj instanceof StringBuffer) {
				return obj.toString();
			}
			if (obj instanceof byte[]) {
				return new String((byte[]) obj, "UTF-8");
			}
			// handle java.sql.Clob
			if (obj instanceof java.sql.Clob) {
				java.sql.Clob clob = (java.sql.Clob) obj;
				long len = clob.length();
				// read in chunks if large
				return clob.getSubString(1, (int) len);
			}
			// If it's any other object, fallback to object.toString()
			return obj.toString();
		} catch (Exception e) {
			logger.warn("toStringSafe failed for object type {}: {}", obj.getClass().getName(), e.getMessage());
			try {
				return String.valueOf(obj);
			} catch (Exception ignore) {
				return null;
			}
		}
	}

	/**
	 * Return DTOs for a given execution id
	 */
	public List<ScenarioExampleRunDTO> getExampleRowsForExecution(Long executionId, boolean includeXml) {
		List<ScenarioExampleRun> rows = scenarioExampleRunRepository.findByExecutionIdOrderByIdAsc(executionId);
		return rows.stream().map(r -> toDto(r, includeXml)).collect(Collectors.toList());
	}

	/**
	 * Return DTOs for a given test case id
	 */
	public List<ScenarioExampleRunDTO> getExampleRowsForTestCase(Long testCaseId, boolean includeXml) {
		List<ScenarioExampleRun> rows = scenarioExampleRunRepository.findByTestCase_IdTCOrderByIdDesc(testCaseId);
		return rows.stream().map(r -> toDto(r, includeXml)).collect(Collectors.toList());
	}

	public List<Map<String, Object>> runFromDTO(List<TestCaseDTO> testCases) {
		List<Map<String, Object>> results = new ArrayList<>();

		for (TestCaseDTO testCase : testCases) {
			LocalDateTime executedOn = LocalDateTime.now();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("testCaseId", testCase.getTcId());
			result.put("testCaseName", testCase.getTcName());
			result.put("runOn", executedOn);

			Long runId = null; // will hold persisted history id (either from runSingleTestCase or fallback)

			try {
				// runSingleTestCase now persists header + detail rows and should return a map
				// containing runId
				Map<String, Object> executionResult = runSingleTestCase(testCase);
				if (executionResult != null) {
					result.putAll(executionResult);
					if (executionResult.containsKey("runId")) {
						try {
							runId = executionResult.get("runId") == null ? null
									: Long.valueOf(String.valueOf(executionResult.get("runId")));
						} catch (Exception ignore) {
							runId = null;
						}
					}
				}
			} catch (Exception e) {
				// Execution failure for this test case — build error result but continue the
				// loop
				logger.error("Execution failed for TestCase {}: {}", testCase.getTcId(), e.getMessage(), e);

				result.put("tcStatus", "Execution Error");
				result.put("xmlComparisonStatus", "Error");
				result.put("xmlComparisonDetails", Collections.emptyList());

				Map<String, Object> runSummary = new LinkedHashMap<>();
				runSummary.put("totalExecutedScenarios", 0);
				runSummary.put("passedScenarioDetails", Collections.emptyList());
				runSummary.put("totalFailedScenarios", 0);
				runSummary.put("totalPassedScenarios", 0);
				runSummary.put("durationMillis", 0);
				runSummary.put("totalUnexecutedScenarios", 1);
				result.put("runSummary", runSummary);
				result.put("xmlComparisonStatus", "N/A");
				result.put("xmlComparisonDetails", Collections.emptyList());

				// safe extraction of "first scenario name" using selections (preferred) or
				// fallback to scenarios
				String firstScenarioName = "N/A";
				try {
					if (testCase.getFeatureScenarios() != null && !testCase.getFeatureScenarios().isEmpty()) {
						TestCaseDTO.FeatureScenario fs0 = testCase.getFeatureScenarios().get(0);
						if (fs0.getSelections() != null && !fs0.getSelections().isEmpty()) {
							String nm = fs0.getSelections().get(0).getScenarioName();
							if (nm != null && !nm.isBlank())
								firstScenarioName = nm;
						} else if (fs0.getScenarios() != null && !fs0.getScenarios().isEmpty()) {
							String nm = fs0.getScenarios().get(0);
							if (nm != null && !nm.isBlank())
								firstScenarioName = nm;
						}
					}
				} catch (Exception ignore) {
				}

				Map<String, Object> errorDetail = new LinkedHashMap<>();
				errorDetail.put("scenarioName", firstScenarioName);
				errorDetail.put("errors", Collections.singletonList(e.getMessage()));
				errorDetail.put("exception", e.getClass().getSimpleName());
				result.put("unexecutedScenarioDetails", Collections.singletonList(errorDetail));
				result.put("diffSummary", Collections.emptyMap());

				// Try to persist a failure history header + (possibly empty) per-example rows
				// so caller can query details via runId
				try {
					TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
					if (entity != null) {
						entity.setLastRunOn(executedOn);
						entity.setLastRunStatus("Execution Error");
						testCaseRepository.save(entity);
					}

					TestCaseRunHistory history = new TestCaseRunHistory();
					history.setTestCase(entity);
					history.setRunTime(executedOn);
					history.setRunStatus("Execution Error");
					history.setXmlDiffStatus("N/A");
					history.setOutputLog(objectMapper.writeValueAsString(result));
					historyRepository.save(history);

					// Persist detail rows (will be empty lists here but ensures runId exists)
					try {
						persistPerExampleRows(entity, history, Collections.emptyList(), Collections.emptyList(),
								Collections.emptyList());
					} catch (Exception persistEx) {
						logger.error("Failed to persist per-example rows for failed history {}: {}", history.getId(),
								persistEx.getMessage(), persistEx);
					}

					// attach runId so clients can query details
					runId = history.getId();
					result.put("runId", runId);

				} catch (Exception dbEx) {
					logger.error("⚠ Failed to persist fallback run history for TestCase {}: {}", testCase.getTcId(),
							dbEx.getMessage(), dbEx);
				}
			}

			// If runSingleTestCase succeeded but didn't provide a runId (unlikely), create
			// run header now
			if (runId == null) {
				try {
					if (!result.containsKey("runId")) {
						TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
						if (entity != null) {
							entity.setLastRunOn(executedOn);
							entity.setLastRunStatus(
									result.containsKey("tcStatus") ? String.valueOf(result.get("tcStatus")) : null);
							testCaseRepository.save(entity);
						}

						TestCaseRunHistory history = new TestCaseRunHistory();
						history.setTestCase(entity);
						history.setRunTime(executedOn);
						history.setRunStatus(
								result.containsKey("tcStatus") ? String.valueOf(result.get("tcStatus")) : null);
						history.setXmlDiffStatus(result.containsKey("xmlComparisonStatus")
								? String.valueOf(result.get("xmlComparisonStatus"))
								: null);
						history.setOutputLog(objectMapper.writeValueAsString(result));
						historyRepository.save(history);

						try {
							List<Map<String, Object>> executedScenarios = (List<Map<String, Object>>) result
									.getOrDefault("executedScenarios", Collections.emptyList());
							List<Map<String, Object>> skippedScenarios = (List<Map<String, Object>>) result
									.getOrDefault("unexecutedScenarios", Collections.emptyList());
							List<Map<String, Object>> xmlComparisonDetails = (List<Map<String, Object>>) result
									.getOrDefault("xmlComparisonDetails", Collections.emptyList());

							persistPerExampleRows(entity, history, executedScenarios, skippedScenarios,
									xmlComparisonDetails);
						} catch (Exception persistEx) {
							logger.warn("Failed to persist detail rows for fallback history {}: {}", history.getId(),
									persistEx.getMessage());
						}

						runId = history.getId();
						result.put("runId", runId);
					}
				} catch (Exception e) {
					logger.error("Failed to persist fallback header for TestCase {}: {}", testCase.getTcId(),
							e.getMessage(), e);
				}
			}

			results.add(result);
			// Clear context and continue
			TestContext.clear();
		}
		return results;
	}

	private Map<String, Object> runSingleTestCase(TestCaseDTO testCase) throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		LocalDateTime executedOn = LocalDateTime.now();
		try {
			GitConfigDTO config = featureService.getGitConfig();

			if (config.getSourceType().equalsIgnoreCase("git")) {
				featureService.syncGitAndParseFeatures();
			}

			String baseFeaturePath;
			if (config.getSourceType().equalsIgnoreCase("git")) {
				baseFeaturePath = Paths.get(config.getCloneDir(), config.getGitFeaturePath()).toString();
			} else {
				baseFeaturePath = config.getLocalFeatherPath();
			}

			List<Map<String, Object>> results = new ArrayList<>();

			TestContext.setTestCaseId(testCase.getTcId());
			Map<String, String> featureToPathMap = new HashMap<>();
			Map<String, String> scenarioToFeatureMap = new HashMap<>();
			List<String> missingFeatures = new ArrayList<>();

			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				List<ScenarioBlock> blocks = new ArrayList<>();

				Path featureRoot = Paths.get(baseFeaturePath);
				Optional<Path> featurePathOpt = findFeatureFileRecursive(featureRoot, fs.getFeature());

				boolean featureExists = featurePathOpt.isPresent();
				String featureFilePath = featurePathOpt.isPresent() ? featurePathOpt.get().toString() : null;

				if (!featureExists) {
					missingFeatures.add(fs.getFeature());
				} else {
					// Feature-level tags once per feature file
					fs.setFeatureTags(extractFeatureTags(featureFilePath));
					fs.setBackgroundBlock(extractBackground(featureFilePath));
				}

				// NEW: per-scenario tag maps we’ll fill and store on fs
				Map<String, List<String>> scenarioTagsByName = new LinkedHashMap<>();
				Map<String, List<String>> exampleTagsByName = new LinkedHashMap<>();

				// ---------------------------
				// Build a safe list of scenario names to iterate
				// prefer fs.getScenarios(); fallback to fs.getSelections() names if null
				// ---------------------------
				List<String> scenarioNames = new ArrayList<>();
				if (fs.getScenarios() != null && !fs.getScenarios().isEmpty()) {
					scenarioNames.addAll(fs.getScenarios());
				} else if (fs.getSelections() != null && !fs.getSelections().isEmpty()) {
					for (TestCaseDTO.ScenarioSelection sel : fs.getSelections()) {
						if (sel != null && sel.getScenarioName() != null) {
							scenarioNames.add(sel.getScenarioName());
						}
					}
				}

				// iterate safely over scenarioNames (may be empty)
				for (String scenarioName : scenarioNames) {
					if (!featureExists) {
						// feature missing — nothing to extract from file
						continue;
					}

					try {
						featureToPathMap.put(fs.getFeature(), featureFilePath);

						// normalize key used for extraction
						String adjustedScenarioName = scenarioName == null ? "" : scenarioName.trim();
						if (!adjustedScenarioName.startsWith("-")) {
							adjustedScenarioName = "- " + adjustedScenarioName;
						}

						ScenarioBlock sb = extractScenarioBlock(featureFilePath, adjustedScenarioName);
						if (sb == null || sb.getContent() == null || sb.getContent().trim().isEmpty()) {
							continue;
						}

						if (sb.isFromExampleWithTags()) {
							logger.warn("Skipping scenario [{}] because it belongs to Examples with tags",
									adjustedScenarioName);
							continue;
						}

						blocks.add(sb);

						// map both clean and adjusted names for reverse lookup (optional)
						String featureFileName = Paths.get(featureFilePath).getFileName().toString();
						scenarioToFeatureMap.put(scenarioName.trim(), featureFileName);
						scenarioToFeatureMap.put(adjustedScenarioName, featureFileName);

						// Extract tags for this scenario
						List<String> scenarioTags = extractScenarioTags(featureFilePath, adjustedScenarioName);
						if (scenarioTags != null && !scenarioTags.isEmpty()) {
							scenarioTagsByName.put(scenarioName.trim(), scenarioTags);
						}

						List<String> exampleTags = extractExampleTags(featureFilePath, adjustedScenarioName);
						if (exampleTags != null && !exampleTags.isEmpty()) {
							exampleTagsByName.put(scenarioName.trim(), exampleTags);
						}

					} catch (IOException e) {
						logger.error("Error reading feature file: {}", featureFilePath, e);
					}
				}

				fs.setScenarioBlocks(blocks);
				// Store the per-scenario tag maps on the DTO
				fs.setScenarioTagsByName(scenarioTagsByName);
				fs.setExampleTagsByName(exampleTagsByName);
			}

			Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();

			// 1. Generate temporary feature file from TestCaseDTO
			File featureFile = generateTempFeatureFile(testCase);
			File jsonReportFile = File.createTempFile("cucumber-report", ".json");

			// Keep mapping for replacement
			Map<String, String> tempPathMapping = new HashMap<>();
			tempPathMapping.put(featureFile.getAbsolutePath(),
					featureToPathMap.values().stream().findFirst().orElse(featureFile.getAbsolutePath()));

			String tempFileName = featureFile.getName();
			String originalPath = featureToPathMap.values().stream().findFirst().orElse(featureFile.getAbsolutePath());
			String originalFileName = Paths.get(originalPath).getFileName().toString();
			// map temp → file name
			featureToPathMap.put(tempFileName, originalFileName);
			// store path separately
			featureToPathMap.put(originalFileName, originalPath);

			// 2. Setup Cucumber command-line arguments
			String[] gluePkgs = featureService.getGluePackagesArray();

			List<String> argvList = new ArrayList<>();

			// 1. Add glue packages
			for (String glue : gluePkgs) {
				argvList.add("--glue");
				argvList.add(glue);
			}

			// 2. Add plugins
			argvList.add("--plugin");
			argvList.add("pretty");
			argvList.add("--plugin");
			argvList.add("json:" + jsonReportFile.getAbsolutePath());

			// 3. Finally add the feature file(s)
			argvList.add(featureFile.getAbsolutePath());

			String[] argv = argvList.toArray(new String[0]);

			// ✅ Inject Maven Run Profile
			if (config.getSourceType().equalsIgnoreCase("git")) {
				String configPath = "src/test/resources/configs/" + config.getMavenEnv() + "/configs.properties";
				loadSystemPropertiesFromConfig(configPath);
			}

			// ✅ Ensure dependencies are copied once
			StepDefCompiler.ensureDependenciesCopied();

			// 3. Compile stepDefs if needed (before running cucumber)
//			for (String projPath : featureService.getStepDefsProjectPaths()) {
//				StepDefCompiler.compileStepDefs(Collections.singletonList(projPath)); // ✅
//			}

			// Always compile step defs from the current application project
			String currentAppPath = Paths.get(".").toAbsolutePath().normalize().toString();
			StepDefCompiler.compileStepDefs(Collections.singletonList(currentAppPath));

			// 3.1. Capture Cucumber stdout
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream originalOut = System.out;
			System.setOut(new PrintStream(baos));

			Map<String, Map<String, Pair<List<String>, List<String>>>> exampleMap;
			try {
				// ✅ Gather stepDef paths (target/classes, test-classes)
				List<String> stepDefsPaths = featureService.getStepDefsFullPaths();

				List<URL> urls = new ArrayList<>();

				for (String path : stepDefsPaths) {
					File f = new File(path);
					if (f.exists()) {
						urls.add(f.toURI().toURL());
					}
				}

				// ✅ Add jars from target/dependency
				File depDir = new File("target/dependency");
				if (depDir.exists() && depDir.isDirectory()) {
					File[] jars = depDir.listFiles((dir, name) -> name.endsWith(".jar"));
					if (jars != null) {
						for (File jar : jars) {
							urls.add(jar.toURI().toURL());
						}
					}
				}

				// ✅ Now just run cucumber using the system classloader
				// Create a URLClassLoader with stepDefs and dependency jars
				try (URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]),
						Thread.currentThread().getContextClassLoader())) {
					logger.info("Running Cucumber with argv: {}", Arrays.toString(argv));
					Main.run(argv, classLoader);
				}

				exampleMap = extractExamplesFromFeature(featureFile);

			} finally {
				System.out.flush();
				System.setOut(originalOut);
				if (cleanupTempFeature && featureFile.exists()) {
					if (!featureFile.delete()) {
						logger.warn("Could not delete temp feature file: {}", featureFile.getAbsolutePath());
					} else {
						logger.info("Deleted temp feature file: {}", featureFile.getAbsolutePath());
					}
				}
			}

			String fullOutput = baos.toString();

			// ✅ Replace temp paths with actual feature paths in output
			for (Map.Entry<String, String> entry : tempPathMapping.entrySet()) {
				String tempPath = entry.getKey().replace("\\", "/");
				String realPath = entry.getValue().replace("\\", "/");
				fullOutput = fullOutput.replace(tempPath, realPath);
				fullOutput = fullOutput.replace(tempFileName, originalFileName);
			}

			logger.info("===== Begin Test Output =====\n{}\n===== End Test Output =====", fullOutput);

			// 5. Parse the generated JSON report
			executedScenarios = parseCucumberJson(jsonReportFile, exampleMap, featureToPathMap);

			if (jsonReportFile.exists())
				jsonReportFile.delete();

			// ✅ Split: truly executed vs skipped/unexecuted
			List<Map<String, Object>> trulyExecutedScenarios = new ArrayList<>();
			List<Map<String, Object>> skippedScenarios = new ArrayList<>();

			for (Map<String, Object> scenario : executedScenarios) {
				String rawName = (String) scenario.get("featureFileName");
				if (rawName != null) {
					// mapped file name (clean, not temp)
					String mappedFileName = featureToPathMap.getOrDefault(rawName, rawName);
					scenario.put("featureFileName", mappedFileName);
					// also attach full path if available
					String fullPath = featureToPathMap.get(mappedFileName);
					if (fullPath != null) {
						scenario.put("featureFilePath", fullPath);
					}
				}
				String status = (String) scenario.get("status");
				List<String> errors = safeList(scenario.get("errors"));

				if ("Passed".equalsIgnoreCase(status) || "Failed".equalsIgnoreCase(status)) {
					trulyExecutedScenarios.add(scenario);
				} else if ("Undefined".equalsIgnoreCase(status)) {
					scenario.put("status", "Skipped");
					scenario.put("skipReason", "Glue not found – scenario skipped");
					skippedScenarios.add(scenario);
				} else if ("Skipped".equalsIgnoreCase(status) && errors.isEmpty()) {
					scenario.put("skipReason", "All steps skipped (Background only)");
					skippedScenarios.add(scenario);
				}
			}

			// 6. Extract scenario names from executedScenarios
			// Real executed = passed, failed, skipped
			Set<String> executedScenarioNames = Stream
					.concat(trulyExecutedScenarios.stream(), skippedScenarios.stream())
					.map(s -> String.valueOf(s.get("scenarioName")).trim()).collect(Collectors.toSet());

			Set<String> declaredScenarioNames = new HashSet<>();
			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				if (fs.getScenarios() != null) {
					for (String s : fs.getScenarios()) {
						if (s != null)
							declaredScenarioNames.add(s.trim());
					}
				} else if (fs.getSelections() != null) {
					for (TestCaseDTO.ScenarioSelection sel : fs.getSelections()) {
						if (sel != null && sel.getScenarioName() != null) {
							declaredScenarioNames.add(sel.getScenarioName().trim());
						}
					}
				}
			}

			// Java: Match scenario outlines by prefix
			Set<String> unexecuted = declaredScenarioNames.stream()
					.filter(declared -> executedScenarioNames.stream()
							.noneMatch(executed -> executed.startsWith(declared.replace("<Scenario>", ""))))
					.collect(Collectors.toSet());

			List<Map<String, Object>> allUnexecutedScenarios = new ArrayList<>();

			List<Map<String, Object>> unexecutedList = new ArrayList<>();
			for (String scenarioName : unexecuted) {
				Map<String, Object> detail = new LinkedHashMap<>();
				detail.put("scenarioName", scenarioName);

				try {
					Optional<String> featureOpt = testCase.getFeatureScenarios().stream()
							.filter(fs -> fs.getScenarios().contains(scenarioName))
							.map(TestCaseDTO.FeatureScenario::getFeature).findFirst();

					String feature = featureOpt.orElse(null);

					if (feature == null) {
						detail.put("reason", "Scenario declared but not linked to any feature in test case JSON");
					} else if (missingFeatures.contains(feature)) {
						detail.put("reason", "Feature file not found: " + feature);
					} else if (!scenarioToFeatureMap.containsKey(scenarioName)) {
						detail.put("reason", "Scenario block not found in feature file: " + feature);
					} else {
						detail.put("reason", "Unknown reason – check logs for full trace");
					}
				} catch (Exception e) {
					detail.put("reason", "Exception during reason detection");
					detail.put("exception", e.getClass().getSimpleName());
					detail.put("message", e.getMessage());
				}
				if (skippedScenarios.size() == 0) {
					unexecutedList.add(detail);
					allUnexecutedScenarios.add(detail);
				}
			}

			// ✅ Compute execution status
			boolean anyFailed = trulyExecutedScenarios.stream().anyMatch(s -> "Failed".equals(s.get("status")));
			boolean allFailed = trulyExecutedScenarios.isEmpty()
					|| trulyExecutedScenarios.stream().allMatch(s -> "Failed".equals(s.get("status")));
			String statusByExecution = allFailed ? "Failed" : anyFailed ? "Partially Passed" : "Passed";

			TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
			String inputPath = entity != null ? entity.getInputFile() : null;
			String outputPath = entity != null ? entity.getOutputFile() : null;

			List<Map<String, Object>> xmlComparisonDetails = new ArrayList<>();

			for (Map<String, Object> scenario : trulyExecutedScenarios) {
				List<String> errors = safeList(scenario.get("errors"));
				String featureName = (String) scenario.get("featureFileName");
				if (featureName == null)
					continue;

				Map<String, Object> xmlDetail = buildXmlComparisonDetails(inputPath, outputPath,
						Paths.get(featureName).getFileName().toString(), Collections.singleton(scenario), objectMapper);

				xmlDetail.put("scenarioName", scenario.get("scenarioName"));

				String scenarioType = (scenario.containsKey("exampleHeader") && scenario.containsKey("exampleValues"))
						? "Scenario Outline"
						: "Scenario";
				xmlDetail.put("scenarioType", scenarioType);

				if (scenario.containsKey("exampleHeader"))
					xmlDetail.put("exampleHeader", scenario.get("exampleHeader"));
				if (scenario.containsKey("exampleValues"))
					xmlDetail.put("exampleValues", scenario.get("exampleValues"));

				int diffCount = (int) Optional.ofNullable(xmlDetail.get("differences")).map(d -> ((List<?>) d).size())
						.orElse(0);
				xmlDetail.put("diffCount", diffCount);
				 
				//Derive per-example XML diff status and attach to xmlDetail & scenario map
				String perExampleStatus = derivePerExampleXmlStatus(
				        String.valueOf(scenario.get("scenarioName")),
				        (String) xmlDetail.get("inputXml"),   // or the inputXml you have available
				        (String) xmlDetail.get("outputXml"),  // or the outputXml you have available
				        toJsonOrString(xmlDetail.get("differences")),
				        /* xmlDetailByScenario */ null,        // optional: pass a lookup map if you have one here
				        /* skippedScenarios */ skippedScenarios // pass list so Unexecuted can be detected
				);
				xmlDetail.put("xmlDiffStatus", perExampleStatus);

				// also add it back to the scenario map (so persistPerExampleRows may read from scenario if necessary)
				scenario.put("xmlDiffStatus", perExampleStatus);

				// Match unexecuted scenarios to feature
				List<Map<String, Object>> unexecutedForFeature = unexecutedList.stream().filter(e -> {
					String unexecName = String.valueOf(e.get("scenarioName")).trim();
					String unexecFeature = Paths.get(String.valueOf(e.get("featureFileName"))).normalize().toString();
					String execFeature = Paths.get(featureName).normalize().toString();
					boolean featureMatch = unexecFeature.endsWith(execFeature);
					boolean scenarioMatch = testCase.getFeatureScenarios().stream().anyMatch(
							fs -> fs.getScenarios().stream().anyMatch(s -> s.trim().equalsIgnoreCase(unexecName)));
					return featureMatch && scenarioMatch;
				}).collect(Collectors.toList());

				if (!unexecutedForFeature.isEmpty()) {
					xmlDetail.put("unexecutedScenarios", unexecutedForFeature);
					allUnexecutedScenarios.addAll(unexecutedForFeature);
				}

				xmlComparisonDetails.add(xmlDetail);
			}

			// ✅ Add skipped scenarios to unexecuted & output map
			for (Map<String, Object> skipped : skippedScenarios) {
				Map<String, Object> skippedDetail = new LinkedHashMap<>();
				skippedDetail.put("scenarioName", skipped.get("scenarioName"));
				skippedDetail.put("scenarioType", skipped.getOrDefault("scenarioType", "Scenario"));
				skippedDetail.put("errors", safeList(skipped.get("errors")));
				skippedDetail.put("status", skipped.get("status"));
				skippedDetail.put("skipReason", "Glue not found – scenario skipped");

				// ✅ Add exampleHeader / exampleValues if they exist (Scenario Outline)
				if (skipped.containsKey("exampleHeader")) {
					skippedDetail.put("exampleHeader", skipped.get("exampleHeader"));
				}
				if (skipped.containsKey("exampleValues")) {
					skippedDetail.put("exampleValues", skipped.get("exampleValues"));
				}

				// ✅ Include feature file name + path if present
				if (skipped.containsKey("featureFileName")) {
					String rawFeature = (String) skipped.get("featureFileName");

					// clean name (just the file name)
					String fileName = Paths.get(rawFeature).getFileName().toString();
					skippedDetail.put("featureFileName", fileName);

					// full path (from mapping if available)
					String fullPath = featureToPathMap.getOrDefault(fileName, rawFeature);
					skippedDetail.put("featureFilePath", fullPath);
				}

				unexecutedList.add(skippedDetail);
				allUnexecutedScenarios.add(skippedDetail);
			}

			// ✅ Decide comparisonStatus
			String comparisonStatus;
			if (!allUnexecutedScenarios.isEmpty()) {
				if (!xmlComparisonDetails.isEmpty() && xmlComparisonDetails.stream()
						.anyMatch(m -> !"✅ XML files are equal.".equals(m.get("message")))) {
					comparisonStatus = "Partially Unexecuted";
				} else {
					comparisonStatus = "N/A";
				}
			} else if (trulyExecutedScenarios.isEmpty() && !missingFeatures.isEmpty()) {
				comparisonStatus = null;
			} else if (xmlComparisonDetails.stream()
					.anyMatch(m -> !"✅ XML files are equal.".equals(m.get("message")))) {
				comparisonStatus = "Mismatched";
			} else {
				comparisonStatus = "Matched";
			}

			// ✅ Derive finalStatus
			String finalStatus;
			if ("Passed".equals(statusByExecution) && "Mismatched".equals(comparisonStatus)) {
				finalStatus = "Discrepancy";
			} else if ("Partially Unexecuted".equals(comparisonStatus)) {
				finalStatus = "Partially Unexecuted";
			} else if ("N/A".equals(comparisonStatus)) {
				finalStatus = "Unexecuted";
			} else {
				finalStatus = statusByExecution;
			}

			TestCaseRunHistory history = new TestCaseRunHistory();
			history.setTestCase(entity);
			history.setRunTime(executedOn);
			history.setRunStatus(finalStatus);
			history.setXmlDiffStatus(comparisonStatus);
			history.setXmlParsedDifferencesJson(prettyWriter.writeValueAsString(xmlComparisonDetails));
			long durationStart = System.currentTimeMillis();
			// Save only scenario names for executed scenarios
			ObjectMapper mapper = new ObjectMapper();
			List<String> executedNames = trulyExecutedScenarios.stream().map(s -> (String) s.get("scenarioName"))
					.collect(Collectors.toList());
			history.setExecutedScenarios(executedNames.isEmpty() ? null : mapper.writeValueAsString(executedNames));

			// Save only scenario names for unexecuted scenarios
			List<String> unexecutedNames = unexecutedList.stream().map(s -> (String) s.get("scenarioName"))
					.collect(Collectors.toList());
			history.setUnexecutedScenarios(
					unexecutedNames.isEmpty() ? null : mapper.writeValueAsString(unexecutedNames));

			List<String> passedNames = executedScenarios.stream().filter(s -> "Passed".equals(s.get("status")))
					.map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());

			List<String> failedNames = executedScenarios.stream().filter(s -> "Failed".equals(s.get("status")))
					.map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());

			Map<String, Object> runSummary = new LinkedHashMap<>();
			runSummary.put("totalExecutedScenarios", trulyExecutedScenarios.size());
			List<Map<String, Object>> passedScenariosDetailed = new ArrayList<>();
			for (Map<String, Object> exec : executedScenarios) {
				if ("Passed".equals(exec.get("status"))) {
					Map<String, Object> passedEntry = new LinkedHashMap<>();
					passedEntry.put("scenarioName", exec.get("scenarioName"));
					passedEntry.put("scenarioType", exec.getOrDefault("scenarioType", "Scenario"));

					if ("Scenario Outline".equals(exec.get("scenarioType"))) {
						passedEntry.put("exampleHeader", exec.containsKey("exampleHeader") ? exec.get("exampleHeader")
								: Collections.emptyList());
						passedEntry.put("exampleValues", exec.containsKey("exampleValues") ? exec.get("exampleValues")
								: Collections.emptyList());
					}

					passedScenariosDetailed.add(passedEntry);
				}
			}
			runSummary.put("passedScenarioDetails", passedScenariosDetailed);

			runSummary.put("totalFailedScenarios", failedNames.size());
			runSummary.put("totalPassedScenarios", passedNames.size());
			runSummary.put("durationMillis", System.currentTimeMillis() - durationStart);
			runSummary.put("totalUnexecutedScenarios", unexecutedList.size());

			Map<String, Object> outputLogMap = new LinkedHashMap<>();
			outputLogMap.put("runSummary", runSummary);
			outputLogMap.put("unexecutedScenarioDetails", unexecutedList);

			List<Map<String, Object>> failedReasons = new ArrayList<>();
			for (Map<String, Object> exec : executedScenarios) {
				if ("Failed".equals(exec.get("status"))) {
					Map<String, Object> entry = new LinkedHashMap<>();
					List<String> parsedDiffs = (List<String>) exec.getOrDefault("parsedDifferences",
							Collections.emptyList());

					entry.put("scenarioName", exec.get("scenarioName"));
					entry.put("scenarioType", exec.getOrDefault("scenarioType", "Scenario"));

					if ("Scenario Outline".equals(exec.get("scenarioType"))) {
						entry.put("exampleHeader", exec.containsKey("exampleHeader") ? exec.get("exampleHeader")
								: Collections.emptyList());
						entry.put("exampleValues", exec.containsKey("exampleValues") ? exec.get("exampleValues")
								: Collections.emptyList());
					}

					List<String> errors = safeList(exec.get("errors")).stream().map(err -> {
						for (Map.Entry<String, String> e : tempPathMapping.entrySet()) {
							err = err.replace(e.getKey().replace("\\", "/"), e.getValue().replace("\\", "/"));
						}
						return err;
					}).collect(Collectors.toList());

					entry.put("errors", errors);
					entry.put("parsedDifferences", parsedDiffs);
					entry.put("parsedDiffCount", parsedDiffs.size());
					failedReasons.add(entry);
				}
			}
			outputLogMap.put("failedScenarioDetails", failedReasons);

			history.setOutputLog(prettyWriter.writeValueAsString(outputLogMap));

			List<String> fullOutputLines = Arrays.asList(fullOutput.split("\\R"));
			List<String> trimmedLines = new ArrayList<>();

			for (String line : fullOutputLines) {
				trimmedLines.add(line);
				if (line.matches("^\\d+m\\d+\\.\\d+s$")) {
					break;
				}
			}

			List<String> cleanedLines = cleanRawCucumberLog(trimmedLines);

			while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).trim().isEmpty()) {
				cleanedLines.remove(cleanedLines.size() - 1);
			}

			List<String> finalLines = new ArrayList<>();
			boolean previousBlank = false;
			for (String line : cleanedLines) {
				if (line.trim().isEmpty()) {
					if (!previousBlank) {
						finalLines.add("");
						previousBlank = true;
					}
				} else {
					finalLines.add(line);
					previousBlank = false;
				}
			}

			String cleanedLog = String.join("\n", finalLines);
			history.setRawCucumberLog(cleanedLog);

//				history.setUnexecutedScenarios(unexecuted.isEmpty() ? null : String.join(", ", unexecuted));
			history.setInputXmlContent((String) TestContext.get("inputXmlContent"));
			history.setOutputXmlContent((String) TestContext.get("outputXmlContent"));

//			historyRepository.save(history);
			// save header
			historyRepository.save(history);

			// Persist per-example/detail rows (bulk)
			try {
				// 'entity' is the TestCase entity you already fetched earlier
				persistPerExampleRows(entity, history, trulyExecutedScenarios, skippedScenarios, xmlComparisonDetails);
			} catch (Exception ex) {
				logger.error("Failed to persist per-example rows for history {}: {}", history.getId(), ex.getMessage(),
						ex);
			}

			// expose run id to client so they can fetch per-example details later
			result.put("runId", history.getId());

			// existing output population
			result.put("testCaseId", testCase.getTcId());
			result.put("testCaseName", testCase.getTcName());
			result.put("runOn", executedOn);
			result.put("tcStatus", finalStatus);
			result.put("xmlComparisonStatus", comparisonStatus);
			result.put("xmlComparisonDetails", xmlComparisonDetails);
			result.put("diffSummary", computeDiffSummary(xmlComparisonDetails, executedScenarios));

			if (!trulyExecutedScenarios.isEmpty()) {
				result.put("executedScenarios", trulyExecutedScenarios);
			}
			if (!unexecutedList.isEmpty()) {
				result.put("unexecutedScenarios", unexecutedList);
			}

			results.add(result);

			// update test case entity last run info (unchanged)
			if (entity != null) {
				entity.setLastRunOn(executedOn);
				entity.setLastRunStatus(finalStatus);
				testCaseRepository.save(entity);
			}

			TestContext.remove("inputXmlContent");
			TestContext.remove("outputXmlContent");

			TestContext.clear();

		} catch (Exception e) {
			logger.error("Execution failed for TestCase {}: {}", testCase.getTcId(), e.getMessage(), e);

			// mark status in response
			result.put("tcStatus", "Execution Error");

			// 🔹 Prepare runSummary even on failure
			Map<String, Object> runSummary = new LinkedHashMap<>();
			runSummary.put("totalExecutedScenarios", 0);
			runSummary.put("passedScenarioDetails", Collections.emptyList());
			runSummary.put("totalFailedScenarios", 0);
			runSummary.put("totalPassedScenarios", 0);
			runSummary.put("durationMillis", 0);
			runSummary.put("totalUnexecutedScenarios", 1);
			result.put("runSummary", runSummary);
			result.put("xmlComparisonStatus", "N/A");
			result.put("xmlComparisonDetails", Collections.emptyList());

			// safe: extract a best-effort first scenario name from selections (avoid old
			// getScenarios())
			String firstScenarioName = "N/A";
			try {
				if (testCase.getFeatureScenarios() != null && !testCase.getFeatureScenarios().isEmpty()) {
					TestCaseDTO.FeatureScenario fs0 = testCase.getFeatureScenarios().get(0);
					if (fs0.getSelections() != null && !fs0.getSelections().isEmpty()) {
						String nm = fs0.getSelections().get(0).getScenarioName();
						if (nm != null && !nm.isBlank())
							firstScenarioName = nm;
					} else if (fs0.getScenarios() != null && !fs0.getScenarios().isEmpty()) {
						// fallback if older DTO still present
						String nm = fs0.getScenarios().get(0);
						if (nm != null && !nm.isBlank())
							firstScenarioName = nm;
					}
				}
			} catch (Exception ignore) {
			}

			Map<String, Object> errorDetail = new LinkedHashMap<>();
			errorDetail.put("scenarioName", firstScenarioName);
			errorDetail.put("reason", Collections.singletonList(e.getMessage()));
			errorDetail.put("exception", e.getClass().getSimpleName());

			result.put("unexecutedScenarioDetails", Collections.singletonList(errorDetail));
			result.put("diffSummary", Collections.emptyMap());

			// ✅ Save failure run history too and persist per-example rows (even if empty)
			try {
				TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
				if (entity != null) {
					entity.setLastRunOn(executedOn);
					entity.setLastRunStatus("Execution Error");
					testCaseRepository.save(entity);
				}

				TestCaseRunHistory history = new TestCaseRunHistory();
				history.setTestCase(entity);
				history.setRunTime(executedOn);
				history.setRunStatus("Execution Error");
				history.setXmlDiffStatus("N/A");
				history.setOutputLog(objectMapper.writeValueAsString(result));
				historyRepository.save(history);

				// persist per-example rows (will be empty lists here) so runId is available
				try {
					persistPerExampleRows(entity, history, Collections.emptyList(), Collections.emptyList(),
							Collections.emptyList());
				} catch (Exception persistEx) {
					logger.error("Failed to persist per-example rows for failed history {}: {}", history.getId(),
							persistEx.getMessage(), persistEx);
				}

				// expose run id to caller
				result.put("runId", history.getId());

			} catch (Exception dbEx) {
				logger.error("⚠ Failed to persist run history for TestCase {}: {}", testCase.getTcId(),
						dbEx.getMessage(), dbEx);
			}
		}
		TestContext.clear();
		return result;
	}

	private List<String> extractFeatureTags(String featureFilePath) throws IOException {
		List<String> tags = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(Paths.get(featureFilePath))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.startsWith("Feature:")) {
					break; // stop once Feature: is reached
				}
				if (line.startsWith("@")) {
					tags.addAll(Arrays.asList(line.split("\\s+")));
				}
			}
		}
		return tags;
	}

	// ✅ Extract tags that appear immediately before a Scenario/Scenario Outline
	private List<String> extractScenarioTags(String featureFilePath, String scenarioName) throws IOException {
		List<String> tags = new ArrayList<>();
		List<String> lines = Files.readAllLines(Paths.get(featureFilePath));

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).trim();

			if (line.startsWith("Scenario Outline:") || line.startsWith("Scenario:")) {
				// Normalize the scenario heading just like in generateTempFeatureFile
				boolean isOutline = line.startsWith("Scenario Outline:");
				String after = line.substring(isOutline ? "Scenario Outline:".length() : "Scenario:".length()).trim();
				if (after.startsWith("-"))
					after = after.substring(1).trim();

				if (after.equalsIgnoreCase(scenarioName.replaceFirst("^-\\s*", "").trim())) {
					// look backwards for contiguous @tag lines
					int j = i - 1;
					while (j >= 0 && lines.get(j).trim().startsWith("@")) {
						tags.add(0, lines.get(j).trim()); // insert at front to preserve order
						j--;
					}
					break;
				}
			}
		}
		return tags;
	}

	// ✅ Extract tags that appear immediately before Examples: in a given scenario
	private List<String> extractExampleTags(String featureFilePath, String scenarioName) throws IOException {
		List<String> tags = new ArrayList<>();
		List<String> lines = Files.readAllLines(Paths.get(featureFilePath));

		boolean insideTargetScenario = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).trim();

			if (line.startsWith("Scenario Outline:") || line.startsWith("Scenario:")) {
				// new scenario starts → check if it's our target
				boolean isOutline = line.startsWith("Scenario Outline:");
				String after = line.substring(isOutline ? "Scenario Outline:".length() : "Scenario:".length()).trim();
				if (after.startsWith("-"))
					after = after.substring(1).trim();

				insideTargetScenario = after.equalsIgnoreCase(scenarioName.replaceFirst("^-\\s*", "").trim());
			}

			if (insideTargetScenario && line.startsWith("Examples:")) {
				// look backwards for contiguous @tag lines
				int j = i - 1;
				while (j >= 0 && lines.get(j).trim().startsWith("@")) {
					tags.add(0, lines.get(j).trim()); // preserve order
					j--;
				}
				break;
			}
		}

		return tags;
	}

	private List<String> extractBackground(String featureFilePath) throws IOException {
		List<String> lines = Files.readAllLines(Paths.get(featureFilePath));
		List<String> background = new ArrayList<>();

		boolean inBackground = false;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith("Background:")) {
				inBackground = true;
				background.add(trimmed);
				continue;
			}
			if (inBackground) {
				if (trimmed.startsWith("Scenario") || trimmed.startsWith("@")) {
					// background ended
					break;
				}
				background.add(line);
			}
		}
		return background.isEmpty() ? null : background;
	}

	public class ScenarioBlock {
		private final String content; // full text of the block (including steps/examples)
		private final String type; // "Scenario" or "Scenario Outline"
		private final boolean fromExampleWithTags; // ✅ new flag

		public ScenarioBlock(String content, String type, boolean fromExampleWithTags) {
			this.content = content;
			this.type = type;
			this.fromExampleWithTags = fromExampleWithTags;
		}

		public String getContent() {
			return content;
		}

		public String getType() {
			return type;
		}

		public boolean isFromExampleWithTags() {
			return fromExampleWithTags;
		}
	}

	private ScenarioBlock extractScenarioBlock(String featureFilePath, String scenarioName) throws IOException {
		List<String> lines = Files.readAllLines(Paths.get(featureFilePath));
		StringBuilder block = new StringBuilder();
		boolean capture = false;
		boolean possibleTag = false;
		boolean fromExampleWithTags = false; // ✅ flag
		String type = null; // "Scenario" or "Scenario Outline"

		for (String line : lines) {
			String trimmed = line.trim();

			// potential tag before scenario
			if (trimmed.startsWith("@") && !capture) {
				block.setLength(0); // reset
				block.append(trimmed).append("\n");
				possibleTag = true;
				continue;
			}

			// if we are already capturing and encounter another scenario => stop
			if (capture && (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:"))) {
				break;
			}

			if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
				String extractedName = trimmed.replace("Scenario:", "").replace("Scenario Outline:", "").split("#")[0]
						.trim();

				String normalizedExtracted = extractedName.startsWith("-") ? extractedName.substring(1).trim()
						: extractedName;
				String normalizedTarget = scenarioName.startsWith("-") ? scenarioName.substring(1).trim()
						: scenarioName;

				if (normalizedExtracted.equals(normalizedTarget)) {
					if (!possibleTag) {
						block.setLength(0);
					}
					block.append(trimmed).append("\n");
					capture = true;
					type = trimmed.startsWith("Scenario Outline:") ? "Scenario Outline" : "Scenario";
				} else {
					block.setLength(0);
				}
				continue;
			}

			if (capture) {
				if (trimmed.startsWith("@") && block.toString().contains("Examples:")) {
					fromExampleWithTags = true; // ✅ tagged Examples detected
				}
				block.append(trimmed.isEmpty() ? "\n" : "  " + trimmed + "\n");
			}
		}

		if (!capture || type == null) {
			return null;
		}

		return new ScenarioBlock(block.toString().trim(), type, fromExampleWithTags); // ✅ pass flag
	}

	private File generateTempFeatureFile(TestCaseDTO testCase) throws IOException {
		StringBuilder out = new StringBuilder();

		// Build a quick lookup for selections: scenarioName -> ScenarioSelection
		Map<String, TestCaseDTO.ScenarioSelection> selectionByScenario = new HashMap<>();
		if (testCase.getFeatureScenarios() != null) {
			for (TestCaseDTO.FeatureScenario f : testCase.getFeatureScenarios()) {
				if (f != null && f.getSelections() != null) {
					for (TestCaseDTO.ScenarioSelection sel : f.getSelections()) {
						if (sel != null && sel.getScenarioName() != null) {
							selectionByScenario.put(sel.getScenarioName().trim(), sel);
						}
					}
				}
			}
		}

		if (testCase.getFeatureScenarios() != null) {
			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				if (fs == null)
					continue;

				// --- Feature-level tags (preserve) ---
				if (fs.getFeatureTags() != null && !fs.getFeatureTags().isEmpty()) {
					out.append(String.join(" ", fs.getFeatureTags()).trim()).append("\n");
				}

				// --- Feature line (use test case name as feature title) ---
				out.append("Feature: ").append(testCase.getTcName() == null ? "" : testCase.getTcName().trim())
						.append("\n\n");

				// --- Background (once per feature) ---
				if (fs.getBackgroundBlock() != null && !fs.getBackgroundBlock().isEmpty()) {
					out.append("Background:\n");
					for (String bgLine : fs.getBackgroundBlock()) {
						String trimmed = bgLine == null ? "" : bgLine.trim();
						if (!trimmed.isEmpty() && !trimmed.startsWith("Background:")) {
							out.append("  ").append(trimmed).append("\n");
						}
					}
					out.append("\n");
				}

				String currentScenarioTitle = null;

				if (fs.getScenarioBlocks() != null) {
					for (ScenarioBlock block : fs.getScenarioBlocks()) {
						if (block == null || block.getContent() == null) {
							continue;
						}
						String[] lines = block.getContent().split("\n");

						for (int i = 0; i < lines.length; i++) {
							String raw = lines[i];
							String s = raw == null ? "" : raw.trim();
							if (s.isEmpty()) {
								continue;
							}

							// skip original tag lines (we re-print tags from DTO maps)
							if (s.startsWith("@")) {
								continue;
							}

							// --- Scenario / Scenario Outline header ---
							if (s.startsWith("Scenario Outline:") || s.startsWith("Scenario:")) {
								boolean isOutline = s.startsWith("Scenario Outline:");
								String after = s
										.substring(isOutline ? "Scenario Outline:".length() : "Scenario:".length())
										.trim();
								if (after.startsWith("-")) {
									after = after.substring(1).trim();
								}
								currentScenarioTitle = after;

								// Re-print scenario-level tags from DTO (if present)
								if (fs.getScenarioTagsByName() != null) {
									List<String> scenarioTags = fs.getScenarioTagsByName().get(currentScenarioTitle);
									if (scenarioTags != null && !scenarioTags.isEmpty()) {
										out.append(String.join(" ", scenarioTags).trim()).append("\n");
									}
								}

								out.append(isOutline ? "Scenario Outline: " : "Scenario: ").append(after).append("\n");
								continue;
							}

							// --- Examples block start ---
							if (s.startsWith("Examples:")) {
								// Print example-level tags (if present in DTO)
								if (currentScenarioTitle != null && fs.getExampleTagsByName() != null) {
									List<String> exTags = fs.getExampleTagsByName().get(currentScenarioTitle);
									if (exTags != null && !exTags.isEmpty()) {
										out.append("\n").append(String.join(" ", exTags).trim()).append("\n");
									} else {
										out.append("\n");
									}
								} else {
									out.append("\n");
								}

								out.append("Examples:\n");

								// If we have a user selection for this scenario, write only those selected
								// examples
								TestCaseDTO.ScenarioSelection sel = null;
								if (currentScenarioTitle != null) {
									sel = selectionByScenario.get(currentScenarioTitle);
								}

								if (sel != null && sel.getSelectedExamples() != null) {
									TestCaseDTO.ExampleTable et = sel.getSelectedExamples();

									List<String> headers = et.getHeaders();
									List<Map<String, String>> rows = et.getRows();

									// If headers present, compute max width for each column
									if (headers != null && !headers.isEmpty()) {
										final int cols = headers.size();
										int[] maxWidths = new int[cols];
										// initial widths from headers
										for (int c = 0; c < cols; c++) {
											String h = headers.get(c) == null ? "" : headers.get(c).trim();
											maxWidths[c] = h.length();
										}
										// update widths from rows
										if (rows != null) {
											for (Map<String, String> row : rows) {
												for (int c = 0; c < cols; c++) {
													String key = headers.get(c) == null ? "" : headers.get(c).trim();
													String val = "";
													if (row != null && row.containsKey(key) && row.get(key) != null) {
														val = row.get(key).trim().replace("\\", "/");
													}
													if (val.length() > maxWidths[c]) {
														maxWidths[c] = val.length();
													}
												}
											}
										}

										// build header line with padded cells
										StringBuilder headerLine = new StringBuilder();
										headerLine.append("|");
										for (int c = 0; c < cols; c++) {
											String h = headers.get(c) == null ? "" : headers.get(c).trim();
											headerLine.append(" ").append(padRight(h, maxWidths[c])).append(" ")
													.append("|");
										}
										out.append("  ").append(headerLine.toString()).append("\n");

										// build each row line
										if (rows != null && !rows.isEmpty()) {
											for (Map<String, String> row : rows) {
												StringBuilder rowLine = new StringBuilder();
												rowLine.append("|");
												for (int c = 0; c < cols; c++) {
													String key = headers.get(c) == null ? "" : headers.get(c).trim();
													String val = "";
													if (row != null && row.containsKey(key) && row.get(key) != null) {
														val = row.get(key).trim().replace("\\", "/");
													}
													rowLine.append(" ").append(padRight(val, maxWidths[c])).append(" ")
															.append("|");
												}
												out.append("  ").append(rowLine.toString()).append("\n");
											}
										}
									} else {
										// No headers -> print rows best-effort without alignment (fallback)
										if (rows != null && !rows.isEmpty()) {
											for (Map<String, String> row : rows) {
												StringBuilder rowLine = new StringBuilder();
												rowLine.append("|");
												if (row != null) {
													for (String v : row.values()) {
														String vv = v == null ? "" : v.trim().replace("\\", "/");
														rowLine.append(" ").append(vv).append(" ").append("|");
													}
												}
												out.append("  ").append(rowLine.toString()).append("\n");
											}
										}
									}

									// Skip original table rows in the block (advance i while next lines start with
									// '|')
									while (i + 1 < lines.length && lines[i + 1] != null
											&& lines[i + 1].trim().startsWith("|")) {
										i++;
									}

									// continue outer processing (we already wrote the examples)
									continue;
								} else {
									// No selection found: fall back to printing original table rows from the
									// scenario block. The loop below (when it sees '|' lines) will print them
									// as-is.
									continue;
								}
							}

							// --- Table rows (original) - print as-is but indented under Examples ---
							if (s.startsWith("|")) {
								out.append("  ").append(s).append("\n");
								continue;
							}

							// --- Steps (Given/When/Then/And/But) ---
							if (s.matches("^(Given|When|Then|And|But).*")) {
								out.append("  ").append(s).append("\n");
								continue;
							}

							// Fallback: print the trimmed line
							out.append(s).append("\n");
						} // end lines loop

						out.append("\n"); // one blank line between scenarios
					} // end scenarioBlocks loop
				} // end scenarioBlocks null check
			} // end featureScenarios loop
		} // end featureScenarios null check

		// Trim trailing whitespace from each line and write to temp file
		String[] rawLines = out.toString().split("\n");
		File temp = File.createTempFile("testcase_", ".feature");
		try (BufferedWriter w = new BufferedWriter(new FileWriter(temp))) {
			for (String line : rawLines) {
				if (line == null) {
					line = "";
				}
				// remove trailing spaces
				line = line.replaceAll("\\s+$", "");
				w.write(line);
				w.newLine();
			}
		}
		return temp;
	}

	/** Helper to pad a string to the right (Java 8). */
	private static String padRight(String s, int n) {
		if (s == null)
			s = "";
		if (s.length() >= n)
			return s;
		StringBuilder b = new StringBuilder(s);
		for (int i = s.length(); i < n; i++) {
			b.append(' ');
		}
		return b.toString();
	}

	public List<Map<String, Object>> extractXmlDifferences(String diffOutput) {
		List<Map<String, Object>> diffs = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		StringBuilder buffer = new StringBuilder();
		boolean inside = false;

		for (String line : diffOutput.split("\r?\n")) {
			line = line.trim();

			boolean isNewStart = line.matches("(?i)^Expected .+ but was .+ - comparing .+ at .+")
					|| line.contains("Expected XML files to be equal")
					|| line.contains("Expected XML files to be NOT equal") || line.contains("AssertionError")
					|| line.contains("expected [") || line.contains("Expected child")
					|| line.contains("Expected element") || line.contains("Expected attribute");

			if (isNewStart) {
				if (buffer.length() > 0) {
					Map<String, Object> parsed = parseSingleDiff(buffer.toString(), seen);
					if (parsed != null)
						diffs.add(parsed);
					buffer.setLength(0);
				}
				inside = true;
			}

			if (inside)
				buffer.append(line).append("\n");
		}

		if (buffer.length() > 0) {
			Map<String, Object> parsed = parseSingleDiff(buffer.toString(), seen);
			if (parsed != null)
				diffs.add(parsed);
		}

		return diffs;
	}

//	public Set<Map<String, Object>> parseCucumberJson(File jsonFile,
//			Map<String, Map<String, Pair<List<String>, List<String>>>> exampleLookup,
//			Map<String, String> featureFilePathMap) throws IOException {
//
//		Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();
//		List<Map<String, Object>> features = objectMapper.readValue(jsonFile, List.class);
//
//		for (Map<String, Object> feature : features) {
//			String featureUri = (String) feature.get("uri");
//			String featureFileName = new File(featureUri).getName();
//			List<Map<String, Object>> elements = (List<Map<String, Object>>) feature.get("elements");
//
//			if (elements == null)
//				continue;
//
//			// 🔑 Collect Background steps
//			List<Map<String, Object>> backgroundSteps = new ArrayList<>();
//			for (Map<String, Object> element : elements) {
//				if ("background".equalsIgnoreCase((String) element.get("type"))) {
//					List<Map<String, Object>> steps = (List<Map<String, Object>>) element.get("steps");
//					if (steps != null)
//						backgroundSteps.addAll(steps);
//				}
//			}
//
//			for (Map<String, Object> element : elements) {
//				if ("background".equalsIgnoreCase((String) element.get("type"))) {
//					continue; // ✅ skip treating background as scenario
//				}
//
//				String scenarioName = (String) element.get("name");
//				String originalId = (String) element.get("id");
//				String id = originalId;
//
//				// 🔑 Fix example indices
//				if (originalId != null && originalId.contains(";;")) {
//					int splitIndex = originalId.lastIndexOf(";;");
//					String scenarioNamePart = originalId.substring(0, splitIndex);
//					String exampleIndexPart = originalId.substring(splitIndex + 2);
//					try {
//						int index = Integer.parseInt(exampleIndexPart.trim());
//						id = scenarioNamePart + ";;" + (index - 1);
//					} catch (NumberFormatException ignored) {
//					}
//				}
//
//				String exampleIndex = "";
//				if (id != null && id.contains(";;")) {
//					String[] parts = id.split(";;");
//					if (parts.length > 1)
//						exampleIndex = parts[1].trim();
//				}
//
//				String fullScenarioName = scenarioName;
//				if (!exampleIndex.isEmpty()) {
//					fullScenarioName += " [example #" + exampleIndex + "]";
//				}
//
//				// 🔑 Merge background + scenario steps
//				List<Map<String, Object>> steps = new ArrayList<>(backgroundSteps);
//				List<Map<String, Object>> scenarioSteps = (List<Map<String, Object>>) element.get("steps");
//				if (scenarioSteps != null)
//					steps.addAll(scenarioSteps);
//
//				boolean hasFailed = false;
//				boolean hasUndefined = false;
//				List<String> errors = new ArrayList<>();
//				List<String> parsedDifferences = new ArrayList<>();
//				Set<String> undefinedStepsSeen = new HashSet<>(); // 🚀 Track undefined steps per scenario
//
//				for (Map<String, Object> step : steps) {
//					Map<String, Object> result = (Map<String, Object>) step.get("result");
//					String keyword = step.get("keyword") != null ? step.get("keyword").toString() : "";
//					String name = step.get("name") != null ? step.get("name").toString() : "";
//					String stepName = keyword + name;
//
//					if (result != null) {
//						String status = (String) result.get("status");
//
//						if ("failed".equalsIgnoreCase(status)) {
//							hasFailed = true;
//							String errorMessage = (String) result.get("error_message");
//							if (errorMessage != null) {
//								String trimmedError = errorMessage.split("\tat")[0].trim();
//								trimmedError = trimmedError.replaceAll("java\\.lang\\.AssertionError:\\s*", "")
//										.replaceAll("[\\r\\n]+", " ").trim();
//
//								String[] parts = trimmedError.split("Differences:", 2);
//								errors.add(parts[0].trim());
//
//								if (parts.length > 1) {
//									String differencesSection = parts[1].trim();
//									Pattern pattern = Pattern.compile("(?=\\b[Ee]xpected )");
//									Matcher matcher = pattern.matcher(differencesSection);
//									int lastIndex = 0;
//									while (matcher.find()) {
//										if (lastIndex != matcher.start()) {
//											String diff = differencesSection.substring(lastIndex, matcher.start())
//													.trim();
//											if (!diff.isEmpty()
//													&& !diff.matches("(?i)^expected \\[.*\\] but found \\[.*\\]$")) {
//												parsedDifferences.add(diff);
//											}
//										}
//										lastIndex = matcher.start();
//									}
//									String lastDiff = differencesSection.substring(lastIndex).trim();
//									if (!lastDiff.isEmpty()
//											&& !lastDiff.matches("(?i)^expected \\[.*\\] but found \\[.*\\]$")) {
//										parsedDifferences.add(lastDiff);
//									}
//								}
//							}
//
//						} else if ("undefined".equalsIgnoreCase(status)) {
//							hasUndefined = true;
//							// ✅ Only add if not already recorded
//							if (undefinedStepsSeen.add(stepName)) {
//								errors.add(
//										"Step undefined: " + stepName + " (check glue packages or step definitions)");
//							}
//
//						} else if ("skipped".equalsIgnoreCase(status) && hasUndefined) {
//							// ✅ Always keep skipped steps, even if duplicates
//							errors.add("Step skipped: " + stepName + " (due to previous undefined step)");
//						}
//					}
//				}
//
//				// Build scenario map (no skip classification here!)
//				Map<String, Object> scenarioMap = new LinkedHashMap<>();
//				scenarioMap.put("featureFileName", featureFileName);
//				scenarioMap.put("scenarioName", fullScenarioName);
//				scenarioMap.put("status", hasUndefined ? "Undefined" : hasFailed ? "Failed" : "Passed");
//				if (!errors.isEmpty()) {
////					scenarioMap.put("errors", errors);
////					if (!parsedDifferences.isEmpty()) {
////						scenarioMap.put("parsedDifferences", parsedDifferences);
////						scenarioMap.put("parsedDiffCount", parsedDifferences.size());
////					}
//					List<String> uniqueErrors = errors.stream().distinct().collect(Collectors.toList());
//					scenarioMap.put("errors", uniqueErrors);
//					if (!parsedDifferences.isEmpty()) {
//						scenarioMap.put("parsedDifferences", parsedDifferences);
//						scenarioMap.put("parsedDiffCount", parsedDifferences.size());
//					}
//				}
//
//				if (id != null && exampleLookup.containsKey(featureFileName)) {
//					Map<String, Pair<List<String>, List<String>>> examples = exampleLookup.get(featureFileName);
//					String lookupId = id.contains(";;") ? id.substring(id.indexOf(';') + 1) : id;
//					if (examples.containsKey(lookupId)) {
//						Pair<List<String>, List<String>> example = examples.get(lookupId);
//						scenarioMap.put("scenarioType", "Scenario Outline");
//						scenarioMap.put("exampleHeader", example.getKey());
//						scenarioMap.put("exampleValues", example.getValue());
//					} else {
//						scenarioMap.put("scenarioType", "Scenario");
//					}
//				} else {
//					scenarioMap.put("scenarioType", "Scenario");
//				}
//
//				executedScenarios.add(scenarioMap);
//			}
//		}
//
//		return executedScenarios;
//	}

	@SuppressWarnings("unchecked")
	public Set<Map<String, Object>> parseCucumberJson(File jsonFile,
			Map<String, Map<String, Pair<List<String>, List<String>>>> exampleLookup,
			Map<String, String> featureFilePathMap) throws IOException {

		Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();
		List<Map<String, Object>> features = objectMapper.readValue(jsonFile, List.class);

		for (Map<String, Object> feature : features) {
			String featureUri = (String) feature.get("uri");
			String featureFileName = new File(featureUri).getName();
			List<Map<String, Object>> elements = (List<Map<String, Object>>) feature.get("elements");

			if (elements == null)
				continue;

			// Collect Background steps
			List<Map<String, Object>> backgroundSteps = new ArrayList<>();
			for (Map<String, Object> element : elements) {
				if ("background".equalsIgnoreCase((String) element.get("type"))) {
					List<Map<String, Object>> steps = (List<Map<String, Object>>) element.get("steps");
					if (steps != null)
						backgroundSteps.addAll(steps);
				}
			}

			for (Map<String, Object> element : elements) {
				if ("background".equalsIgnoreCase((String) element.get("type"))) {
					continue; // skip treating background as scenario
				}

				String scenarioName = (String) element.get("name");
				String originalId = (String) element.get("id");
				String id = originalId;

				// Fix example indices
				if (originalId != null && originalId.contains(";;")) {
					int splitIndex = originalId.lastIndexOf(";;");
					String scenarioNamePart = originalId.substring(0, splitIndex);
					String exampleIndexPart = originalId.substring(splitIndex + 2);
					try {
						int index = Integer.parseInt(exampleIndexPart.trim());
						id = scenarioNamePart + ";;" + (index - 1);
					} catch (NumberFormatException ignored) {
					}
				}

				String exampleIndex = "";
				if (id != null && id.contains(";;")) {
					String[] parts = id.split(";;");
					if (parts.length > 1)
						exampleIndex = parts[1].trim();
				}

				String fullScenarioName = scenarioName;
				if (!exampleIndex.isEmpty()) {
					fullScenarioName += " [example #" + exampleIndex + "]";
				}

				// Merge background + scenario steps
				List<Map<String, Object>> steps = new ArrayList<>(backgroundSteps);
				List<Map<String, Object>> scenarioSteps = (List<Map<String, Object>>) element.get("steps");
				if (scenarioSteps != null)
					steps.addAll(scenarioSteps);

				boolean hasFailed = false;
				boolean hasUndefined = false;
				List<String> errors = new ArrayList<>();
				List<String> parsedDifferences = new ArrayList<>();
				Set<String> undefinedStepsSeen = new HashSet<>(); // track undefined steps per scenario

				// We'll try to compute start/finish/duration
				Long explicitStartMillis = extractMillisFromElement(element, "start_timestamp", "start", "started_at",
						"started", "timestamp");
				Long explicitEndMillis = extractMillisFromElement(element, "end_timestamp", "end", "finished_at",
						"finished", "stop_timestamp");
				Long explicitDurationMs = extractDurationMsFromElement(element, "duration");

				// fallback: aggregate from step-level durations / timestamps
				Long stepStartMillis = null;
				Long stepEndMillis = null;
				long sumStepDurNs = 0L;
				boolean anyStepDuration = false;

				for (Map<String, Object> step : steps) {
					// Step timestamps/duration handling (defensive)
					Long stepTimestamp = extractMillisFromElement(step, "start_timestamp", "start", "timestamp",
							"started_at", "started");
					if (stepTimestamp != null) {
						if (stepStartMillis == null || stepTimestamp < stepStartMillis)
							stepStartMillis = stepTimestamp;
						if (stepEndMillis == null || stepTimestamp > stepEndMillis)
							stepEndMillis = stepTimestamp;
					}
					// some cucumber variants put duration under result.duration (often in ns)
					Map<String, Object> result = (Map<String, Object>) step.get("result");
					if (result != null) {
						Object durObj = result.get("duration");
						if (durObj != null) {
							Long durNs = parseNumberToLong(durObj);
							if (durNs != null) {
								sumStepDurNs += durNs;
								anyStepDuration = true;
							}
						}
					}

					String keyword = step.get("keyword") != null ? step.get("keyword").toString() : "";
					String name = step.get("name") != null ? step.get("name").toString() : "";
					String stepName = keyword + name;

					if (result != null) {
						String status = (String) result.get("status");

						if ("failed".equalsIgnoreCase(status)) {
							hasFailed = true;
							String errorMessage = (String) result.get("error_message");
							if (errorMessage != null) {
								String trimmedError = errorMessage.split("\tat")[0].trim();
								trimmedError = trimmedError.replaceAll("java\\.lang\\.AssertionError:\\s*", "")
										.replaceAll("[\\r\\n]+", " ").trim();

								String[] parts = trimmedError.split("Differences:", 2);
								errors.add(parts[0].trim());

								if (parts.length > 1) {
									String differencesSection = parts[1].trim();
									Pattern pattern = Pattern.compile("(?=\\b[Ee]xpected )");
									Matcher matcher = pattern.matcher(differencesSection);
									int lastIndex = 0;
									while (matcher.find()) {
										if (lastIndex != matcher.start()) {
											String diff = differencesSection.substring(lastIndex, matcher.start())
													.trim();
											if (!diff.isEmpty()
													&& !diff.matches("(?i)^expected \\[.*\\] but found \\[.*\\]$")) {
												parsedDifferences.add(diff);
											}
										}
										lastIndex = matcher.start();
									}
									String lastDiff = differencesSection.substring(lastIndex).trim();
									if (!lastDiff.isEmpty()
											&& !lastDiff.matches("(?i)^expected \\[.*\\] but found \\[.*\\]$")) {
										parsedDifferences.add(lastDiff);
									}
								}
							}

						} else if ("undefined".equalsIgnoreCase(status)) {
							hasUndefined = true;
							if (undefinedStepsSeen.add(stepName)) {
								errors.add(
										"Step undefined: " + stepName + " (check glue packages or step definitions)");
							}

						} else if ("skipped".equalsIgnoreCase(status) && hasUndefined) {
							errors.add("Step skipped: " + stepName + " (due to previous undefined step)");
						}
					}
				} // end steps loop

				// Decide final start/finish/duration using best available data:
				Long finalStartMillis = explicitStartMillis != null ? explicitStartMillis : stepStartMillis;
				Long finalEndMillis = explicitEndMillis != null ? explicitEndMillis : stepEndMillis;
				Long finalDurationMs = null;

				// explicit duration in element (maybe in ms or ns)
				if (explicitDurationMs != null) {
					finalDurationMs = explicitDurationMs;
				} else if (anyStepDuration) {
					// sumStepDurNs likely in nanoseconds -> convert to ms
					finalDurationMs = sumStepDurNs / 1_000_000L;
					// if we also have start & end from timestamps, prefer difference (more
					// accurate)
					if (finalStartMillis != null && finalEndMillis != null) {
						long diff = finalEndMillis - finalStartMillis;
						if (diff > 0)
							finalDurationMs = diff;
					}
				} else if (finalStartMillis != null && finalEndMillis != null) {
					finalDurationMs = finalEndMillis - finalStartMillis;
				}

				// Format timestamps to ISO strings (UTC) when present
				String startedIso = null;
				String finishedIso = null;
				if (finalStartMillis != null) {
					startedIso = Instant.ofEpochMilli(finalStartMillis).toString();
				}
				if (finalEndMillis != null) {
					finishedIso = Instant.ofEpochMilli(finalEndMillis).toString();
				}

				// Build scenario map (no skip classification here!)
				Map<String, Object> scenarioMap = new LinkedHashMap<>();
				scenarioMap.put("featureFileName", featureFileName);
				scenarioMap.put("scenarioName", fullScenarioName);
				scenarioMap.put("status", hasUndefined ? "Undefined" : hasFailed ? "Failed" : "Passed");
				if (!errors.isEmpty()) {
					List<String> uniqueErrors = errors.stream().distinct().collect(Collectors.toList());
					scenarioMap.put("errors", uniqueErrors);
					if (!parsedDifferences.isEmpty()) {
						scenarioMap.put("parsedDifferences", parsedDifferences);
						scenarioMap.put("parsedDiffCount", parsedDifferences.size());
					}
				}

				// Example lookup handling (unchanged)
				if (id != null && exampleLookup.containsKey(featureFileName)) {
					Map<String, Pair<List<String>, List<String>>> examples = exampleLookup.get(featureFileName);
					String lookupId = id.contains(";;") ? id.substring(id.indexOf(';') + 1) : id;
					if (examples.containsKey(lookupId)) {
						Pair<List<String>, List<String>> example = examples.get(lookupId);
						scenarioMap.put("scenarioType", "Scenario Outline");
						scenarioMap.put("exampleHeader", example.getKey());
						scenarioMap.put("exampleValues", example.getValue());
					} else {
						scenarioMap.put("scenarioType", "Scenario");
					}
				} else {
					scenarioMap.put("scenarioType", "Scenario");
				}

				executedScenarios.add(scenarioMap);
			}
		}

		return executedScenarios;
	}

	/**
	 * Utility: try a list of candidate keys on the map for a timestamp. Accepts ISO
	 * strings or numeric epoch millis (String/Number). Returns epoch millis if
	 * found, or null.
	 */
	private Long extractMillisFromElement(Map<String, Object> element, String... candidateKeys) {
		if (element == null)
			return null;
		for (String k : candidateKeys) {
			Object o = element.get(k);
			if (o == null)
				continue;
			// If JSON library already produced a Number
			if (o instanceof Number) {
				long val = ((Number) o).longValue();
				// Heuristic: if value looks like seconds (10 digits), convert to millis
				if (val < 1_000_000_00000L) { // < ~Nov 2286 in millis
					// if it's seconds (10 digits), convert
					if (String.valueOf(val).length() <= 10) {
						return val * 1000L;
					} else {
						return val;
					}
				} else {
					return val;
				}
			}
			// If it's a string, try parse
			if (o instanceof String) {
				String s = ((String) o).trim();
				if (s.isEmpty())
					continue;
				// try parse as long
				try {
					long l = Long.parseLong(s);
					if (String.valueOf(l).length() <= 10) {
						return l * 1000L;
					} else {
						return l;
					}
				} catch (NumberFormatException ignored) {
				}
				// try ISO instant parse
				try {
					Instant inst = Instant.parse(s);
					return inst.toEpochMilli();
				} catch (DateTimeParseException ignored) {
				}
				// try common / alternative patterns (yyyy-MM-ddTHH:mm:ss[.SSS][Z/offset])
				try {
					Instant inst = Instant.parse(s.replace(" ", "T"));
					return inst.toEpochMilli();
				} catch (DateTimeParseException ignored) {
				}
				// last-ditch: try to extract digits
				Matcher m = Pattern.compile("(\\d{10,13})").matcher(s);
				if (m.find()) {
					String digits = m.group(1);
					try {
						long l = Long.parseLong(digits);
						if (digits.length() == 10)
							return l * 1000L;
						return l;
					} catch (NumberFormatException ignored) {
					}
				}
			}
		}
		return null;
	}

	/**
	 * Utility: attempt to extract a duration (ms) from the element. Accepts numeric
	 * duration that might be expressed in ms or ns.
	 */
	private Long extractDurationMsFromElement(Map<String, Object> element, String key) {
		if (element == null)
			return null;
		Object o = element.get(key);
		if (o == null)
			return null;
		Long numeric = parseNumberToLong(o);
		if (numeric == null)
			return null;
		// Heuristic: durations are often reported in nanoseconds (very large) or
		// milliseconds
		if (numeric > 10_000_000_000L) { // >> typical ms (10 billion ms = 2777 hours) assume ns
			return numeric / 1_000_000L;
		}
		// if numeric looks like seconds (<= 10_000), treat as seconds -> convert to ms
		if (numeric > 0 && numeric <= 10_000L) {
			return numeric * 1000L;
		}
		// otherwise treat as ms
		return numeric;
	}

	/** Parse different number types to Long (defensive) */
	private Long parseNumberToLong(Object o) {
		if (o == null)
			return null;
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		if (o instanceof String) {
			try {
				return Long.parseLong(((String) o).trim());
			} catch (NumberFormatException ignored) {
				try {
					Double d = Double.parseDouble(((String) o).trim());
					return d.longValue();
				} catch (NumberFormatException ignored2) {
				}
			}
		}
		return null;
	}

	private String customSlug(String scenarioName) {
		// replace spaces and underscores with dash, keep special chars like <, >, :
		return scenarioName.trim().toLowerCase().replaceAll("[ _]+", "-"); // spaces or underscores → dash
	}

	public Map<String, Map<String, Pair<List<String>, List<String>>>> extractExamplesFromFeature(File featureFile)
			throws IOException {

		Map<String, Map<String, Pair<List<String>, List<String>>>> exampleMap = new HashMap<>();

		// normalize newlines
//		String content = Files.readString(featureFile.toPath(), StandardCharsets.UTF_8);
		String content = new String(Files.readAllBytes(featureFile.toPath()), StandardCharsets.UTF_8);
		List<String> lines = Arrays.asList(content.split("\\R"));

		String currentScenarioId = null;
		List<String> headers = new ArrayList<>();
		List<List<String>> valuesList = new ArrayList<>();
		boolean insideExamples = false;

		for (String line : lines) {
			line = line.trim();

			// new Scenario Outline resets state
//	        if (line.startsWith("Scenario Outline:")) {
//	            currentScenarioId = slugify(line.substring("Scenario Outline:".length()).trim());
//	            headers.clear();
//	            valuesList.clear();
//	            insideExamples = false;
//	        }

			// new Scenario Outline resets state
			if (line.startsWith("Scenario Outline:")) {
				String originalName = line.substring("Scenario Outline:".length()).trim();
				currentScenarioId = customSlug(originalName);
				headers.clear();
				valuesList.clear();
				insideExamples = false;
			}

			// detect Examples start
			if (line.startsWith("Examples:")) {
				headers.clear();
				valuesList.clear();
				insideExamples = true;
				continue;
			}

			// collect rows ONLY if inside Examples
			if (insideExamples && line.startsWith("|")) {
				List<String> cells = Arrays.stream(line.split("\\|")).map(String::trim).filter(s -> !s.isEmpty())
						.collect(Collectors.toList());

				if (headers.isEmpty()) {
					headers.addAll(cells);
				} else {
					valuesList.add(cells);
				}
			}

			// if we're in an Examples block and already collected rows, save them
			if (insideExamples && currentScenarioId != null && !headers.isEmpty() && !valuesList.isEmpty()) {
				Map<String, Pair<List<String>, List<String>>> scenarioExamples = new LinkedHashMap<>();
				for (int i = 0; i < valuesList.size(); i++) {
					String key = currentScenarioId + ";;" + (i + 1);
					scenarioExamples.put(key, ImmutablePair.of(headers, valuesList.get(i)));
				}
				exampleMap.put(featureFile.getName(), scenarioExamples);
			}
		}

		return exampleMap;
	}

	private String slugify(String input) {
		return input.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
	}

	private String cleanStackAndFooterLines(String raw) {
		StringBuilder cleaned = new StringBuilder();
		for (String line : raw.split("\r?\n")) {
			line = line.trim();
			if (line.matches("^at .+\\(.+\\.java:\\d+\\)$"))
				continue;
			if (line.matches("^\\d+\\s+(Scenarios|Steps).*"))
				continue;
			if (line.matches("^\\d+m\\d+\\.\\d+s.*"))
				continue;
			if (line.equalsIgnoreCase("Failed scenarios:"))
				continue;
			if (line.startsWith("at ✽."))
				continue;
			if (line.matches("file:///.+\\.feature:\\d+\\s+#.+"))
				continue;
			cleaned.append(line).append("\n");
		}
		return cleaned.toString().trim().replaceAll("\n{2,}", "\n");
	}

	private static final List<DiffPattern> DIFF_PATTERNS;
	static {
		List<DiffPattern> patterns = new ArrayList<>();

		patterns.add(new DiffPattern("Text Mismatch",
				Pattern.compile("Expected text value '(.*?)' but was '(.*?)' - comparing"), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("expected", m.group(1));
					map.put("actual", m.group(2));
					return map;
				}));

		patterns.add(new DiffPattern("Attribute Mismatch",
				Pattern.compile("Expected attribute value '(.*?)' but was '(.*?)' - comparing"), (m, raw) -> {
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("expected", m.group(1));
					data.put("actual", m.group(2));
					data.put("attribute", extractAttributeName(raw));
					return data;
				}));

		patterns.add(new DiffPattern("Tag Mismatch",
				Pattern.compile("Expected element tag name '(.*?)' but was '(.*?)' - comparing"), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("expected", m.group(1));
					map.put("actual", m.group(2));
					return map;
				}));

		patterns.add(new DiffPattern("Child Count Mismatch",
				Pattern.compile("Expected child nodelist length '.*?' but was '.*?' - comparing.*"), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("description", raw);
					return map;
				}));

		patterns.add(new DiffPattern("Missing/Extra Node",
				Pattern.compile("Expected child '.*?' but was '.*?' - comparing.*"), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("description", raw);
					return map;
				}));

		patterns.add(new DiffPattern("False Positive Equality",
				Pattern.compile(
						"Expected XML files to be NOT equal, but got:.*expected \\[true\\] but found \\[false\\]"),
				(m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("description", raw);
					return map;
				}));

		patterns.add(new DiffPattern("Assertion Result",
				Pattern.compile("^(?!.*NOT equal).*expected \\[true\\] but found \\[false\\]"), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("description", raw);
					return map;
				}));

		patterns.add(new DiffPattern("Parsing Error",
				Pattern.compile(".*(invalid xml|parsing error).*", Pattern.CASE_INSENSITIVE), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("description", raw);
					return map;
				}));

		patterns.add(new DiffPattern("Namespace Mismatch",
				Pattern.compile(".*(namespace|xmlns).*", Pattern.CASE_INSENSITIVE), (m, raw) -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("description", raw);
					return map;
				}));

		DIFF_PATTERNS = Collections.unmodifiableList(patterns);
	}

	private Map<String, Object> parseSingleDiff(String raw, Set<String> seen) {
		raw = cleanStackAndFooterLines(raw);
		String normalized = raw.replaceAll("file://.*#", "").replaceAll("\\s+", " ").trim();

		String xpath = extractXPath(normalized);
		String node = extractNodeNameFromXPath(xpath);
		String dedupKey = (xpath != null ? xpath : "") + "|" + node + "|" + normalized;
		if (!seen.add(dedupKey))
			return null;

		for (DiffPattern dp : DIFF_PATTERNS) {
			Matcher matcher = dp.pattern.matcher(normalized);
			if (matcher.find()) {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("xpath", xpath);
				if (xpath != null)
					result.put("node", node);
				result.put("differenceType", dp.type);
				result.putAll(dp.extractor.apply(matcher, raw));
				return result;
			}
		}

		return null;
	}

	private static class DiffPattern {
		String type;
		Pattern pattern;
		BiFunction<Matcher, String, Map<String, Object>> extractor;

		DiffPattern(String type, Pattern pattern, BiFunction<Matcher, String, Map<String, Object>> extractor) {
			this.type = type;
			this.pattern = pattern;
			this.extractor = extractor;
		}
	}

	private String extractXPath(String line) {
		Matcher matcher = Pattern.compile(" at (/[\\w\\[\\]/@\\.\\(\\)]+)").matcher(line);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	private String extractNodeNameFromXPath(String xpath) {
		if (xpath == null || !xpath.contains("/"))
			return xpath;
		String[] parts = xpath.split("/");
		return parts.length > 0 ? parts[parts.length - 1].replaceAll("\\[.*?\\]", "") : xpath;
	}

	private static String extractAttributeName(String line) {
		Pattern pattern = Pattern.compile("@([a-zA-Z0-9_-]+)");
		Matcher matcher = pattern.matcher(line);
		return matcher.find() ? matcher.group(1) : null;
	}

	private Map<String, Object> computeDiffSummary(List<Map<String, Object>> xmlComparisonDetails,
			Set<Map<String, Object>> executedScenarios) {

		int xmlDiffCount = xmlComparisonDetails.stream()
				.mapToInt(detail -> ((List<?>) detail.getOrDefault("differences", Collections.emptyList())).size())
				.sum();

		// Count only real XML diff lines
		int cucumberDiffCount = executedScenarios.stream()
				.flatMap(s -> ((List<?>) s.getOrDefault("parsedDifferences", Collections.emptyList())).stream())
				.filter(Objects::nonNull).map(Object::toString).map(String::trim).filter(s -> !s.isEmpty())
				.filter(line -> line.contains("comparing")) // Real XML diff lines include 'comparing'
				.collect(Collectors.toSet()) // Remove duplicates
				.size();

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("xmlComparatorDiffCount", xmlDiffCount);
		summary.put("cucumberExecutionDiffCount", cucumberDiffCount);
		return summary;
	}

	public String formatRawLog(String log) {
		String[] lines = log.split("\\r?\\n");
		return Arrays.stream(lines).map(line -> removeAnsiCodes(line).trim()).filter(line -> !line.isEmpty())
				.collect(Collectors.joining("\n"));
	}

	private String removeAnsiCodes(String input) {
		return input.replaceAll("\\u001B\\[[;\\d]*m", "");
	}

	public List<Map<String, Object>> runByIds(List<Long> testCaseIds) {
		List<TestCaseDTO> dtos = testCaseRepository.findByIdTCIn(testCaseIds).stream().map(this::convertToDTO)
				.collect(Collectors.toList());
		try {
			return runFromDTO(dtos);
		} catch (Exception e) {
			logger.error("Test Run failed", e);
			return Collections.emptyList();
		}
	}

	private TestCaseDTO convertToDTO(TestCase tc) {
		TestCaseDTO dto = new TestCaseDTO();
		dto.setTcId(tc.getIdTC());
		dto.setTcName(tc.getTcName());
		dto.setDescription(tc.getDescription());
		try {
			dto.setFeatureScenarios(objectMapper.readValue(tc.getFeatureScenarioJson(),
					new TypeReference<List<TestCaseDTO.FeatureScenario>>() {
					}));
		} catch (Exception e) {
			throw new RuntimeException("Invalid featureScenarioJson", e);
		}
		return dto;
	}

	private Map<String, Object> buildXmlComparisonDetails(String inputFile, String outputFile, String featureFileName,
			Set<Map<String, Object>> executedScenarios, ObjectMapper objectMapper) {

		Map<String, Object> xmlDetail = new LinkedHashMap<>();
		xmlDetail.put("inputFile", inputFile);
		xmlDetail.put("outputFile", outputFile);
		xmlDetail.put("featureFileName", featureFileName);

		String scenarioName = executedScenarios.stream().map(s -> (String) s.get("scenarioName"))
				.filter(n -> n.toLowerCase().contains("xml")).findFirst().orElse("XML Comparison");
		xmlDetail.put("scenarioName", scenarioName);

		if (!isXml(inputFile) || !isXml(outputFile)) {
			xmlDetail.put("message", "⚠️ Skipped: Input/Output file is not XML.");
			xmlDetail.put("differences", Collections.emptyList());
			return xmlDetail;
		}

		String fullInputPath = Paths.get("src/main/resources", inputFile).toString();
		String fullOutputPath = Paths.get("src/main/resources", outputFile).toString();

		// 👇 NEW: read & cache exact XML content for this run (only once)
		try {
			if (TestContext.get("inputXmlContent") == null) {
				String inputContent = new String(Files.readAllBytes(Paths.get(fullInputPath)), StandardCharsets.UTF_8);
				TestContext.set("inputXmlContent", inputContent);
			}
		} catch (IOException e) {
			logger.warn("Unable to read input XML: {}", fullInputPath, e);
			TestContext.set("inputXmlContent",
					"❌ Unable to read input XML: " + fullInputPath + " (" + e.getMessage() + ")");
		}
		try {
			if (TestContext.get("outputXmlContent") == null) {
				String outputContent = new String(Files.readAllBytes(Paths.get(fullOutputPath)),
						StandardCharsets.UTF_8);
				TestContext.set("outputXmlContent", outputContent);
			}
		} catch (IOException e) {
			logger.warn("Unable to read output XML: {}", fullOutputPath, e);
			TestContext.set("outputXmlContent",
					"❌ Unable to read output XML: " + fullOutputPath + " (" + e.getMessage() + ")");
		}

		// Existing compare call
		String xmlComparisonResult = XmlComparator.compareXmlFiles(fullInputPath, fullOutputPath);

		if (xmlComparisonResult.contains("✅ XML files are equal.")) {
			xmlDetail.put("message", "✅ XML files are equal.");
			xmlDetail.put("differences", Collections.emptyList());
		} else if (xmlComparisonResult.contains("❌ Error comparing XML files")) {
			xmlDetail.put("message", xmlComparisonResult);
			xmlDetail.put("differences", Collections.emptyList());
		} else {
			List<Map<String, Object>> parsed = extractXmlDifferences(xmlComparisonResult);
			xmlDetail.put("message", "❌ XML files have differences");
			xmlDetail.put("differences", parsed);
		}

		return xmlDetail;
	}

	private boolean isXml(String fileName) {
		return fileName != null && fileName.toLowerCase().endsWith(".xml");
	}

	private List<String> cleanRawCucumberLog(List<String> rawLines) {
		List<String> cleanedLog = new ArrayList<>();
		Set<String> seenBlocks = new HashSet<>();

		List<String> currentBlock = new ArrayList<>();
		boolean insideScenarioBlock = false;
		boolean insideSummaryBlock = false;
		List<String> summaryBlock = new ArrayList<>();

		for (String rawLine : rawLines) {
			// Remove ANSI codes
			String line = rawLine.replaceAll("\u001B\\[[;\\d]*m", "");

			// Start of a new scenario
			if (line.trim().startsWith("Scenario") || line.trim().startsWith("Scenario Outline")) {
				addBlockIfUnique(currentBlock, seenBlocks, cleanedLog);
				currentBlock.clear();
				insideScenarioBlock = true;
			}

			// Failed scenarios summary starts
			if (line.trim().startsWith("Failed scenarios:")) {
				addBlockIfUnique(currentBlock, seenBlocks, cleanedLog);
				currentBlock.clear();
				insideScenarioBlock = false;
				insideSummaryBlock = true;
			}

			// Collect lines into summary block
			if (insideSummaryBlock) {
				if (line.trim().isEmpty()) {
					insideSummaryBlock = false;
					addBlockIfUnique(summaryBlock, seenBlocks, cleanedLog);
					summaryBlock.clear();
				} else {
					summaryBlock.add(line);
				}
				continue;
			}

			// Capture scenario-related lines
			if (insideScenarioBlock || line.trim().startsWith("Given ") || line.trim().startsWith("When ")
					|| line.trim().startsWith("Then ") || line.trim().startsWith("And ")
					|| line.contains("java.lang.AssertionError") || line.contains("at org.testng")
					|| line.contains("at ✽.") || line.startsWith("file:///")) {
				currentBlock.add(line);
			} else if (!line.trim().isEmpty()) {
				// Treat as independent non-scenario line
				addBlockIfUnique(Collections.singletonList(line), seenBlocks, cleanedLog);
			}
		}

		// Final flush
		addBlockIfUnique(currentBlock, seenBlocks, cleanedLog);
		addBlockIfUnique(summaryBlock, seenBlocks, cleanedLog);

		return cleanedLog;
	}

	private void addBlockIfUnique(List<String> block, Set<String> seenBlocks, List<String> cleanedLog) {
		if (block == null || block.isEmpty())
			return;

		String blockString = String.join("\n", block).trim();
		if (!blockString.isEmpty() && seenBlocks.add(blockString)) {
			cleanedLog.addAll(block);
			cleanedLog.add(""); // Add spacing between blocks for readability
		}
	}

}
