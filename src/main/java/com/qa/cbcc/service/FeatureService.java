package com.qa.cbcc.service;

import com.qa.cbcc.dto.ExampleDTO;
import com.qa.cbcc.dto.ScenarioDTO;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FeatureService {

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
                .filter(path -> path.toString().endsWith(".feature"))
                .forEach(path -> {
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
                                String scenarioType = line.startsWith("Scenario Outline") ? "Scenario Outline" : "Scenario";

                                Set<String> combinedTags = new LinkedHashSet<>(featureLevelTags);
                                combinedTags.addAll(tagBuffer);

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
        boolean useAuth = !gitUsername.isBlank() && !gitPassword.isBlank();
        var credentials = useAuth ? new UsernamePasswordCredentialsProvider(gitUsername, gitPassword) : null;

        try {
            File gitDir = new File(repoDir, ".git");

            if (repoDir.exists() && !gitDir.exists()) {
                deleteDirectory(repoDir.toPath());
            }

            if (!repoDir.exists()) {
                Git.cloneRepository()
                        .setURI(gitRepoUrl)
                        .setDirectory(repoDir)
                        .setBranch(gitBranch)
                        .setCredentialsProvider(credentials)
                        .call();

                if (!new File(repoDir, ".git").exists()) {
                    throw new IOException("Clone failed: .git directory not found in " + repoDir.getAbsolutePath());
                }
                return;
            }

            Git git = Git.open(repoDir);
            String currentBranch = git.getRepository().getBranch();

            if (!currentBranch.equals(gitBranch)) {
                deleteDirectory(repoDir.toPath());
                Git.cloneRepository()
                        .setURI(gitRepoUrl)
                        .setDirectory(repoDir)
                        .setBranch(gitBranch)
                        .setCredentialsProvider(credentials)
                        .call();
            } else {
                git.pull()
                        .setRemoteBranchName(gitBranch)
                        .setCredentialsProvider(credentials)
                        .call();
            }
        } catch (Exception e) {
            throw new IOException("Git error: " + e.getMessage(), e);
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        file.deleteOnExit();
                    }
                });

        if (path.toFile().exists()) {
            throw new IOException("Failed to delete directory: " + path.toAbsolutePath());
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
            if (line.isEmpty()) continue;

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
            if (!val.isEmpty()) result.add(val);
        }
        return result;
    }
}
