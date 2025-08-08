package com.qa.cbcc.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.qa.cbcc.dto.ExampleDTO;
import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.dto.ScenarioDTO;

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
								String scenarioName = line.substring(line.indexOf(":") + 1).trim();
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

								if ("Scenario Outline".equals(scenarioType)) {
									List<ExampleDTO> examples = extractExamples(lines, i);
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

	private void cloneFresh(File repoDir, UsernamePasswordCredentialsProvider credentials) throws GitAPIException {
		logger.info("Cloning fresh from {}", gitRepoUrl);
		Git.cloneRepository().setURI(gitRepoUrl).setDirectory(repoDir).setBranch(gitBranch)
				.setCredentialsProvider(credentials).call();
		logger.info("Repo successfully cloned to {}", repoDir.getAbsolutePath());
	}

	public List<ScenarioDTO> getScenariosByTags(List<String> tagsToMatch) throws IOException {
		if (cachedScenarios.isEmpty()) {
			syncGitAndParseFeatures();
		}

		return cachedScenarios.stream()
				.filter(dto -> tagsToMatch.stream()
						.allMatch(tag -> dto.getTags().stream().anyMatch(t -> t.equalsIgnoreCase("@" + tag))))
				.collect(Collectors.toList());
	}

	public List<ScenarioDTO> getScenariosByTags(Map<String, String> tagFilters) throws IOException {
		if (cachedScenarios.isEmpty()) {
			syncGitAndParseFeatures();
		}

		return cachedScenarios.stream().filter(dto -> {
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
			} else if (insideExamples) {
				break;
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

	private Properties loadConfig() throws IOException {
		Properties props = new Properties();
		Path path = Paths.get(CONFIG_FILE);

		if (Files.exists(path)) {
			try (var reader = Files.newBufferedReader(path)) {
				props.load(reader);
			}
		}

		return props;
	}

	private void saveConfig(Properties props) throws IOException {
		Path path = Paths.get(CONFIG_FILE);

		try (var writer = Files.newBufferedWriter(path)) {
			props.store(writer, "Updated Git configuration");
		}
	}

	public GitConfigDTO getGitConfig() {
		GitConfigDTO dto = new GitConfigDTO();
		dto.setSourceType(featureSource);
		dto.setRepoUrl(gitRepoUrl);
		dto.setCloneDir(localCloneDir);
		dto.setFeaturePath(gitFeatureSubPath);
		dto.setBranch(gitBranch);
		dto.setUsername(gitUsername);
		dto.setPassword(gitPassword);
		dto.setLocalPath(localFeatureDir);
		dto.setRefreshInterval(refreshInterval);
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

			if (newConfig.getFeaturePath() != null) {
				props.setProperty("feature.git.feature-path", newConfig.getFeaturePath());
				this.gitFeatureSubPath = newConfig.getFeaturePath();
				updatedFields.put("featurePath", newConfig.getFeaturePath());
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

			if (newConfig.getLocalPath() != null) {
				props.setProperty("feature.local.path", newConfig.getLocalPath());
				this.localFeatureDir = newConfig.getLocalPath();
				updatedFields.put("localPath", newConfig.getLocalPath());
			}

			if (newConfig.getRefreshInterval() != null) {
				props.setProperty("feature.refresh.interval.ms", newConfig.getRefreshInterval().toString());
				updatedFields.put("refreshInterval", newConfig.getRefreshInterval());
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
		} catch (IOException e) {
			logger.error("Failed to load git config: {}", e.getMessage());
		}
	}

}
