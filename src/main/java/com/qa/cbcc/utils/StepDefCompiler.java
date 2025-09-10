package com.qa.cbcc.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StepDefCompiler {

	private static final Logger logger = LoggerFactory.getLogger(StepDefCompiler.class);

	// Timeout for external Maven operations (adjust if needed)
	private static final long MVN_TIMEOUT_SECONDS = 3 * 60; // 3 minutes

	/**
	 * Ensures dependencies are copied into target/dependency. Uses a timeout and
	 * gobblers to avoid process deadlocks.
	 */
	public static void ensureDependenciesCopied() {
		File depDir = new File("target/dependency");

		if (!depDir.exists()) {
			depDir.mkdirs();
		}

		// Skip if already copied
		if (depDir.exists() && depDir.isDirectory() && depDir.list().length > 0) {
			logger.info("Dependencies already available in target/dependency, skipping copy.");
			return;
		}

		String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
		logger.info("Copying dependencies with {} ...", mvnCmd);

		ProcessBuilder pb = new ProcessBuilder(mvnCmd, "dependency:copy-dependencies", "-DoutputDirectory=target/dependency",
				"-DincludeScope=test");
		pb.redirectErrorStream(true);

		Process process = null;
		StreamGobbler gobbler = null;
		try {
			process = pb.start();
			gobbler = new StreamGobbler(process.getInputStream(), s -> logger.info("[maven] {}", s));
			gobbler.start();

			boolean finished = process.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished) {
				logger.error("Maven dependency:copy-dependencies timed out after {}s; destroying process", MVN_TIMEOUT_SECONDS);
				process.destroyForcibly();
				throw new RuntimeException("Timed out running mvn dependency:copy-dependencies");
			}
			int exitCode = process.exitValue();
			if (exitCode != 0) {
				throw new RuntimeException("Maven dependency:copy-dependencies failed with exit code " + exitCode);
			}
			logger.info("Dependencies copied successfully into target/dependency");
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			if (process != null) process.destroyForcibly();
			throw new RuntimeException("Interrupted while running mvn dependency:copy-dependencies", ie);
		} catch (IOException ioe) {
			if (process != null) process.destroyForcibly();
			logger.error("Failed to copy dependencies automatically", ioe);
			throw new RuntimeException("Failed to copy dependencies automatically", ioe);
		} finally {
			if (gobbler != null) {
				try {
					gobbler.join(500);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	/**
	 * Compile step definitions by running 'mvn test-compile' in each project path.
	 * This method keeps original behavior but adds robust process handling.
	 */
	public static void compileStepDefs(List<String> projectPaths) {
		for (String projectPath0 : projectPaths) {
			String projectPath = projectPath0;
			File srcDir = new File(projectPath, "src/test/java");
			File outputDir = new File(projectPath, "target/test-classes");
			File pomFile = new File(projectPath, "pom.xml");

			// If no pom.xml in repo (like client code), fall back to app root
			if (!pomFile.exists()) {
				logger.warn("No pom.xml found in {}, falling back to application root ({})", projectPath, System.getProperty("user.dir"));
				projectPath = System.getProperty("user.dir"); // root of your app
				srcDir = new File(projectPath, "src/test/java");
				outputDir = new File(projectPath, "target/test-classes");
				pomFile = new File(projectPath, "pom.xml");
			}

			List<File> javaFiles = new ArrayList<>();
			if (srcDir.exists()) {
				collectJavaFiles(srcDir, javaFiles);
			}

			boolean needsCompile = false;
			for (File javaFile : javaFiles) {
				File classFile = getClassFileFor(javaFile, srcDir, outputDir);
				if (!classFile.exists() || javaFile.lastModified() > classFile.lastModified()) {
					needsCompile = true;
					break;
				}
			}

			if (!needsCompile) {
				logger.info("StepDefs already up-to-date, skipping compile for {}", projectPath);
				continue;
			}

			String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
			ProcessBuilder pb = new ProcessBuilder(mvnCmd, "test-compile");
			pb.directory(new File(projectPath));
			pb.redirectErrorStream(true);

			Process process = null;
			StreamGobbler gobbler = null;
			try {
				process = pb.start();
				gobbler = new StreamGobbler(process.getInputStream(), s -> logger.info("[maven] {}", s));
				gobbler.start();

				boolean finished = process.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				if (!finished) {
					logger.error("Maven test-compile timed out after {}s for {}, killing process", MVN_TIMEOUT_SECONDS, projectPath);
					process.destroyForcibly();
					throw new RuntimeException("Timed out running mvn test-compile for " + projectPath);
				}
				int exitCode = process.exitValue();
				if (exitCode != 0) {
					throw new RuntimeException("Maven test-compile failed for " + projectPath + ", exit code " + exitCode);
				} else {
					logger.info("Maven test-compile succeeded for {}", projectPath);
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				if (process != null) process.destroyForcibly();
				throw new RuntimeException("Interrupted while running Maven test-compile for " + projectPath, ie);
			} catch (IOException ioe) {
				if (process != null) process.destroyForcibly();
				throw new RuntimeException("Failed to run Maven test-compile for " + projectPath, ioe);
			} finally {
				if (gobbler != null) {
					try {
						gobbler.join(500);
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
				}
			}
		}
	}

	/**
	 * Similar to compileStepDefs but only runs for projects that have a pom.xml.
	 */
	public static void compileStepDefsFromOtherProject(List<String> projectPaths) {
		for (String projectPath : projectPaths) {
			File srcDir = new File(projectPath, "src/test/java");
			File outputDir = new File(projectPath, "target/test-classes");
			File pomFile = new File(projectPath, "pom.xml");
			if (!pomFile.exists()) {
				logger.warn("No pom.xml found in {}, skipping Maven compile.", projectPath);
				continue;
			}
			List<File> javaFiles = new ArrayList<>();
			if (srcDir.exists()) {
				collectJavaFiles(srcDir, javaFiles);
			}

			boolean needsCompile = false;
			for (File javaFile : javaFiles) {
				File classFile = getClassFileFor(javaFile, srcDir, outputDir);
				if (!classFile.exists() || javaFile.lastModified() > classFile.lastModified()) {
					needsCompile = true;
					break;
				}
			}

			if (!needsCompile) {
				logger.info("StepDefs already up-to-date, skipping compile for {}", projectPath);
				continue;
			}

			String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
			ProcessBuilder pb = new ProcessBuilder(mvnCmd, "test-compile");
			pb.directory(new File(projectPath));
			pb.redirectErrorStream(true);

			Process process = null;
			StreamGobbler gobbler = null;
			try {
				process = pb.start();
				gobbler = new StreamGobbler(process.getInputStream(), s -> logger.info("[maven] {}", s));
				gobbler.start();

				boolean finished = process.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				if (!finished) {
					logger.error("Maven test-compile timed out after {}s for {}, killing process", MVN_TIMEOUT_SECONDS, projectPath);
					process.destroyForcibly();
					throw new RuntimeException("Timed out running mvn test-compile for " + projectPath);
				}
				int exitCode = process.exitValue();
				if (exitCode != 0) {
					throw new RuntimeException("Maven test-compile failed for " + projectPath + ", exit code " + exitCode);
				} else {
					logger.info("Maven test-compile succeeded for {}", projectPath);
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				if (process != null) process.destroyForcibly();
				throw new RuntimeException("Interrupted while running Maven test-compile for " + projectPath, ie);
			} catch (IOException ioe) {
				if (process != null) process.destroyForcibly();
				throw new RuntimeException("Failed to run Maven test-compile for " + projectPath, ioe);
			} finally {
				if (gobbler != null) {
					try {
						gobbler.join(500);
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
				}
			}
		}
	}

	/* ------------------ Helper methods ------------------ */

	/**
	 * Collects .java files recursively. Null-safe.
	 */
	private static void collectJavaFiles(File dir, List<File> javaFiles) {
		if (dir == null || !dir.exists() || !dir.isDirectory()) {
			return;
		}
		File[] list = dir.listFiles();
		if (list == null) return;
		for (File file : list) {
			if (file.isDirectory()) {
				collectJavaFiles(file, javaFiles);
			} else if (file.getName().endsWith(".java")) {
				javaFiles.add(file);
			}
		}
	}

	/**
	 * Builds a class file path relative to src/test/java -> target/test-classes
	 */
	private static File getClassFileFor(File javaFile, File srcDir, File outputDir) {
		String relative = javaFile.getAbsolutePath().substring(srcDir.getAbsolutePath().length() + 1);
		String classNamePath = relative.replace(".java", ".class");
		return new File(outputDir, classNamePath);
	}

	/**
	 * Small thread to continuously read input stream and pass lines to a consumer.
	 * This prevents spawned processes blocking due to full stdout/stderr buffers.
	 */
	private static class StreamGobbler extends Thread {
		private final InputStream is;
		private final Consumer<String> consumer;

		StreamGobbler(InputStream is, Consumer<String> consumer) {
			this.is = is;
			this.consumer = consumer;
			setDaemon(true);
		}

		@Override
		public void run() {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				String line;
				while ((line = br.readLine()) != null) {
					try {
						consumer.accept(line);
					} catch (Exception e) {
						// swallow consumer exceptions but log
						logger.warn("StreamGobbler consumer threw: {}", e.getMessage(), e);
					}
				}
			} catch (IOException e) {
				logger.debug("StreamGobbler IO error: {}", e.getMessage());
			}
		}
	}
}
