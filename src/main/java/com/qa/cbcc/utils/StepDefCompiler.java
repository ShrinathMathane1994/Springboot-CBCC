package com.qa.cbcc.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StepDefCompiler {

    private static final Logger logger = LoggerFactory.getLogger(StepDefCompiler.class);

    private static final String TEST_SRC_REL = "src/test/java";
    private static final String OUTPUT_REL = "target/dynamic-stepdefs";
    private static final String DEP_DIR = "target/dependency";
    private static final List<String> REQUIRED_TEST_ARTIFACT_HINTS = Arrays.asList("awaitility", "lombok");

    // Use a bounded pool sized to CPU cores to avoid unbounded thread growth
    private static final int POOL_SIZE = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService COMPILE_EXECUTOR = Executors.newFixedThreadPool(POOL_SIZE,
            r -> {
                Thread t = new Thread(r, "stepdef-compiler-" + UUID.randomUUID().toString());
                t.setDaemon(true);
                return t;
            });

    // Track running compilations per normalized projectPath to dedupe
    private static final ConcurrentMap<String, CompletableFuture<Void>> RUNNING = new ConcurrentHashMap<>();

    private StepDefCompiler() {
        // no instantiation
    }

    /**
     * Asynchronously compile step definitions for given project paths.
     * Returns a CompletableFuture that completes when compilation finishes (or exceptionally if failed).
     * If no files are stale, returns a completed future.
     */
    public static CompletableFuture<Void> compileStepDefsAsync(List<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String projectPath : projectPaths) {
            if (projectPath == null) continue;
            final String normalized = normalizeProjectPath(projectPath);
            CompletableFuture<Void> fut = RUNNING.computeIfAbsent(normalized, p -> {
                CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
                    try {
                        doCompileForProject(p);
                    } catch (RuntimeException re) {
                        // rethrow so future completes exceptionally
                        throw re;
                    }
                }, COMPILE_EXECUTOR).whenComplete((r, t) -> {
                    // remove entry (allow subsequent compiles). keep removal quiet even on exception.
                    RUNNING.remove(p);
                    if (t == null) {
                        logger.debug("Async compile completed for {}", p);
                    } else {
                        logger.warn("Async compile failed for {}: {}", p, t.getMessage());
                    }
                });
                return cf;
            });
            futures.add(fut);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
    }

    /**
     * Blocking compile convenience API (previous behaviour) — waits for completion.
     */
    public static void compileStepDefs(List<String> projectPaths) {
        CompletableFuture<Void> all = compileStepDefsAsync(projectPaths);
        try {
            all.get(); // block until done
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for step-def compilation: {}", ie.getMessage());
        } catch (ExecutionException ee) {
            throw new RuntimeException("StepDef compilation failed", ee.getCause());
        }
    }

    // --- internal helpers --- //

    private static String normalizeProjectPath(String projectPath) {
        try {
            return Paths.get(projectPath).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            // fallback: use raw string if normalization fails
            return projectPath;
        }
    }

    // Synchronous compile logic (executed inside executor)
    private static void doCompileForProject(String projectPath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK required (JavaCompiler not found).");
        }

        File srcDir = new File(projectPath, TEST_SRC_REL);
        if (!srcDir.isDirectory()) {
            logger.info("Skip compile: no test sources at {}", srcDir.getAbsolutePath());
            return;
        }

        File outDir = new File(projectPath, OUTPUT_REL);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("Cannot create output directory " + outDir.getAbsolutePath());
        }

        List<File> sources = collectJavaFilesParallel(srcDir.toPath());
        if (sources.isEmpty()) {
            logger.info("No test sources under {}", srcDir.getAbsolutePath());
            return;
        }

        // incremental: only compile stale files
        final List<File> toCompile = sources.parallelStream()
                .filter(src -> isStale(src, srcDir, outDir))
                .collect(Collectors.toList());

        if (toCompile.isEmpty()) {
            logger.debug("StepDefs up-to-date for {}", projectPath);
            return;
        }

        // Ensure dependencies (can be slow) — do it synchronously here before invoking javac.
        ensureDependenciesCopied(projectPath);

        boolean retried = false;
        while (true) {
            String classpath = buildFullTestClasspath(projectPath);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(toCompile);
                List<String> options = Arrays.asList(
                        "-classpath", classpath,
                        "-processorpath", classpath,
                        "-d", outDir.getAbsolutePath(),
                        "-parameters"
                );
                JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, options, null, units);
                boolean ok = task.call();
                if (ok) {
                    logger.info("Compiled {} StepDef file(s) for {}", toCompile.size(), projectPath);
                    break;
                } else {
                    String diagText = diagnostics.getDiagnostics().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(System.lineSeparator()));
//                    logger.warn("Compilation diagnostics for {}:\n{}", projectPath, diagText);
                    if (!retried && indicatesMissingTestLibs(diagText)) {
                        logger.warn("Missing test libraries detected; attempting dependency copy then retry.");
                        retried = true;
                        ensureDependenciesCopied(projectPath);
                        continue;
                    }
//                    throw new IllegalStateException("StepDef compilation failed:\n" + diagText);
                }
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
//                throw new RuntimeException("Failed compiling step definitions for " + projectPath, ex);
            }
        }
    }

    private static boolean indicatesMissingTestLibs(String diagnostics) {
        String lower = diagnostics == null ? "" : diagnostics.toLowerCase();
        if (REQUIRED_TEST_ARTIFACT_HINTS.stream().anyMatch(lower::contains)) return true;
        return lower.contains("cannot find symbol") && (lower.contains("awaitility") || lower.contains("lombok"));
    }

    private static List<File> collectJavaFilesParallel(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.parallel()
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.warn("Failed to scan java files under {}: {}", root, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static boolean isStale(File javaFile, File srcRoot, File outRoot) {
        File classFile = toClassFile(javaFile, srcRoot, outRoot);
        return !classFile.exists() || javaFile.lastModified() > classFile.lastModified();
    }

    private static File toClassFile(File javaFile, File srcRoot, File outRoot) {
        String rel = srcRoot.toPath().relativize(javaFile.toPath()).toString();
        String classRel = rel.substring(0, rel.length() - 5) + "class";
        return new File(outRoot, classRel);
    }

    private static String buildFullTestClasspath(String projectPath) {
        Set<String> entries = new LinkedHashSet<>();
        String runtimeCp = System.getProperty("java.class.path", "");
        if (!runtimeCp.isEmpty()) entries.addAll(Arrays.asList(runtimeCp.split(File.pathSeparator)));
        addIfExists(entries, projectPath + File.separator + "target" + File.separator + "classes");
        addIfExists(entries, projectPath + File.separator + "target" + File.separator + "test-classes");
        addIfExists(entries, projectPath + File.separator + OUTPUT_REL);
        File depDir = new File(projectPath, DEP_DIR);
        if (depDir.isDirectory()) {
            File[] jars = depDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) for (File j : jars) entries.add(j.getAbsolutePath());
        } else {
            logger.debug("Dependency directory not found: {}", depDir.getAbsolutePath());
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void addIfExists(Set<String> set, String path) {
        File f = new File(path);
        if (f.exists()) set.add(f.getAbsolutePath());
    }

    /**
     * Copy test-scope dependencies (one-time) by invoking mvn dependency:copy-dependencies.
     * This method is synchronous and may take time on first run.
     */
    public static void ensureDependenciesCopied(String projectPath) {
        File depDir = new File(projectPath, DEP_DIR);
        if (depDir.isDirectory()) {
            File[] jars = depDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null && jars.length > 0 && hasRequiredArtifacts(jars)) {
                logger.debug("Dependencies appear present in {}", depDir.getAbsolutePath());
                return;
            }
        }

        String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        List<String> cmd = Arrays.asList(
                mvnCmd,
                "-q",
                "dependency:copy-dependencies",
                "-DincludeScope=test",
                "-DoutputDirectory=" + DEP_DIR
        );
        logger.info("Copying test dependencies (project {}) with: {}", projectPath, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(projectPath));
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) logger.debug("[dep-copy] {}", line);
            }
            int ec = p.waitFor();
            if (ec != 0) logger.warn("dependency:copy-dependencies exited with {}", ec);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while copying dependencies for {}: {}", projectPath, ie.getMessage());
        } catch (IOException ioe) {
            logger.warn("Failed to copy dependencies automatically for {}: {}", projectPath, ioe.getMessage());
        }

        File finalDep = new File(projectPath, DEP_DIR);
        if (!finalDep.isDirectory() || Objects.requireNonNull(finalDep.listFiles((d, n) -> n.endsWith(".jar"))).length == 0) {
            logger.warn("Dependency jars missing in `{}`. Ensure they are copied before running tests.", finalDep.getAbsolutePath());
        }
    }

    private static boolean hasRequiredArtifacts(File[] jars) {
        String lower = Arrays.stream(jars).map(f -> f.getName().toLowerCase()).collect(Collectors.joining(" "));
        return REQUIRED_TEST_ARTIFACT_HINTS.stream().allMatch(lower::contains);
    }

    public static void clean(String projectPath) {
        Path out = new File(projectPath, OUTPUT_REL).toPath();
        if (!Files.exists(out)) return;
        try {
            Files.walk(out)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
            logger.info("Cleaned {}", out);
        } catch (Exception e) {
            logger.warn("Failed cleaning {}", out, e);
        }
    }

    /**
     * Optional helper to shutdown executor at application stop, if desired.
     */
    public static void shutdownExecutorNow() {
        try {
            COMPILE_EXECUTOR.shutdownNow();
        } catch (Exception ignored) {}
    }
}
