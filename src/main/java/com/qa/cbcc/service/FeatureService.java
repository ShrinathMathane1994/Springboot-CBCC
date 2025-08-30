package com.qa.cbcc.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.qa.cbcc.dto.ExampleDTO;
import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.dto.ScenarioDTO;

import io.cucumber.java.en.And;
import io.cucumber.java.en.But;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

@Service
public class FeatureService {

	private static final Logger logger = LoggerFactory.getLogger(FeatureService.class);
	private static final String CONFIG_FILE = "src/main/resources/git-config.properties";

	@Value("${feature.source:local}")
	private String featureSource;

	@Value("${feature.local.path:src/test/resources/features}")
	private String localFeatureDir;

	@Value("${feature.git.repo-url:https://github.com/your-org/your-repo.git}")
	private String gitRepoUrl;

	@Value("${feature.git.clone-dir:features-repo}")
	private String localCloneDir;

	@Value("${feature.git.feature-path:path/to/features}")
	private String gitFeatureSubPath;

	@Value("${feature.git.username:}")
	private String gitUsername;

	@Value("${feature.git.password:}")
	private String gitPassword;

	@Value("${feature.git.branch:main}")
	private String gitBranch;

	@Value("${feature.refresh.interval.ms:300000}")
	private Long refreshInterval;

	@Value("${step.defs.project-path:}")
	private String stepDefProjPath;

	@Value("${step.defs.glue:}")
	private String gluePackageName;

	private List<String> stepDefProjPaths = new ArrayList<>();
	private List<String> gluePackageNames = new ArrayList<>();
	// ✅ Cache for auto-scanned glue per project path
	private static final Map<String, Set<String>> cachedGluePkgsPerPath = new ConcurrentHashMap<>();

	private List<ScenarioDTO> cachedScenarios = new ArrayList<>();
	private final Map<String, List<ScenarioDTO>> tagIndex = new ConcurrentHashMap<>();

	public String getFeatureSource() {
		return featureSource;
	}

	public void setFeatureSource(String featureSource) {
		this.featureSource = featureSource;
	}

	public List<ScenarioDTO> getCachedScenarios() {
		return cachedScenarios;
	}

	public void setCachedScenarios(List<ScenarioDTO> cachedScenarios) {
		this.cachedScenarios = cachedScenarios;
	}

	public synchronized void syncGitAndParseFeatures() throws IOException {
		String featuresPath;

		if (featureSource.equalsIgnoreCase("git")) {
			cloneRepositoryIfNeeded();
			featuresPath = Paths.get(localCloneDir, gitFeatureSubPath).toString();
		} else {
			featuresPath = localFeatureDir;
		}

		List<ScenarioDTO> scenarios = new ArrayList<>();
		Map<String, List<ScenarioDTO>> newIndex = new ConcurrentHashMap<>();

		Files.walk(Paths.get(featuresPath)).filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".feature")).forEach(path -> {
					try {
						List<String> lines = Files.readAllLines(path);
						Set<String> featureLevelTags = new LinkedHashSet<>();
						Set<String> tagBuffer = new LinkedHashSet<>();
						String currentFeature = null;

						for (int i = 0; i < lines.size(); i++) {
							String line = lines.get(i).trim();

//							if (line.startsWith("@")) {
//								List<String> tags = Arrays.asList(line.split("\\s+"));
//								tagBuffer.addAll(tags);
//								if (currentFeature == null) {
//									featureLevelTags.addAll(tags);
//								}
//							} else if (line.startsWith("Feature:")) {
//								currentFeature = line.substring("Feature:".length()).trim();}
							if (line.startsWith("@")) {
								List<String> tags = Arrays.asList(line.split("\\s+"));
								tagBuffer.addAll(tags);
							} else if (line.startsWith("Feature:")) {
								currentFeature = line.substring("Feature:".length()).trim();
								featureLevelTags = new LinkedHashSet<>(tagBuffer); // ← capture tags above the Feature
								tagBuffer.clear(); // ← reset tagBuffer to only capture scenario-level tags
							} else if (line.startsWith("Scenario") && line.contains(":")) {
								String scenarioNameRaw = line.substring(line.indexOf(":") + 1).trim();
								String scenarioName = cleanScenarioName(scenarioNameRaw);
								String scenarioType = line.startsWith("Scenario Outline") ? "Scenario Outline"
										: "Scenario";

								Set<String> combinedTags = new LinkedHashSet<>(featureLevelTags);
								combinedTags.addAll(tagBuffer);

								ScenarioDTO dto = new ScenarioDTO();
								dto.setFeature(currentFeature);
								dto.setScenario(scenarioName);
								dto.setType(scenarioType);
								dto.setFile(path.getFileName().toString());
								dto.setFilePath(path.toAbsolutePath().toString());
								dto.setTags(new ArrayList<>(combinedTags));

//								if ("Scenario Outline".equals(scenarioType)) {
//									List<ExampleDTO> examples = extractExamples(lines, i);
//									dto.setExamples(examples);
//								}

								if ("Scenario Outline".equals(scenarioType)) {
//								     Pass scenario tags so  can compare
									List<ExampleDTO> examples = extractExamples(lines, i, dto.getTags());

									dto.setExamples(examples);
								}

								scenarios.add(dto);
								for (String tag : combinedTags) {
									newIndex.computeIfAbsent(tag.toLowerCase(), k -> new ArrayList<>()).add(dto);
								}

								tagBuffer.clear();
							}
						}
					} catch (IOException e) {
						throw new RuntimeException("Failed to read file: " + path + ", reason: " + e.getMessage());
					}
				});

//		for (int j = 0; j < scenarios.size(); j++) {
//			scenarios.get(j).setIndextNo(j+1);
//		}

		this.cachedScenarios = scenarios;
		this.tagIndex.clear();
		this.tagIndex.putAll(newIndex);
	}

	private void cloneRepositoryIfNeeded() throws IOException {
		File repoDir = new File(localCloneDir);
		File gitDir = new File(repoDir, ".git");

		boolean credentialsProvided = gitUsername != null && !gitUsername.isEmpty();
		UsernamePasswordCredentialsProvider credentials = credentialsProvided
				? new UsernamePasswordCredentialsProvider(gitUsername, gitPassword)
				: null;

		try {
			// If directory doesn't exist, clone fresh
			if (!repoDir.exists() || !gitDir.exists()) {
				logger.info("Repo folder doesn't exist or missing .git. Cloning fresh.");
				FileUtils.deleteQuietly(repoDir);
				cloneFresh(repoDir, credentials);
				return;
			}

			try (Git git = Git.open(repoDir)) {
				String currentBranch = git.getRepository().getBranch();
				logger.info("Current local branch: '{}'", currentBranch);

				// Branch mismatch -> delete and reclone
				if (!currentBranch.equals(gitBranch)) {
					logger.warn("Branch mismatch (expected '{}', found '{}'). Cleaning and recloning.", gitBranch,
							currentBranch);
					git.getRepository().close();
					FileUtils.deleteDirectory(repoDir);
					cloneFresh(repoDir, credentials);
					return;
				}

				// Pull if branch matches
				git.pull().setCredentialsProvider(credentials).call();
				logger.info("Repository updated (pull completed).");

			} catch (Exception e) {
				logger.error("Failed to open or update repo. Re-cloning. Reason: {}", e.getMessage());
				FileUtils.deleteDirectory(repoDir);
				cloneFresh(repoDir, credentials);
			}

		} catch (Exception e) {
			logger.error("Git sync failed: {}", e.getMessage(), e);
			throw new IOException("Git sync failed: " + e.getMessage(), e);
		}
	}

//	private void cloneFresh(File repoDir, UsernamePasswordCredentialsProvider credentials) throws GitAPIException {
//		logger.info("Cloning fresh from {}", gitRepoUrl);
//		Git.cloneRepository().setURI(gitRepoUrl).setDirectory(repoDir).setBranch(gitBranch)
//				.setCredentialsProvider(credentials).call();
//		logger.info("Repo successfully cloned to {}", repoDir.getAbsolutePath());
//	}

	private void cloneFresh(File repoDir, UsernamePasswordCredentialsProvider credentials)
			throws IOException, InterruptedException {
		logger.info("Cloning fresh from {} using cmd", gitRepoUrl);

		List<String> command = new ArrayList<>();
		command.add("cmd");
		command.add("/c");
		String repoUrlWithCreds = gitRepoUrl;
		if (credentials != null && gitRepoUrl.startsWith("http")) {
			int idx = repoUrlWithCreds.indexOf("://") + 3;
			// Remove any username already present in the URL
			int atIdx = repoUrlWithCreds.indexOf("@", idx);
			if (atIdx != -1) {
				repoUrlWithCreds = repoUrlWithCreds.substring(0, idx) + repoUrlWithCreds.substring(atIdx + 1);
			}
			repoUrlWithCreds = repoUrlWithCreds.substring(0, idx) + repoUrlWithCreds.substring(idx);
		}
		command.add(String.format("git clone --branch %s %s \"%s\"", gitBranch, repoUrlWithCreds,
				repoDir.getAbsolutePath()));

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		pb.directory(repoDir.getParentFile());

		Process process = pb.start();
		try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				logger.info(line);
			}
		}
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IOException("Git clone failed with exit code " + exitCode);
		}
		logger.info("Repo successfully cloned to {}", repoDir.getAbsolutePath());
	}

	public List<ScenarioDTO> getScenariosByTags(List<String> tagsToMatch) throws IOException {
		if (cachedScenarios.isEmpty()) {
			syncGitAndParseFeatures();
		}

		List<ScenarioDTO> filtered = cachedScenarios.stream()
				.filter(dto -> tagsToMatch.stream()
						.allMatch(tag -> dto.getTags().stream().anyMatch(t -> t.equalsIgnoreCase("@" + tag))))
				.collect(Collectors.toList());

		for (int i = 0; i < filtered.size(); i++) {
			filtered.get(i).setIndextNo(i + 1);
		}

		return filtered;
	}

	public List<ScenarioDTO> getScenariosByTags(Map<String, String> tagFilters) throws IOException {
		if (cachedScenarios.isEmpty()) {
			syncGitAndParseFeatures();
		}

		// Filter first
		List<ScenarioDTO> filtered = cachedScenarios.stream().filter(dto -> {
			List<String> tags = dto.getTags().stream().map(String::toLowerCase).collect(Collectors.toList());

			// Allow both key:value and raw tag matching
			return tagFilters.entrySet().stream().allMatch(entry -> {
				String key = entry.getKey().toLowerCase();
				String value = entry.getValue().toLowerCase();

				// Check both @key:value and raw @value
				String formatted1 = "@" + key + ":" + value;
				String formatted2 = "@" + value;

				return tags.contains(formatted1) || tags.contains(formatted2);
			});
		}).collect(Collectors.toList());

		// Re-index for the filtered list
		for (int i = 0; i < filtered.size(); i++) {
			filtered.get(i).setIndextNo(i + 1);
		}

		return filtered;
	}

//	private List<ExampleDTO> extractExamples(List<String> lines, int startIndex) {
//		List<ExampleDTO> examples = new ArrayList<>();
//		List<String> headers = null;
//		boolean insideExamples = false;
//
//		for (int i = startIndex + 1; i < lines.size(); i++) {
//			String line = lines.get(i).trim();
//			if (line.isEmpty())
//				continue;
//
//			if (line.toLowerCase().startsWith("examples")) {
//				insideExamples = true;
//				continue;
//			}
//
//			if (insideExamples && line.startsWith("|")) {
//				List<String> columns = extractColumns(line);
//
//				if (headers == null) {
//					headers = columns;
//				} else {
//					ExampleDTO example = new ExampleDTO();
//					example.setIndex(examples.size() + 1);
//					example.setLineNumber(i + 1);
//					Map<String, String> values = new LinkedHashMap<>();
//
//					for (int j = 0; j < headers.size(); j++) {
//						String key = headers.get(j);
//						String val = j < columns.size() ? columns.get(j) : "";
//						values.put(key, val);
//					}
//
//					example.setValues(values);
//					examples.add(example);
//				}
//			} else if (insideExamples) {
//				break;
//			}
//		}
//		return examples;
//	}

	private List<ExampleDTO> extractExamples(List<String> lines, int startIndex, List<String> scenarioTags) {
		List<ExampleDTO> examples = new ArrayList<>();
		List<String> headers = null;
		boolean insideExamples = false;
		Set<String> exampleTags = new LinkedHashSet<>();

		for (int i = startIndex + 1; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (line.isEmpty() || line.startsWith("#"))
				continue;

			// 🔹 Capture tags above Examples (before "Examples:" starts)
			if (line.startsWith("@") && !insideExamples) {
				List<String> tags = Arrays.asList(line.split("\\s+"));
				exampleTags.addAll(tags);
				continue;
			}

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

					// ✅ Only set tags if they differ from scenario-level tags
					if (!exampleTags.isEmpty() && !exampleTags.equals(new LinkedHashSet<>(scenarioTags))) {
						example.setTags(new ArrayList<>(exampleTags));
					}

					examples.add(example);
				}
			} else if (insideExamples) {
				break;
			}
		}
		return examples;
	}

	private String cleanScenarioName(String rawName) {
		if (rawName == null)
			return null;
		String name = rawName.trim();
		if (name.startsWith("-")) {
			name = name.substring(1).trim(); // remove leading dash
		}
		return name;
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

	private Properties loadConfig() throws IOException {
		Properties props = new Properties();
		Path path = Paths.get(CONFIG_FILE);

		if (Files.exists(path)) {
			try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				props.load(reader);
			}
		}

		return props;
	}

	private void saveConfig(Properties props) throws IOException {
		Path path = Paths.get(CONFIG_FILE);

		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			props.store(writer, "Updated Git configuration");
		}
	}

	public GitConfigDTO getGitConfig() {
		GitConfigDTO dto = new GitConfigDTO();
		dto.setSourceType(featureSource);
		dto.setRepoUrl(gitRepoUrl);
		dto.setCloneDir(localCloneDir);
		dto.setGitFeaturePath(gitFeatureSubPath);
		dto.setBranch(gitBranch);
		dto.setUsername(gitUsername);
		dto.setPassword(gitPassword);
		dto.setLocalFeatherPath(localFeatureDir);
		dto.setRefreshInterval(refreshInterval);
		dto.setStepDefsProjectPath(stepDefProjPath);
		dto.setGluePackage(gluePackageName);
		return dto;
	}

	public Map<String, Object> updateGitConfig(GitConfigDTO newConfig) {
		Map<String, Object> response = new HashMap<>();
		Map<String, Object> updatedFields = new HashMap<>();

		try {
			Properties props = loadConfig();

			if (newConfig.getSourceType() != null) {
				props.setProperty("feature.source", newConfig.getSourceType());
				this.featureSource = newConfig.getSourceType();
				updatedFields.put("sourceType", newConfig.getSourceType());
			}

			if (newConfig.getRepoUrl() != null) {
				props.setProperty("feature.git.repo-url", newConfig.getRepoUrl());
				this.gitRepoUrl = newConfig.getRepoUrl();
				updatedFields.put("repoUrl", newConfig.getRepoUrl());
			}

			if (newConfig.getCloneDir() != null) {
				props.setProperty("feature.git.clone-dir", newConfig.getCloneDir());
				this.localCloneDir = newConfig.getCloneDir();
				updatedFields.put("cloneDir", newConfig.getCloneDir());
			}

			if (newConfig.getGitFeaturePath() != null) {
				props.setProperty("feature.git.feature-path", newConfig.getGitFeaturePath());
				this.gitFeatureSubPath = newConfig.getGitFeaturePath();
				updatedFields.put("featurePath", newConfig.getGitFeaturePath());
			}

			if (newConfig.getBranch() != null) {
				props.setProperty("feature.git.branch", newConfig.getBranch());
				this.gitBranch = newConfig.getBranch();
				updatedFields.put("branch", newConfig.getBranch());
			}

			if (newConfig.getUsername() != null) {
				props.setProperty("feature.git.username", newConfig.getUsername());
				this.gitUsername = newConfig.getUsername();
				updatedFields.put("username", newConfig.getUsername());
			}

			if (newConfig.getPassword() != null) {
				props.setProperty("feature.git.password", newConfig.getPassword());
				this.gitPassword = newConfig.getPassword();
				updatedFields.put("password", newConfig.getPassword());
			}

			if (newConfig.getLocalFeatherPath() != null) {
				props.setProperty("feature.local.path", newConfig.getLocalFeatherPath());
				this.localFeatureDir = newConfig.getLocalFeatherPath();
				updatedFields.put("localPath", newConfig.getLocalFeatherPath());
			}

			if (newConfig.getRefreshInterval() != null) {
				props.setProperty("feature.refresh.interval.ms", newConfig.getRefreshInterval().toString());
				updatedFields.put("refreshInterval", newConfig.getRefreshInterval());
			}

			if (newConfig.getStepDefsProjectPath() != null) {
				props.setProperty("step.defs.project-path", newConfig.getStepDefsProjectPath());
				this.stepDefProjPath = newConfig.getStepDefsProjectPath();
				updatedFields.put("stepDefProjectPath", newConfig.getStepDefsProjectPath());
			}

			if (newConfig.getGluePackage() != null) {
				props.setProperty("step.defs.glue", newConfig.getGluePackage());
				this.gluePackageName = newConfig.getGluePackage();
				updatedFields.put("gluePackages", newConfig.getGluePackage());
			}

			saveConfig(props);

			response.put("status", 200);
			response.put("message", "Git configuration updated successfully.");
			response.put("updatedFields", updatedFields);

		} catch (IOException e) {
			response.put("status", 500);
			response.put("message", "Failed to update Git configuration: " + e.getMessage());
		}

		return response;
	}

//	@PostConstruct
//	public void initializeGitPropertiesFromFile() {
//		try {
//			Properties props = loadConfig();
//			this.featureSource = props.getProperty("feature.source", "local");
//			this.gitRepoUrl = props.getProperty("feature.git.repo-url", "");
//			this.localCloneDir = props.getProperty("feature.git.clone-dir", "features-repo");
//			this.gitFeatureSubPath = props.getProperty("feature.git.feature-path", "");
//			this.gitBranch = props.getProperty("feature.git.branch", "main");
//			this.gitUsername = props.getProperty("feature.git.username", "");
//			this.gitPassword = props.getProperty("feature.git.password", "");
//			this.localFeatureDir = props.getProperty("feature.local.path", "src/test/resources/features");
//			this.refreshInterval = Long.parseLong(props.getProperty("feature.refresh.interval.ms", "300000"));
//			this.stepDefProjPath = props.getProperty("step.defs.project-path", "");
//			this.gluePackageName = props.getProperty("step.defs.glue", "com.qa.cbcc.steps");
//			// Optional: Log resolved paths for debugging
////	        logger.info("Initialized Git Config: featureSource={}, featureDir={}, stepDefsPath={}, glue={}",
////	                featureSource, localFeatureDir, stepDefProjPath, gluePackageName);
//		} catch (IOException e) {
//			logger.error("Failed to load git config: {}", e.getMessage());
//		}
//	}

	@PostConstruct
	public void initializeGitPropertiesFromFile() {
		try {
			Properties props = loadConfig();
			this.featureSource = props.getProperty("feature.source", "local");
			this.gitRepoUrl = props.getProperty("feature.git.repo-url", "");
			this.localCloneDir = props.getProperty("feature.git.clone-dir", "features-repo");
			this.gitFeatureSubPath = props.getProperty("feature.git.feature-path", "");
			this.gitBranch = props.getProperty("feature.git.branch", "main");
			this.gitUsername = props.getProperty("feature.git.username", "");
			this.gitPassword = props.getProperty("feature.git.password", "");
			this.localFeatureDir = props.getProperty("feature.local.path", "src/test/resources/features");
			this.refreshInterval = Long.parseLong(props.getProperty("feature.refresh.interval.ms", "300000"));

			this.stepDefProjPath = props.getProperty("step.defs.project-path", "");
			this.gluePackageName = props.getProperty("step.defs.glue", "com.qa.cbcc.steps");

			// 🔹 Support multiple paths (comma-separated)
			this.stepDefProjPaths = Arrays.stream(stepDefProjPath.split(",")).map(String::trim)
					.filter(s -> !s.isEmpty()).collect(Collectors.toList());

			// 🔹 Support multiple glue packages (comma-separated)
			this.gluePackageNames = Arrays.stream(gluePackageName.split(",")).map(String::trim)
					.filter(s -> !s.isEmpty()).collect(Collectors.toList());

			logger.info("✅ StepDef paths resolved: {}", stepDefProjPaths);
			logger.info("✅ Glue packages resolved: {}", gluePackageNames);

		} catch (IOException e) {
			logger.error("❌ Failed to load git config: {}", e.getMessage(), e);
		}
	}

	/**
	 * Returns all configured step project class paths (e.g.
	 * /myproj/target/classes).
	 */

//	public List<String> getStepDefsProjectPaths() {
//		if (stepDefProjPaths != null && !stepDefProjPaths.isEmpty()) {
//			return stepDefProjPaths;
//		} else {
//			return Collections.singletonList(new File(".").getAbsolutePath()); // fallback: current dir
//		}
//	}

	public List<String> getStepDefsProjectPaths() {
		if (stepDefProjPaths != null && !stepDefProjPaths.isEmpty()) {
			if (featureSource.equalsIgnoreCase("git")) {
				String baseDir = System.getProperty("user.dir");
				String fullPath = Paths.get(baseDir, stepDefProjPath).toAbsolutePath().normalize().toString();
				return Collections.singletonList(fullPath);
			}
			return stepDefProjPaths;
		} else {
			return Collections.singletonList(Paths.get(".").toAbsolutePath().normalize().toString()); // fallback:
																										// current dir
		}
	}

	public List<String> getStepDefsFullPaths() {
		if (stepDefProjPaths != null && !stepDefProjPaths.isEmpty()) {
			return stepDefProjPaths.stream()
					.flatMap(path -> Stream.of(path + File.separator + "target" + File.separator + "classes",
							path + File.separator + "target" + File.separator + "test-classes"))
					.collect(Collectors.toList());
		} else {
			List<String> fallback = new ArrayList<>();
			fallback.add(new File("target/classes").getAbsolutePath());
			fallback.add(new File("target/test-classes").getAbsolutePath());
			return fallback;
		}
	}

	/**
	 * Returns glue packages array (from config, or auto-scan with caching).
	 */
	public String[] getGluePackagesArray() throws IOException {
		// ✅ Case 1: explicit glues provided in config
		if (gluePackageNames != null && !gluePackageNames.isEmpty()) {
			return gluePackageNames.toArray(new String[0]);
		}

		// ✅ Case 2: auto-scan across multiple step projects
		Set<String> glueSet = new HashSet<>();
		for (String path : getStepDefsFullPaths()) {
			glueSet.addAll(scanGlueWithCache(path));
		}

		if (glueSet.isEmpty()) {
			throw new IllegalStateException("⚠️ No step definition classes found in configured paths");
		}

		return glueSet.stream().sorted().toArray(String[]::new);
	}

	/**
	 * Auto-scans a given path for step definitions, with caching.
	 */
	private Set<String> scanGlueWithCache(String stepDefsPath) throws IOException {
		// ✅ Return cached result if already scanned
		if (cachedGluePkgsPerPath.containsKey(stepDefsPath)) {
			logger.debug("Using cached glue packages for {}", stepDefsPath);
			return cachedGluePkgsPerPath.get(stepDefsPath);
		}

		File stepDefsDir = new File(stepDefsPath);
		if (!stepDefsDir.exists()) {
			throw new IllegalStateException("Step defs path does not exist: " + stepDefsPath);
		}

		Set<String> pkgs;
		// Build classloader for step project (auto-closed)
		try (URLClassLoader cl = new URLClassLoader(new URL[] { stepDefsDir.toURI().toURL() },
				Thread.currentThread().getContextClassLoader())) {
			// Use Reflections to scan for Cucumber annotations
			Reflections reflections = new Reflections(
					new ConfigurationBuilder().setUrls(ClasspathHelper.forClassLoader(cl))
							.setScanners(Scanners.TypesAnnotated).addClassLoaders(cl));

			Set<Class<?>> stepDefClasses = new HashSet<>();
			stepDefClasses.addAll(reflections.getTypesAnnotatedWith(Given.class));
			stepDefClasses.addAll(reflections.getTypesAnnotatedWith(When.class));
			stepDefClasses.addAll(reflections.getTypesAnnotatedWith(Then.class));
			stepDefClasses.addAll(reflections.getTypesAnnotatedWith(And.class));
			stepDefClasses.addAll(reflections.getTypesAnnotatedWith(But.class));

			pkgs = stepDefClasses.stream().map(clazz -> clazz.getPackage().getName()).collect(Collectors.toSet());
		}

		// ✅ Cache result per path
		cachedGluePkgsPerPath.put(stepDefsPath, pkgs);

		return pkgs;
	}
}
