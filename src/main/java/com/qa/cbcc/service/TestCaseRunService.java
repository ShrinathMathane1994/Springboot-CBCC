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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Enumeration;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
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
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRepository;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.utils.StepDefCompiler;
import com.qa.cbcc.utils.TestContext;
import com.qa.cbcc.utils.XmlComparator;

import io.cucumber.core.cli.Main;

@Service
public class TestCaseRunService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseRunService.class);
	private static final AtomicBoolean runCukeInitCalled = new AtomicBoolean(false);

	@Autowired
	private FeatureService featureService;

	@Value("${cleanup.temp.feature:true}")
	private boolean cleanupTempFeature;

	private final TestCaseRepository testCaseRepository;
	private final TestCaseRunHistoryRepository historyRepository;
	private final ObjectMapper objectMapper;
	private final ObjectWriter prettyWriter;

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

	private String findRunCucumberTestsClassName(ClassLoader cl) {
		if (!(cl instanceof URLClassLoader)) {
			return null;
		}
		URLClassLoader urlCl = (URLClassLoader) cl;
		try {
			for (URL url : urlCl.getURLs()) {
				String path = url.getPath();
				if (path == null)
					continue;
				File f = new File(path);
				if (f.isDirectory()) {
					// scan directory tree for RunCucumberTests.class
					Path base = f.toPath();
					try (Stream<Path> walk = Files.walk(base)) {
						Optional<Path> found = walk
								.filter(p -> p.getFileName().toString().equals("RunCucumberTests.class")).findFirst();
						if (found.isPresent()) {
							Path rel = base.relativize(found.get());
							String className = rel.toString().replace(File.separatorChar, '.').replaceAll("\\.class$",
									"");
							return className;
						}
					} catch (IOException ioe) {
						logger.warn("Error scanning directory {} for RunCucumberTests: {}", f, ioe.getMessage(), ioe);
					}
				} else if (f.isFile() && f.getName().endsWith(".jar")) {
					try (JarFile jar = new JarFile(f)) {
						Enumeration<JarEntry> entries = jar.entries();
						while (entries.hasMoreElements()) {
							JarEntry e = entries.nextElement();
							if (e.getName().endsWith("/RunCucumberTests.class")
									|| e.getName().equals("RunCucumberTests.class")) {
								String className = e.getName().replace('/', '.').replaceAll("\\.class$", "");
								return className;
							}
						}
					} catch (IOException ioe) {
						logger.warn("Error scanning jar {} for RunCucumberTests: {}", f.getAbsolutePath(),
								ioe.getMessage(), ioe);
					}
				}
			}
		} catch (Exception ex) {
			logger.warn("Error while searching classloader for RunCucumberTests: {}", ex.getMessage(), ex);
		}
		return null;
	}

	/**
	 * Invoke RunCucumberTests.init() reflectively, but only once per JVM (guarded).
	 * Will throw IOException if the underlying method throws it.
	 */
	private void invokeInitIfPresent(Class<?> runnerClass) throws Exception {
		if (runnerClass == null) {
			logger.debug("No RunCucumberTests class available for init().");
			return;
		}
		// ensure single invocation
		if (!runCukeInitCalled.compareAndSet(false, true)) {
			logger.debug("RunCucumberTests.init() already invoked in this JVM — skipping.");
			return;
		}
		try {
			Method initMethod;
			try {
				initMethod = runnerClass.getMethod("init");
			} catch (NoSuchMethodException nsme) {
				initMethod = runnerClass.getDeclaredMethod("init");
				initMethod.setAccessible(true);
			}
			try {
				initMethod.invoke(null);
				logger.info("RunCucumberTests.init() invoked successfully.");
			} catch (InvocationTargetException ite) {
				Throwable cause = ite.getCause();
				if (cause instanceof IOException)
					throw (IOException) cause;
				throw new RuntimeException("RunCucumberTests.init() failed", cause);
			}
		} catch (NoSuchMethodException | IllegalAccessException e) {
			logger.warn("Could not invoke RunCucumberTests.init(): {}", e.getMessage(), e);
		}
	}

	/**
	 * Invoke RunCucumberTests.destroy() reflectively. Logs exceptions; does not
	 * rethrow.
	 */
	private void invokeDestroyIfPresent(Class<?> runnerClass) {
		if (runnerClass == null) {
			logger.debug("No RunCucumberTests class available for destroy().");
			return;
		}
		try {
			Method destroyMethod;
			try {
				destroyMethod = runnerClass.getMethod("destroy");
			} catch (NoSuchMethodException nsme) {
				destroyMethod = runnerClass.getDeclaredMethod("destroy");
				destroyMethod.setAccessible(true);
			}
			try {
				destroyMethod.invoke(null);
				logger.info("RunCucumberTests.destroy() invoked successfully.");
			} catch (InvocationTargetException ite) {
				Throwable cause = ite.getCause();
				if (cause instanceof SQLException) {
					logger.error("RunCucumberTests.destroy() threw SQLException: {}", cause.getMessage(), cause);
				} else {
					logger.error("RunCucumberTests.destroy() threw exception: {}", cause.getMessage(), cause);
				}
			}
		} catch (NoSuchMethodException | IllegalAccessException e) {
			logger.warn("Could not invoke RunCucumberTests.destroy(): {}", e.getMessage(), e);
		}
	}

    public List<Map<String, Object>> runFromDTO(List<TestCaseDTO> testCases) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (TestCaseDTO testCase : testCases) {
            LocalDateTime executedOn = LocalDateTime.now();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("testCaseId", testCase.getTcId());
            result.put("testCaseName", testCase.getTcName());
            result.put("runOn", executedOn);

            try {
                // Run and let runSingleTestCase() persist success-history
                Map<String, Object> executionResult = runSingleTestCase(testCase);
                result.putAll(executionResult);

            } catch (Exception e) {
                // Capture error result instead of breaking
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

                Map<String, Object> errorDetail = new LinkedHashMap<>();
                errorDetail.put("scenarioName",
                        testCase.getFeatureScenarios().isEmpty() ? "N/A"
                                : testCase.getFeatureScenarios().get(0).getScenarios().isEmpty() ? "N/A"
                                : testCase.getFeatureScenarios().get(0).getScenarios().get(0));
                errorDetail.put("errors", Collections.singletonList(e.getMessage()));
                errorDetail.put("exception", e.getClass().getSimpleName());
                result.put("unexecutedScenarioDetails", Collections.singletonList(errorDetail));
                result.put("diffSummary", Collections.emptyMap());

                // Persist failure-history here (only on exception)
                try {
                    TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
                    if (entity != null) {
                        entity.setLastRunOn(executedOn);
                        entity.setLastRunStatus("Execution Error");
                        testCaseRepository.save(entity);
                    }

                    // idempotent save: update existing if present (tolerance +/- 2s)
                    LocalDateTime from = executedOn.minusSeconds(2);
                    LocalDateTime to = executedOn.plusSeconds(2);
                    List<TestCaseRunHistory> existing = historyRepository
                            .findByTestCase_IdTCAndRunTimeBetween(testCase.getTcId(), from, to);
                    TestCaseRunHistory history;
                    if (existing != null && !existing.isEmpty()) {
                        history = existing.get(0);
                        history.setRunStatus("Execution Error");
                        history.setXmlDiffStatus("N/A");
                        history.setOutputLog(objectMapper.writeValueAsString(result));
                        historyRepository.save(history);
                    } else {
                        history = new TestCaseRunHistory();
                        history.setTestCase(entity);
                        history.setRunTime(executedOn);
                        history.setRunStatus("Execution Error");
                        history.setXmlDiffStatus("N/A");
                        history.setOutputLog(objectMapper.writeValueAsString(result));
                        historyRepository.save(history);
                    }
                } catch (Exception dbEx) {
                    logger.error("⚠ Failed to persist run history for TestCase {}: {}", testCase.getTcId(),
                            dbEx.getMessage(), dbEx);
                }
            }

            results.add(result);
            TestContext.clear();
        }

        return results;
    }


    private Map<String, Object> runSingleTestCase(TestCaseDTO testCase) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDateTime executedOn = LocalDateTime.now();
        String inputPath = null;
        String outputPath = null;
        String runId = java.util.UUID.randomUUID().toString();
        TestContext.setRunId(runId);
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
                String featureFilePath = featurePathOpt.map(Path::toString).orElse(null);

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

                for (String scenarioName : fs.getScenarios()) {
                    if (!featureExists)
                        continue;

                    try {
                        featureToPathMap.put(fs.getFeature(), featureFilePath);

                        // normalize key used for extraction
                        String adjustedScenarioName = scenarioName.trim();
                        if (!adjustedScenarioName.startsWith("-")) {
                            adjustedScenarioName = "- " + adjustedScenarioName;
                        }

                        ScenarioBlock sb = extractScenarioBlock(featureFilePath, adjustedScenarioName);
                        if (sb == null || sb.getContent() == null || sb.getContent().trim().isEmpty())
                            continue;

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

                        // ⬇️ Extract tags for THIS scenario only, then put into maps under the plain
                        // scenario name
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
                if(config.getMavenEnv().equalsIgnoreCase("uat")) {
                    System.setProperty("database.user", "POLROUSER");
                    System.setProperty("database.password", "RTqj_JSy7tg5_Ag");
                }
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

                // ✅ Gather stepDef paths (current application only)
//				List<String> stepDefsPaths = Arrays.asList(
//				    "target/test-classes",
//				    "target/classes"
//				);

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
                // try (URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), Thread.currentThread().getContextClassLoader())) {
                // 	logger.info("Running Cucumber with argv: {}", Arrays.toString(argv));
                // 	Main.run(argv, classLoader);
                // }

                URLClassLoader classLoader = null;
                Class<?> runnerClass = null;
                String runnerClassName = null;
                ClassLoader originalContext = Thread.currentThread().getContextClassLoader();

                try {
                    classLoader = new URLClassLoader(urls.toArray(new URL[0]), originalContext);
                    Thread.currentThread().setContextClassLoader(classLoader);
//---For Scanning the RunCucumberTests Class---//
//					try {
//						// Try simple first
//						runnerClass = classLoader.loadClass("RunCucumberTests");
//						runnerClassName = runnerClass.getName();
//					} catch (ClassNotFoundException e) {
//						runnerClassName = findRunCucumberTestsClassName(classLoader);
//						if (runnerClassName != null) {
//							runnerClass = classLoader.loadClass(runnerClassName);
//						}
//					}

                    try {
                        runnerClass = classLoader.loadClass("RunCucumberTests");
                        runnerClassName = "RunCucumberTests"; // default package
                    } catch (ClassNotFoundException e) {
                        logger.info("RunCucumberTests not found in classpath — skipping init/destroy hooks.");
                    }

                    // Run init once
                    invokeInitIfPresent(runnerClass);

                    // Capture cucumber output
                    System.setOut(new PrintStream(baos));
                    logger.info("Running Cucumber with argv: {}", Arrays.toString(argv));
                    Main.run(argv, classLoader);

                    exampleMap = extractExamplesFromFeature(featureFile);

                } finally {
                    System.out.flush();
                    System.setOut(originalOut);

                    try {
                        if (runnerClass == null && runnerClassName != null && classLoader != null) {
                            runnerClass = classLoader.loadClass(runnerClassName);
                        }
                        invokeDestroyIfPresent(runnerClass);
                    } finally {
                        Thread.currentThread().setContextClassLoader(originalContext);
                        if (classLoader != null) {
                            try {
                                classLoader.close();
                            } catch (IOException e) {
                                logger.warn("Failed to close URLClassLoader: {}", e.getMessage(), e);
                            }
                        }
                    }
                }

                // 🔍 Log the resolved classpath entries
//					logger.info("StepDefs + Dependency classpath URLs:");
//					for (URL url : urls) {
//						logger.info("  {}", url);
//					}

                // ✅ Dynamic Hybrid classloader (inherits app deps + adds stepDefs + jars)
//				try (URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]),
//						Thread.currentThread().getContextClassLoader())) {
//
//					logger.info("Running Cucumber with argv: {}", Arrays.toString(argv));
//					Main.run(argv, classLoader);
//				}
                ;
                // ✅ Extract from the temp feature file BEFORE deleting it
                // exampleMap = extractExamplesFromFeature(featureFile);

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

            Set<String> declaredScenarioNames = testCase.getFeatureScenarios().stream()
                    .flatMap(fs -> fs.getScenarios().stream()).map(String::trim).collect(Collectors.toSet());

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
                            .filter(fs -> fs.getScenarios() != null && fs.getScenarios().contains(scenarioName))
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
            inputPath = entity != null ? entity.getInputFile() : null;
            outputPath = entity != null ? entity.getOutputFile() : null;

// --- PRELOAD XML CONTENTS into run-scoped cache (read only once per run) ---
            try {
                if (inputPath != null) {
                    String cachedIn = TestContext.getXmlContentForRun(runId, inputPath);
                    if (cachedIn == null) {
                        Path inP = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", inputPath);
                        if (Files.exists(inP)) {
                            try {
                                byte[] inBytes = Files.readAllBytes(inP);
                                String inContent = new String(inBytes, StandardCharsets.UTF_8);
                                TestContext.setXmlContentForRun(runId, inputPath, inContent);
                                // keep thread-local compatibility for code that still reads these keys
                                TestContext.set("inputXmlContent", inContent);
                            } catch (IOException e) {
                                logger.warn("Unable to read input XML for run {}: {}", runId, inP, e);
                                TestContext.setXmlContentForRun(runId, inputPath,
                                        "❌ Unable to read input XML: " + inP + " (" + e.getMessage() + ")");
                                TestContext.set("inputXmlContent",
                                        "❌ Unable to read input XML: " + inP + " (" + e.getMessage() + ")");
                            }
                        }
                    } else {
                        // populate thread-local compatibility
                        TestContext.set("inputXmlContent", cachedIn);
                    }
                }

                if (outputPath != null) {
                    String cachedOut = TestContext.getXmlContentForRun(runId, outputPath);
                    if (cachedOut == null) {
                        Path outP = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", outputPath);
                        if (Files.exists(outP)) {
                            try {
                                byte[] outBytes = Files.readAllBytes(outP);
                                String outContent = new String(outBytes, StandardCharsets.UTF_8);
                                TestContext.setXmlContentForRun(runId, outputPath, outContent);
                                TestContext.set("outputXmlContent", outContent);
                            } catch (IOException e) {
                                logger.warn("Unable to read output XML for run {}: {}", runId, outP, e);
                                TestContext.setXmlContentForRun(runId, outputPath,
                                        "❌ Unable to read output XML: " + outP + " (" + e.getMessage() + ")");
                                TestContext.set("outputXmlContent",
                                        "❌ Unable to read output XML: " + outP + " (" + e.getMessage() + ")");
                            }
                        }
                    } else {
                        // populate thread-local compatibility
                        TestContext.set("outputXmlContent", cachedOut);
                    }
                }
            } catch (Exception e) {
                logger.warn("Error preloading XML contents for run {}: {}", runId, e.getMessage(), e);
            }

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

            // Determine if any comparison was skipped (checks "status" or message text)
            boolean skippedComparisonExists = false;
            if (xmlComparisonDetails != null) {
                for (Map<String, Object> m : xmlComparisonDetails) {
                    if (m == null) continue;
                    Object statusObj = m.get("status");
                    if (statusObj instanceof String && "SKIPPED".equals(statusObj)) {
                        skippedComparisonExists = true;
                        break;
                    }
                    Object msgObj = m.get("message");
                    if (msgObj instanceof String) {
                        String msg = (String) msgObj;
                        if (msg.startsWith("⚠️ Skipped") || msg.toLowerCase().contains("skipped")) {
                            skippedComparisonExists = true;
                            break;
                        }
                    }
                }
            }

            // ✅ Decide comparisonStatus
            String comparisonStatus;
            if (skippedComparisonExists) {
                // If any comparison was skipped (missing/non-XML/unreadable), treat comparison as N/A
                comparisonStatus = "N/A";
            } else if (!allUnexecutedScenarios.isEmpty()) {
                if (xmlComparisonDetails != null && !xmlComparisonDetails.isEmpty()
                        && xmlComparisonDetails.stream()
                        .anyMatch(m -> !"✅ XML files are equal.".equals(m.get("message")))) {
                    comparisonStatus = "Partially Unexecuted";
                } else {
                    comparisonStatus = "N/A";
                }
            } else if (trulyExecutedScenarios.isEmpty() && !missingFeatures.isEmpty()) {
                comparisonStatus = null;
            } else if (xmlComparisonDetails != null && xmlComparisonDetails.stream()
                    .anyMatch(m -> !"✅ XML files are equal.".equals(m.get("message")))) {
                comparisonStatus = "Mismatched";
            } else {
                comparisonStatus = "Matched";
            }

            // ✅ Derive finalStatus (do NOT automatically convert "N/A" -> "Unexecuted" if TC actually executed)
            String finalStatus;
            if ("Passed".equals(statusByExecution) && "Mismatched".equals(comparisonStatus)) {
                finalStatus = "Discrepancy";
            } else if ("Partially Unexecuted".equals(comparisonStatus)) {
                finalStatus = "Partially Unexecuted";
            } else if ("N/A".equals(comparisonStatus)) {
                // Only mark as Unexecuted when nothing was executed for the TC.
                if (trulyExecutedScenarios == null || trulyExecutedScenarios.isEmpty()) {
                    finalStatus = "Unexecuted";
                } else {
                    // Comparison not applicable (skipped) but TC had executions — preserve execution status
                    finalStatus = statusByExecution;
                }
            } else {
                finalStatus = statusByExecution;
            }

            // Build history object fields (common content)
            TestCaseRunHistory history = new TestCaseRunHistory();
            history.setTestCase(entity);
            history.setRunTime(executedOn);
            history.setRunStatus(finalStatus);
            history.setXmlDiffStatus(comparisonStatus);
            history.setXmlParsedDifferencesJson(prettyWriter.writeValueAsString(xmlComparisonDetails));

            long durationStart = System.currentTimeMillis();

            // Save only scenario names for executed scenarios
            ObjectMapper mapper = new ObjectMapper();
            List<String> executedNames = trulyExecutedScenarios.stream()
                    .map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());
            history.setExecutedScenarios(executedNames.isEmpty() ? null : mapper.writeValueAsString(executedNames));

            // Save only scenario names for unexecuted scenarios
            List<String> unexecutedNames = unexecutedList.stream()
                    .map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());
            history.setUnexecutedScenarios(unexecutedNames.isEmpty() ? null : mapper.writeValueAsString(unexecutedNames));

            // Passed / failed names for run summary
            List<String> passedNames = executedScenarios.stream().filter(s -> "Passed".equals(s.get("status")))
                    .map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());

            List<String> failedNames = executedScenarios.stream().filter(s -> "Failed".equals(s.get("status")))
                    .map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());

            // Build runSummary and outputLogMap (same structure as before)
            Map<String, Object> runSummary = new LinkedHashMap<>();
            runSummary.put("totalExecutedScenarios", trulyExecutedScenarios.size());
            List<Map<String, Object>> passedScenariosDetailed = new ArrayList<>();
            for (Map<String, Object> exec : executedScenarios) {
                if ("Passed".equals(exec.get("status"))) {
                    Map<String, Object> passedEntry = new LinkedHashMap<>();
                    passedEntry.put("scenarioName", exec.get("scenarioName"));
                    passedEntry.put("scenarioType", exec.getOrDefault("scenarioType", "Scenario"));

                    if ("Scenario Outline".equals(exec.get("scenarioType"))) {
                        passedEntry.put("exampleHeader",
                                exec.containsKey("exampleHeader") ? exec.get("exampleHeader") : Collections.emptyList());
                        passedEntry.put("exampleValues",
                                exec.containsKey("exampleValues") ? exec.get("exampleValues") : Collections.emptyList());
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
                    @SuppressWarnings("unchecked")
                    List<String> parsedDiffs = (List<String>) exec.getOrDefault("parsedDifferences", Collections.emptyList());

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

            // Clean raw cucumber output for storage
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

            // Attach run-scoped XML content (legacy thread-local fallback preserved)
            String currentRunId = TestContext.getRunId();
            if (currentRunId != null) {
                history.setInputXmlContent(TestContext.getXmlContentForRun(currentRunId, inputPath));
                history.setOutputXmlContent(TestContext.getXmlContentForRun(currentRunId, outputPath));
            } else {
                history.setInputXmlContent((String) TestContext.get("inputXmlContent"));
                history.setOutputXmlContent((String) TestContext.get("outputXmlContent"));
            }

            // --- idempotent save (success path) ---
            LocalDateTime historyTime = executedOn; // same timestamp used for result/run
            LocalDateTime from = historyTime.minusSeconds(2);
            LocalDateTime to = historyTime.plusSeconds(2);

            // find any existing history around the same run time
            List<TestCaseRunHistory> existing = historyRepository
                    .findByTestCase_IdTCAndRunTimeBetween(testCase.getTcId(), from, to);

            if (existing != null && !existing.isEmpty()) {
                // update the first matching record (tolerant approach)
                TestCaseRunHistory existingHist = existing.get(0);
                existingHist.setRunStatus(finalStatus);
                existingHist.setXmlDiffStatus(comparisonStatus);
                existingHist.setXmlParsedDifferencesJson(prettyWriter.writeValueAsString(xmlComparisonDetails));

                // executed/unexecuted JSON strings (reuse mapper/executedNames/unexecutedNames you already computed)
                existingHist.setExecutedScenarios(executedNames.isEmpty() ? null : mapper.writeValueAsString(executedNames));
                existingHist.setUnexecutedScenarios(unexecutedNames.isEmpty() ? null : mapper.writeValueAsString(unexecutedNames));

                existingHist.setOutputLog(prettyWriter.writeValueAsString(outputLogMap));
                existingHist.setRawCucumberLog(cleanedLog);

                // ensure XML contents are persisted from run-scoped cache if available
                String runIdForRead = TestContext.getRunId();
                if (runIdForRead != null) {
                    existingHist.setInputXmlContent(TestContext.getXmlContentForRun(runIdForRead, inputPath));
                    existingHist.setOutputXmlContent(TestContext.getXmlContentForRun(runIdForRead, outputPath));
                } else {
                    existingHist.setInputXmlContent((String) TestContext.get("inputXmlContent"));
                    existingHist.setOutputXmlContent((String) TestContext.get("outputXmlContent"));
                }

                historyRepository.save(existingHist);
            } else {
                // insert new history (same fields you already set on 'history')
                history.setTestCase(entity);
                history.setRunTime(historyTime);
                history.setRunStatus(finalStatus);
                history.setXmlDiffStatus(comparisonStatus);
                history.setXmlParsedDifferencesJson(prettyWriter.writeValueAsString(xmlComparisonDetails));
                history.setExecutedScenarios(executedNames.isEmpty() ? null : mapper.writeValueAsString(executedNames));
                history.setUnexecutedScenarios(unexecutedNames.isEmpty() ? null : mapper.writeValueAsString(unexecutedNames));
                history.setOutputLog(prettyWriter.writeValueAsString(outputLogMap));
                history.setRawCucumberLog(cleanedLog);

                String runIdForRead = TestContext.getRunId();
                if (runIdForRead != null) {
                    history.setInputXmlContent(TestContext.getXmlContentForRun(runIdForRead, inputPath));
                    history.setOutputXmlContent(TestContext.getXmlContentForRun(runIdForRead, outputPath));
                } else {
                    history.setInputXmlContent((String) TestContext.get("inputXmlContent"));
                    history.setOutputXmlContent((String) TestContext.get("outputXmlContent"));
                }

                historyRepository.save(history);
            }

            // Populate result map (same as before)
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

            if (entity != null) {
                entity.setLastRunOn(executedOn);
                entity.setLastRunStatus(finalStatus);
                testCaseRepository.save(entity);
            }

        } catch (Exception e) {
            // ---------- UPDATED CATCH: idempotent failure save ----------
            logger.error("Execution failed for TestCase {}: {}", testCase.getTcId(), e.getMessage(), e);
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

            Map<String, Object> errorDetail = new LinkedHashMap<>();
            errorDetail.put("scenarioName",
                    testCase.getFeatureScenarios().isEmpty() ? "N/A"
                            : testCase.getFeatureScenarios().get(0).getScenarios().isEmpty() ? "N/A"
                            : testCase.getFeatureScenarios().get(0).getScenarios().get(0));
            errorDetail.put("reason", Collections.singletonList(e.getMessage()));
            errorDetail.put("exception", e.getClass().getSimpleName());

            result.put("unexecutedScenarioDetails", Collections.singletonList(errorDetail));
            result.put("diffSummary", Collections.emptyMap());

            // ✅ Save failure run history (idempotent)
            try {
                TestCase entityForFail = testCaseRepository.findById(testCase.getTcId()).orElse(null);
                if (entityForFail != null) {
                    entityForFail.setLastRunOn(executedOn);
                    entityForFail.setLastRunStatus("Execution Error");
                    testCaseRepository.save(entityForFail);
                }

                // attempt to update existing history in ±2s window
                LocalDateTime from = executedOn.minusSeconds(2);
                LocalDateTime to = executedOn.plusSeconds(2);
                List<TestCaseRunHistory> existingFail = historyRepository
                        .findByTestCase_IdTCAndRunTimeBetween(testCase.getTcId(), from, to);

                if (existingFail != null && !existingFail.isEmpty()) {
                    TestCaseRunHistory existingHist = existingFail.get(0);
                    existingHist.setRunStatus("Execution Error");
                    existingHist.setXmlDiffStatus("N/A");
                    existingHist.setOutputLog(objectMapper.writeValueAsString(result));

                    String runIdForRead = TestContext.getRunId();
                    if (runIdForRead != null) {
                        existingHist.setInputXmlContent(TestContext.getXmlContentForRun(runIdForRead, inputPath));
                        existingHist.setOutputXmlContent(TestContext.getXmlContentForRun(runIdForRead, outputPath));
                    } else {
                        existingHist.setInputXmlContent((String) TestContext.get("inputXmlContent"));
                        existingHist.setOutputXmlContent((String) TestContext.get("outputXmlContent"));
                    }

                    historyRepository.save(existingHist);
                } else {
                    TestCaseRunHistory failHistory = new TestCaseRunHistory();
                    failHistory.setTestCase(entityForFail);
                    failHistory.setRunTime(executedOn);
                    failHistory.setRunStatus("Execution Error");
                    failHistory.setXmlDiffStatus("N/A");
                    failHistory.setOutputLog(objectMapper.writeValueAsString(result));

                    String runIdForRead = TestContext.getRunId();
                    if (runIdForRead != null) {
                        failHistory.setInputXmlContent(TestContext.getXmlContentForRun(runIdForRead, inputPath));
                        failHistory.setOutputXmlContent(TestContext.getXmlContentForRun(runIdForRead, outputPath));
                    } else {
                        failHistory.setInputXmlContent((String) TestContext.get("inputXmlContent"));
                        failHistory.setOutputXmlContent((String) TestContext.get("outputXmlContent"));
                    }

                    historyRepository.save(failHistory);
                }
            } catch (Exception dbEx) {
                logger.error("⚠ Failed to persist run history for TestCase {}: {}", testCase.getTcId(),
                        dbEx.getMessage(), dbEx);
            }
        } finally {
            // ---------- ALWAYS cleanup run-scoped cache and thread-local at the end ----------
            try {
                String rid = TestContext.getRunId();
                if (rid != null) {
                    try {
                        if (inputPath != null) {
                            TestContext.removeXmlContentForRun(rid, inputPath);
                        }
                    } catch (Exception ex) {
                        logger.warn("Failed to remove input xml cache for run {}: {}", rid, ex.getMessage(), ex);
                    }
                    try {
                        if (outputPath != null) {
                            TestContext.removeXmlContentForRun(rid, outputPath);
                        }
                    } catch (Exception ex) {
                        logger.warn("Failed to remove output xml cache for run {}: {}", rid, ex.getMessage(), ex);
                    }
                }
            } catch (Throwable t) {
                logger.warn("Unexpected error while cleaning run-scoped cache: {}", t.getMessage(), t);
            } finally {
                // ensure the thread-local is cleared
                TestContext.clear();
            }
        }

        // return result (method end)
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

		for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
			// --- Feature-level tags ---
			if (fs.getFeatureTags() != null && !fs.getFeatureTags().isEmpty()) {
				out.append(String.join(" ", fs.getFeatureTags()).trim()).append("\n");
			}

			// --- Feature line ---
			out.append("Feature: ").append(testCase.getTcName().trim()).append("\n\n");

			// --- Background (once per feature) ---
			if (fs.getBackgroundBlock() != null && !fs.getBackgroundBlock().isEmpty()) {
				out.append("Background:\n");
				for (String bgLine : fs.getBackgroundBlock()) {
					String trimmed = bgLine.trim();
					if (!trimmed.startsWith("Background:") && !trimmed.isEmpty()) {
						out.append("  ").append(trimmed).append("\n"); // ✅ 2-space indent
					}
				}
				out.append("\n"); // ✅ exactly one blank line after background
			}

			String currentScenarioTitle = null;

			for (ScenarioBlock block : fs.getScenarioBlocks()) {
				String[] lines = block.getContent().split("\n");

				for (int i = 0; i < lines.length; i++) {
					String raw = lines[i];
					String s = raw.trim();
					if (s.isEmpty())
						continue;

					// 🚫 skip raw tags (we re-print them explicitly from DTO maps)
					if (s.startsWith("@"))
						continue;

					// --- Scenario Outline / Scenario ---
					if (s.startsWith("Scenario Outline:") || s.startsWith("Scenario:")) {
						boolean isOutline = s.startsWith("Scenario Outline:");
						String after = s.substring(isOutline ? "Scenario Outline:".length() : "Scenario:".length())
								.trim();
						if (after.startsWith("-"))
							after = after.substring(1).trim();
						currentScenarioTitle = after;

						// scenario-level tags
						if (fs.getScenarioTagsByName() != null) {
							List<String> scenarioTags = fs.getScenarioTagsByName().get(currentScenarioTitle);
							if (scenarioTags != null && !scenarioTags.isEmpty()) {
								out.append(String.join(" ", scenarioTags).trim()).append("\n");
							}
						}

						out.append(isOutline ? "Scenario Outline: " : "Scenario: ").append(after).append("\n");
						continue;
					}

					// --- Examples ---
					if (s.startsWith("Examples:")) {
						if (currentScenarioTitle != null && fs.getExampleTagsByName() != null) {
							List<String> exTags = fs.getExampleTagsByName().get(currentScenarioTitle);
							if (exTags != null && !exTags.isEmpty()) {
								out.append("\n") // ✅ ensure spacing before tags
										.append(String.join(" ", exTags).trim()).append("\n");
							} else {
								out.append("\n"); // ✅ blank line before "Examples:" even without tags
							}
						} else {
							out.append("\n"); // ✅ blank line before "Examples:"
						}
						out.append("Examples:\n");
						continue;
					}

					// --- Tables (indent under Examples) ---
					if (s.startsWith("|")) {
						out.append("  ").append(s).append("\n");
						continue;
					}

					// --- Steps ---
					if (s.matches("^(Given|When|Then|And|But).*")) {
						out.append("  ").append(s).append("\n");
						continue;
					}

					// --- Fallback ---
					out.append(s).append("\n");
				}

				out.append("\n"); // one blank line between scenarios
			}
		}

		// ✅ Write to temp file (trim trailing whitespace globally)
		List<String> cleanedLines = Arrays.stream(out.toString().split("\n")).map(String::trim)
				.collect(Collectors.toList());

		File temp = File.createTempFile("testcase_", ".feature");
		try (BufferedWriter w = new BufferedWriter(new FileWriter(temp))) {
			for (String line : cleanedLines) {
				w.write(line);
				w.newLine();
			}
		}
		return temp;
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

			// 🔑 Collect Background steps
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
					continue; // ✅ skip treating background as scenario
				}

				String scenarioName = (String) element.get("name");
				String originalId = (String) element.get("id");
				String id = originalId;

				// 🔑 Fix example indices
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

				// 🔑 Merge background + scenario steps
				List<Map<String, Object>> steps = new ArrayList<>(backgroundSteps);
				List<Map<String, Object>> scenarioSteps = (List<Map<String, Object>>) element.get("steps");
				if (scenarioSteps != null)
					steps.addAll(scenarioSteps);

				boolean hasFailed = false;
				boolean hasUndefined = false;
				List<String> errors = new ArrayList<>();
				List<String> parsedDifferences = new ArrayList<>();
				Set<String> undefinedStepsSeen = new HashSet<>(); // 🚀 Track undefined steps per scenario

				for (Map<String, Object> step : steps) {
					Map<String, Object> result = (Map<String, Object>) step.get("result");
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
							// ✅ Only add if not already recorded
							if (undefinedStepsSeen.add(stepName)) {
								errors.add(
										"Step undefined: " + stepName + " (check glue packages or step definitions)");
							}

						} else if ("skipped".equalsIgnoreCase(status) && hasUndefined) {
							// ✅ Always keep skipped steps, even if duplicates
							errors.add("Step skipped: " + stepName + " (due to previous undefined step)");
						}
					}
				}

				// Build scenario map (no skip classification here!)
				Map<String, Object> scenarioMap = new LinkedHashMap<>();
				scenarioMap.put("featureFileName", featureFileName);
				scenarioMap.put("scenarioName", fullScenarioName);
				scenarioMap.put("status", hasUndefined ? "Undefined" : hasFailed ? "Failed" : "Passed");
				if (!errors.isEmpty()) {
//					scenarioMap.put("errors", errors);
//					if (!parsedDifferences.isEmpty()) {
//						scenarioMap.put("parsedDifferences", parsedDifferences);
//						scenarioMap.put("parsedDiffCount", parsedDifferences.size());
//					}
					List<String> uniqueErrors = errors.stream().distinct().collect(Collectors.toList());
					scenarioMap.put("errors", uniqueErrors);
					if (!parsedDifferences.isEmpty()) {
						scenarioMap.put("parsedDifferences", parsedDifferences);
						scenarioMap.put("parsedDiffCount", parsedDifferences.size());
					}
				}

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildXmlComparisonDetails(String inputFile, String outputFile, String featureFileName,
                                                          Set<Map<String, Object>> executedScenarios,
                                                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {

        Map<String, Object> xmlDetail = new LinkedHashMap<>();
        xmlDetail.put("inputFile", inputFile);
        xmlDetail.put("outputFile", outputFile);
        xmlDetail.put("featureFileName", featureFileName);

        // derive a scenario name (defensive)
        String scenarioName = "XML Comparison";
        if (executedScenarios != null) {
            for (Map<String, Object> s : executedScenarios) {
                if (s == null) continue;
                Object nameObj = s.get("scenarioName");
                if (nameObj instanceof String) {
                    String name = (String) nameObj;
                    if (name.toLowerCase().contains("xml")) {
                        scenarioName = name;
                        break;
                    }
                }
            }
        }
        xmlDetail.put("scenarioName", scenarioName);

        boolean inputProvided = inputFile != null && !inputFile.trim().isEmpty();
        boolean outputProvided = outputFile != null && !outputFile.trim().isEmpty();

        Path inputPath = inputProvided ? Paths.get("src", "main", "resources", inputFile) : null;
        Path outputPath = outputProvided ? Paths.get("src", "main", "resources", outputFile) : null;

        boolean inputExists = inputPath != null && Files.exists(inputPath);
        boolean outputExists = outputPath != null && Files.exists(outputPath);

        boolean inputIsXml = isXml(inputFile);
        boolean outputIsXml = isXml(outputFile);

        xmlDetail.put("inputExists", inputExists);
        xmlDetail.put("outputExists", outputExists);
        xmlDetail.put("inputReadable", Boolean.FALSE);
        xmlDetail.put("outputReadable", Boolean.FALSE);

        // Both not provided
        if (!inputProvided && !outputProvided) {
            xmlDetail.put("status", "SKIPPED");
            xmlDetail.put("message", "⚠️ Skipped: Both inputFile and outputFile were not provided.");
            xmlDetail.put("differences", Collections.emptyList());
            return xmlDetail;
        }

        // One or both provided but file(s) missing on disk
        if ((inputProvided && !inputExists) || (outputProvided && !outputExists)) {
            StringBuilder msg = new StringBuilder("⚠️ Skipped: ");
            if (inputProvided && !inputExists) {
                msg.append("Input file not found: ").append(inputFile);
            }
            if (inputProvided && !inputExists && outputProvided && !outputExists) {
                msg.append(" ; ");
            }
            if (outputProvided && !outputExists) {
                msg.append("Output file not found: ").append(outputFile);
            }
            xmlDetail.put("status", "SKIPPED");
            xmlDetail.put("message", msg.toString());
            xmlDetail.put("differences", Collections.emptyList());
            return xmlDetail;
        }

        // Non-XML extension cases
        if (!inputIsXml || !outputIsXml) {
            StringBuilder msg = new StringBuilder("⚠️ Skipped: ");
            if (!inputIsXml) {
                msg.append("Input file is not XML: ").append(inputFile);
            }
            if (!inputIsXml && !outputIsXml) {
                msg.append(" ; ");
            }
            if (!outputIsXml) {
                msg.append("Output file is not XML: ").append(outputFile);
            }
            xmlDetail.put("status", "SKIPPED");
            xmlDetail.put("message", msg.toString());
            xmlDetail.put("differences", Collections.emptyList());
            return xmlDetail;
        }

        // Read & cache contents into TestContext (Java 8 safe readAllBytes), prefer run-scoped cache if present
        String runId = null;
        try {
            try {
                runId = (String) TestContext.get("runId"); // fallback if TestContext.getRunId() not present
            } catch (Throwable ignore) {
                // ignore - we'll call TestContext.getRunId() below in a safe way
            }
            try {
                // If TestContext has getRunId() method (recommended), use it
                java.lang.reflect.Method m = TestContext.class.getMethod("getRunId");
                if (m != null) {
                    Object ridObj = m.invoke(null);
                    if (ridObj instanceof String) {
                        runId = (String) ridObj;
                    }
                }
            } catch (NoSuchMethodException nsme) {
                // ignore - older TestContext may not have run-scoped API
            } catch (Throwable t) {
                // ignore reflection errors, proceed using whatever runId we might have
            }
        } catch (Throwable t) {
            // if reflection access fails, we'll continue using thread-local keys only
        }

        // Helper to build a readable error message
        final String unableToReadPrefix = "❌ Unable to read";

        // INPUT read
        if (inputPath != null) {
            try {
                String cachedIn = null;
                if (runId != null) {
                    try {
                        java.lang.reflect.Method getXmlContentForRun = TestContext.class.getMethod("getXmlContentForRun", String.class, String.class);
                        Object cached = getXmlContentForRun.invoke(null, runId, inputPath.toString());
                        if (cached instanceof String) cachedIn = (String) cached;
                    } catch (NoSuchMethodException nsme) {
                        // method absent - fall back to thread-local
                        cachedIn = (String) TestContext.get("inputXmlContent");
                    }
                } else {
                    cachedIn = (String) TestContext.get("inputXmlContent");
                }

                if (cachedIn == null) {
                    byte[] inBytes = Files.readAllBytes(inputPath);
                    String inputContent = new String(inBytes, StandardCharsets.UTF_8);
                    if (runId != null) {
                        try {
                            java.lang.reflect.Method setXmlContentForRun = TestContext.class.getMethod("setXmlContentForRun", String.class, String.class, String.class);
                            setXmlContentForRun.invoke(null, runId, inputPath.toString(), inputContent);
                        } catch (NoSuchMethodException nsme) {
                            // fallback
                            TestContext.set("inputXmlContent", inputContent);
                        }
                    } else {
                        TestContext.set("inputXmlContent", inputContent);
                    }
                }
                xmlDetail.put("inputReadable", Boolean.TRUE);
            } catch (IOException e) {
                logger.warn("Unable to read input XML: {}", inputPath, e);
                if (runId != null) {
                    try {
                        java.lang.reflect.Method setXmlContentForRun = TestContext.class.getMethod("setXmlContentForRun", String.class, String.class, String.class);
                        setXmlContentForRun.invoke(null, runId, (inputPath != null ? inputPath.toString() : inputFile),
                                unableToReadPrefix + " input XML: " + inputPath + " (" + e.getMessage() + ")");
                    } catch (Throwable ignore) {
                        TestContext.set("inputXmlContent", unableToReadPrefix + " input XML: " + inputPath + " (" + e.getMessage() + ")");
                    }
                } else {
                    TestContext.set("inputXmlContent", unableToReadPrefix + " input XML: " + inputPath + " (" + e.getMessage() + ")");
                }
                xmlDetail.put("inputReadable", Boolean.FALSE);
            } catch (Throwable t) {
                logger.warn("Unexpected error while reading input XML {}: {}", inputPath, t.getMessage(), t);
                TestContext.set("inputXmlContent", unableToReadPrefix + " input XML: " + inputPath + " (" + t.getMessage() + ")");
                xmlDetail.put("inputReadable", Boolean.FALSE);
            }
        }

        // OUTPUT read
        if (outputPath != null) {
            try {
                String cachedOut = null;
                if (runId != null) {
                    try {
                        java.lang.reflect.Method getXmlContentForRun = TestContext.class.getMethod("getXmlContentForRun", String.class, String.class);
                        Object cached = getXmlContentForRun.invoke(null, runId, outputPath.toString());
                        if (cached instanceof String) cachedOut = (String) cached;
                    } catch (NoSuchMethodException nsme) {
                        // fallback
                        cachedOut = (String) TestContext.get("outputXmlContent");
                    }
                } else {
                    cachedOut = (String) TestContext.get("outputXmlContent");
                }

                if (cachedOut == null) {
                    byte[] outBytes = Files.readAllBytes(outputPath);
                    String outputContent = new String(outBytes, StandardCharsets.UTF_8);
                    if (runId != null) {
                        try {
                            java.lang.reflect.Method setXmlContentForRun = TestContext.class.getMethod("setXmlContentForRun", String.class, String.class, String.class);
                            setXmlContentForRun.invoke(null, runId, outputPath.toString(), outputContent);
                        } catch (NoSuchMethodException nsme) {
                            // fallback
                            TestContext.set("outputXmlContent", outputContent);
                        }
                    } else {
                        TestContext.set("outputXmlContent", outputContent);
                    }
                }
                xmlDetail.put("outputReadable", Boolean.TRUE);
            } catch (IOException e) {
                logger.warn("Unable to read output XML: {}", outputPath, e);
                if (runId != null) {
                    try {
                        java.lang.reflect.Method setXmlContentForRun = TestContext.class.getMethod("setXmlContentForRun", String.class, String.class, String.class);
                        setXmlContentForRun.invoke(null, runId, (outputPath != null ? outputPath.toString() : outputFile),
                                unableToReadPrefix + " output XML: " + outputPath + " (" + e.getMessage() + ")");
                    } catch (Throwable ignore) {
                        TestContext.set("outputXmlContent", unableToReadPrefix + " output XML: " + outputPath + " (" + e.getMessage() + ")");
                    }
                } else {
                    TestContext.set("outputXmlContent", unableToReadPrefix + " output XML: " + outputPath + " (" + e.getMessage() + ")");
                }
                xmlDetail.put("outputReadable", Boolean.FALSE);
            } catch (Throwable t) {
                logger.warn("Unexpected error while reading output XML {}: {}", outputPath, t.getMessage(), t);
                TestContext.set("outputXmlContent", unableToReadPrefix + " output XML: " + outputPath + " (" + t.getMessage() + ")");
                xmlDetail.put("outputReadable", Boolean.FALSE);
            }
        }

        boolean inputReadable = Boolean.TRUE.equals(xmlDetail.get("inputReadable"));
        boolean outputReadable = Boolean.TRUE.equals(xmlDetail.get("outputReadable"));

        if (!inputReadable || !outputReadable) {
            StringBuilder m = new StringBuilder("⚠️ Skipped: ");
            if (!inputReadable) {
                m.append("Unable to read input file: ").append(inputFile);
            }
            if (!inputReadable && !outputReadable) {
                m.append(" ; ");
            }
            if (!outputReadable) {
                m.append("Unable to read output file: ").append(outputFile);
            }
            xmlDetail.put("status", "SKIPPED");
            xmlDetail.put("message", m.toString());
            xmlDetail.put("differences", Collections.emptyList());
            return xmlDetail;
        }

        // Now safe to compare
        String fullInputPath = inputPath.toString();
        String fullOutputPath = outputPath.toString();

        String xmlComparisonResult;
        try {
            xmlComparisonResult = XmlComparator.compareXmlFiles(fullInputPath, fullOutputPath);
        } catch (Exception e) {
            logger.error("Error comparing XML files {} and {}: {}", fullInputPath, fullOutputPath, e.getMessage(), e);
            xmlDetail.put("status", "ERROR");
            xmlDetail.put("message", "❌ Error comparing XML files: " + e.getMessage());
            xmlDetail.put("differences", Collections.emptyList());
            return xmlDetail;
        }

        if (xmlComparisonResult == null) {
            xmlDetail.put("status", "ERROR");
            xmlDetail.put("message", "❌ XML comparator returned null result.");
            xmlDetail.put("differences", Collections.emptyList());
            return xmlDetail;
        }

        if (xmlComparisonResult.contains("✅ XML files are equal.")) {
            xmlDetail.put("status", "EQUAL");
            xmlDetail.put("message", "✅ XML files are equal.");
            xmlDetail.put("differences", Collections.emptyList());
        } else if (xmlComparisonResult.contains("❌ Error comparing XML files")) {
            xmlDetail.put("status", "ERROR");
            xmlDetail.put("message", xmlComparisonResult);
            xmlDetail.put("differences", Collections.emptyList());
        } else {
            List<Map<String, Object>> parsed = extractXmlDifferences(xmlComparisonResult);
            xmlDetail.put("status", "DIFFER");
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
