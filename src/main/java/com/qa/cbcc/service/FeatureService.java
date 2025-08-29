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
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
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

    // cache for auto-scanned glue per project path
    private static final Map<String, Set<String>> cachedGluePkgsPerPath = new ConcurrentHashMap<>();

    private List<ScenarioDTO> cachedScenarios = new ArrayList<>();
    private final Map<String, List<ScenarioDTO>> tagIndex = new ConcurrentHashMap<>();

    public String getFeatureSource() { return featureSource; }
    public void setFeatureSource(String featureSource) { this.featureSource = featureSource; }

    public List<ScenarioDTO> getCachedScenarios() { return cachedScenarios; }
    public void setCachedScenarios(List<ScenarioDTO> cachedScenarios) { this.cachedScenarios = cachedScenarios; }

    public synchronized void syncGitAndParseFeatures() throws IOException {
        String featuresPath;

        if ("git".equalsIgnoreCase(featureSource)) {
            cloneRepositoryIfNeeded();
            featuresPath = Paths.get(localCloneDir, gitFeatureSubPath).toString();
        } else {
            featuresPath = localFeatureDir;
        }

        final List<ScenarioDTO> scenarios = new ArrayList<>();
        final Map<String, List<ScenarioDTO>> newIndex = new ConcurrentHashMap<>();

        Files.walk(Paths.get(featuresPath))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".feature"))
            .forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                    Set<String> featureLevelTags = new LinkedHashSet<>();
                    Set<String> tagBuffer = new LinkedHashSet<>();
                    String currentFeature = null;

                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();

                        if (line.startsWith("@")) {
                            List<String> tags = Arrays.asList(line.split("\\s+"));
                            tagBuffer.addAll(tags);
                        } else if (line.startsWith("Feature:")) {
                            currentFeature = line.substring("Feature:".length()).trim();
                            featureLevelTags = new LinkedHashSet<String>(tagBuffer); // capture tags above Feature
                            tagBuffer.clear(); // now tags collected are for scenario level
                        } else if (line.startsWith("Scenario") && line.contains(":")) {
                            String scenarioNameRaw = line.substring(line.indexOf(":") + 1).trim();
                            String scenarioName = cleanScenarioName(scenarioNameRaw);
                            String scenarioType = line.startsWith("Scenario Outline") ? "Scenario Outline" : "Scenario";

                            Set<String> combinedTags = new LinkedHashSet<String>(featureLevelTags);
                            combinedTags.addAll(tagBuffer);

                            ScenarioDTO dto = new ScenarioDTO();
                            dto.setFeature(currentFeature);
                            dto.setScenario(scenarioName);
                            dto.setType(scenarioType);
                            dto.setFile(path.getFileName().toString());
                            dto.setFilePath(path.toAbsolutePath().toString());
                            dto.setTags(new ArrayList<String>(combinedTags));

                            if ("Scenario Outline".equals(scenarioType)) {
                                List<ExampleDTO> examples = extractExamples(lines, i, dto.getTags());
                                dto.setExamples(examples);
                            }

                            scenarios.add(dto);
                            for (String tag : combinedTags) {
                                String key = tag.toLowerCase();
                                List<ScenarioDTO> list = newIndex.get(key);
                                if (list == null) {
                                    list = new ArrayList<ScenarioDTO>();
                                    newIndex.put(key, list);
                                }
                                list.add(dto);
                            }

                            tagBuffer.clear();
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read file: " + path + ", reason: " + e.getMessage(), e);
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
            if (!repoDir.exists() || !gitDir.exists()) {
                logger.info("Repo folder doesn't exist or missing .git. Cloning fresh.");
                FileUtils.deleteQuietly(repoDir);
                cloneFresh(repoDir, credentials);
                return;
            }

            try (Git git = Git.open(repoDir)) {
                String currentBranch = git.getRepository().getBranch();
                logger.info("Current local branch: '{}'", currentBranch);

                if (!currentBranch.equals(gitBranch)) {
                    logger.warn("Branch mismatch (expected '{}', found '{}'). Cleaning and recloning.", gitBranch, currentBranch);
                    git.getRepository().close();
                    FileUtils.deleteDirectory(repoDir);
                    cloneFresh(repoDir, credentials);
                    return;
                }

                PullCommand pull = git.pull();
                if (credentials != null) {
                    pull.setCredentialsProvider(credentials);
                }
                pull.call();
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
        // Avoid passing null credentials provider
        if (credentials != null) {
            Git.cloneRepository()
               .setURI(gitRepoUrl)
               .setDirectory(repoDir)
               .setBranch(gitBranch)
               .setCredentialsProvider(credentials)
               .call();
        } else {
            Git.cloneRepository()
               .setURI(gitRepoUrl)
               .setDirectory(repoDir)
               .setBranch(gitBranch)
               .call();
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

        List<ScenarioDTO> filtered = cachedScenarios.stream().filter(dto -> {
            List<String> tags = dto.getTags().stream().map(String::toLowerCase).collect(Collectors.toList());

            return tagFilters.entrySet().stream().allMatch(entry -> {
                String key = entry.getKey().toLowerCase();
                String value = entry.getValue().toLowerCase();

                String formatted1 = "@" + key + ":" + value;
                String formatted2 = "@" + value;

                return tags.contains(formatted1) || tags.contains(formatted2);
            });
        }).collect(Collectors.toList());

        for (int i = 0; i < filtered.size(); i++) {
            filtered.get(i).setIndextNo(i + 1);
        }

        return filtered;
    }

    private List<ExampleDTO> extractExamples(List<String> lines, int startIndex, List<String> scenarioTags) {
        List<ExampleDTO> examples = new ArrayList<>();
        List<String> headers = null;
        boolean insideExamples = false;
        Set<String> exampleTags = new LinkedHashSet<String>();

        for (int i = startIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // capture tags above Examples (before "Examples:")
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
                    Map<String, String> values = new LinkedHashMap<String, String>();

                    for (int j = 0; j < headers.size(); j++) {
                        String key = headers.get(j);
                        String val = j < columns.size() ? columns.get(j) : "";
                        values.put(key, val);
                    }
                    example.setValues(values);

                    // Only set tags if they differ from scenario-level tags
                    if (!exampleTags.isEmpty() && !exampleTags.equals(new LinkedHashSet<String>(scenarioTags))) {
                        example.setTags(new ArrayList<String>(exampleTags));
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
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.startsWith("-")) {
            name = name.substring(1).trim();
        }
        return name;
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
        Map<String, Object> response = new HashMap<String, Object>();
        Map<String, Object> updatedFields = new HashMap<String, Object>();

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

            response.put("status", Integer.valueOf(200));
            response.put("message", "Git configuration updated successfully.");
            response.put("updatedFields", updatedFields);

        } catch (IOException e) {
            response.put("status", Integer.valueOf(500));
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
            this.refreshInterval = Long.valueOf(props.getProperty("feature.refresh.interval.ms", "300000"));

            this.stepDefProjPath = props.getProperty("step.defs.project-path", "");
            this.gluePackageName = props.getProperty("step.defs.glue", "com.qa.cbcc.steps");

            // multiple paths (comma-separated)
            this.stepDefProjPaths = Arrays.stream(stepDefProjPath.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            // multiple glue packages (comma-separated)
            this.gluePackageNames = Arrays.stream(gluePackageName.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            logger.info("StepDef paths resolved: {}", stepDefProjPaths);
            logger.info("Glue packages resolved: {}", gluePackageNames);

        } catch (IOException e) {
            logger.error("Failed to load git config: {}", e.getMessage(), e);
        }
    }

    /** Returns all configured step project paths (e.g. /myproj). */
    public List<String> getStepDefsProjectPaths() {
        if (stepDefProjPaths != null && !stepDefProjPaths.isEmpty()) {
            return stepDefProjPaths;
        } else {
            List<String> fallback = new ArrayList<String>();
            fallback.add(new File(".").getAbsolutePath());
            return fallback;
        }
    }

    /** Returns compiled class folders for each configured step project. */
    public List<String> getStepDefsFullPaths() {
        if (stepDefProjPaths != null && !stepDefProjPaths.isEmpty()) {
            return stepDefProjPaths.stream()
                    .flatMap(new java.util.function.Function<String, Stream<String>>() {
                        @Override
                        public Stream<String> apply(String path) {
                            return Stream.of(
                                path + File.separator + "target" + File.separator + "classes",
                                path + File.separator + "target" + File.separator + "test-classes"
                            );
                        }
                    })
                    .collect(Collectors.toList());
        } else {
            List<String> list = new ArrayList<String>();
            list.add(new File("target/classes").getAbsolutePath());
            list.add(new File("target/test-classes").getAbsolutePath());
            return list;
        }
    }

    /** Returns glue packages array (from config, or auto-scan with caching). */
    public String[] getGluePackagesArray() throws IOException {
        if (gluePackageNames != null && !gluePackageNames.isEmpty()) {
            return gluePackageNames.toArray(new String[0]);
        }

        Set<String> glueSet = new HashSet<String>();
        for (String path : getStepDefsFullPaths()) {
            glueSet.addAll(scanGlueWithCache(path));
        }

        if (glueSet.isEmpty()) {
            throw new IllegalStateException("No step definition classes found in configured paths");
        }

        List<String> sorted = new ArrayList<String>(glueSet);
        java.util.Collections.sort(sorted);
        return sorted.toArray(new String[0]);
    }

    /** Auto-scans a given path for step definitions, with caching. */
    private Set<String> scanGlueWithCache(String stepDefsPath) throws IOException {
        if (cachedGluePkgsPerPath.containsKey(stepDefsPath)) {
            logger.debug("Using cached glue packages for {}", stepDefsPath);
            return cachedGluePkgsPerPath.get(stepDefsPath);
        }

        File stepDefsDir = new File(stepDefsPath);
        if (!stepDefsDir.exists()) {
            throw new IllegalStateException("Step defs path does not exist: " + stepDefsPath);
        }

        Set<String> pkgs;
        try (URLClassLoader cl = new URLClassLoader(new URL[] { stepDefsDir.toURI().toURL() },
                Thread.currentThread().getContextClassLoader())) {

            Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forClassLoader(cl))
                    .setScanners(Scanners.TypesAnnotated)
                    .addClassLoaders(cl)
            );

            Set<Class<?>> stepDefClasses = new HashSet<Class<?>>();
            stepDefClasses.addAll(reflections.getTypesAnnotatedWith(Given.class));
            stepDefClasses.addAll(reflections.getTypesAnnotatedWith(When.class));
            stepDefClasses.addAll(reflections.getTypesAnnotatedWith(Then.class));
            stepDefClasses.addAll(reflections.getTypesAnnotatedWith(And.class));
            stepDefClasses.addAll(reflections.getTypesAnnotatedWith(But.class));

            pkgs = stepDefClasses.stream()
                    .map(new java.util.function.Function<Class<?>, String>() {
                        @Override
                        public String apply(Class<?> clazz) {
                            Package p = clazz.getPackage();
                            return p == null ? "" : p.getName();
                        }
                    })
                    .collect(Collectors.toSet());
        }

        cachedGluePkgsPerPath.put(stepDefsPath, pkgs);
        return pkgs;
    }
}
