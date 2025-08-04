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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.qa.cbcc.dto.ExampleDTO;
import com.qa.cbcc.dto.ScenarioDTO;

@Service
public class FeatureService {

	private static final Logger logger = LoggerFactory.getLogger(FeatureService.class);

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

	private List<ScenarioDTO> cachedScenarios = new ArrayList<>();
	private final Map<String, List<ScenarioDTO>> tagIndex = new ConcurrentHashMap<>();

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
                logger.warn("Branch mismatch (expected '{}', found '{}'). Cleaning and recloning.", gitBranch, currentBranch);
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
	    Git.cloneRepository()
	            .setURI(gitRepoUrl)
	            .setDirectory(repoDir)
	            .setBranch(gitBranch)
	            .setCredentialsProvider(credentials)
	            .call();
	    logger.info("Repo successfully cloned to {}", repoDir.getAbsolutePath());
	}


	private void deleteDirectory(Path path) throws IOException {
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private void cleanRepoExceptGit(Path repoPath) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoPath)) {
			for (Path entry : stream) {
				if (!entry.getFileName().toString().equals(".git")) {
					deleteDirectory(entry);
				}
			}
		}
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
}
