package com.qa.cbcc.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StepDefCompiler {

	private static final Logger logger = LoggerFactory.getLogger(StepDefCompiler.class);

//	public static void ensureDependenciesCopied() {
//		File depDir = new File("target/dependency");
//
//		// ✅ Skip if already copied
//		if (depDir.exists() && depDir.isDirectory() && depDir.list().length > 0) {
//			logger.info("Dependencies already available in target/dependency, skipping copy.");
//			return;
//		}
//
//		try {
//			// ✅ Cross-platform mvn command
//			String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
//
//			logger.info("Copying dependencies with {} ...", mvnCmd);
//
//			ProcessBuilder pb = new ProcessBuilder(mvnCmd, "dependency:copy-dependencies",
//					"-DoutputDirectory=target/dependency", "-DincludeScope=runtime");
//			pb.redirectErrorStream(true);
//
//			Process process = pb.start();
//
//			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
//				String line;
//				while ((line = reader.readLine()) != null) {
//					logger.info("[maven] {}", line);
//				}
//			}
//
//			int exitCode = process.waitFor();
//			if (exitCode != 0) {
//				throw new RuntimeException("Maven dependency:copy-dependencies failed with exit code " + exitCode);
//			}
//
//			logger.info("Dependencies copied successfully into target/dependency");
//
//		} catch (Exception e) {
//			logger.error("Failed to copy dependencies automatically", e);
//			throw new RuntimeException("Failed to copy dependencies automatically", e);
//		}
//	}
//
//	public static void compileStepDefs(List<String> projectPaths) {
//		for (String projectPath : projectPaths) {
//			File srcDir = new File(projectPath, "src/test/java");
//			if (!srcDir.exists()) {
//				throw new IllegalArgumentException("Source dir not found: " + srcDir.getAbsolutePath());
//			}
//
//			File outputDir = new File(projectPath, "target/test-classes");
//			outputDir.mkdirs();
//
//			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
//			if (compiler == null) {
//				throw new IllegalStateException(
//						"❌ No system Java compiler. Are you running on a JRE instead of a JDK?");
//			}
//
//			// Collect all .java files
//			List<File> javaFiles = new ArrayList<>();
//			collectJavaFiles(srcDir, javaFiles);
//
//			if (javaFiles.isEmpty()) {
//				System.out.println("⚠ No stepDef .java files found in " + srcDir);
//				continue;
//			}
//
//			// Filter only those that are newer than .class
//			List<File> toCompile = new ArrayList<>();
//			for (File javaFile : javaFiles) {
//				File classFile = getClassFileFor(javaFile, srcDir, outputDir);
//				if (!classFile.exists() || javaFile.lastModified() > classFile.lastModified()) {
//					toCompile.add(javaFile);
//				}
//			}
//
//			if (toCompile.isEmpty()) {
//				System.out.println("✅ StepDefs already up-to-date, skipping compile for " + projectPath);
//				continue;
//			}
//
//			List<String> options = Arrays.asList("-d", outputDir.getAbsolutePath());
//			int result = compiler.run(null, null, null, Stream
//					.concat(options.stream(), toCompile.stream().map(File::getAbsolutePath)).toArray(String[]::new));
//
//			if (result != 0) {
//				throw new RuntimeException("❌ StepDefs compilation failed, exit code " + result);
//			} else {
//				System.out
//						.println("✅ Compiled " + toCompile.size() + " StepDef(s) into " + outputDir.getAbsolutePath());
//			}
//		}
//	}

	public static void ensureDependenciesCopied() {
		File depDir = new File("target/dependency");

		if (!depDir.exists()) {
			depDir.mkdirs();
		}

		// ✅ Skip if already copied
		if (depDir.exists() && depDir.isDirectory() && depDir.list().length > 0) {
			logger.info("Dependencies already available in target/dependency, skipping copy.");
			return;
		}

		try {
			String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
			logger.info("Copying dependencies with {} ...", mvnCmd);

			ProcessBuilder pb = new ProcessBuilder(mvnCmd, "dependency:copy-dependencies",
					"-DoutputDirectory=target/dependency", "-DincludeScope=test");
			pb.redirectErrorStream(true);

			Process process = pb.start();

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					logger.info("[maven] {}", line);
				}
			}

			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new RuntimeException("Maven dependency:copy-dependencies failed with exit code " + exitCode);
			}

			logger.info("Dependencies copied successfully into target/dependency");

		} catch (Exception e) {
			logger.error("Failed to copy dependencies automatically", e);
			throw new RuntimeException("Failed to copy dependencies automatically", e);
		}
	}

//	public static void compileStepDefs(List<String> projectPaths) {
//		for (String projectPath : projectPaths) {
//			File srcDir = new File(projectPath, "src/test/java");
//			if (!srcDir.exists()) {
//				throw new IllegalArgumentException("Source dir not found: " + srcDir.getAbsolutePath());
//			}
//
//			File outputDir = new File(projectPath, "target/test-classes");
//			outputDir.mkdirs();
//
//			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
//			if (compiler == null) {
//				throw new IllegalStateException(
//						"❌ No system Java compiler. Are you running on a JRE instead of a JDK?");
//			}
//
//			// Collect all .java files
//			List<File> javaFiles = new ArrayList<>();
//			collectJavaFiles(srcDir, javaFiles);
//
//			if (javaFiles.isEmpty()) {
//				System.out.println("⚠ No stepDef .java files found in " + srcDir);
//				continue;
//			}
//
//			// Filter only those that are newer than .class
//			List<File> toCompile = new ArrayList<>();
//			for (File javaFile : javaFiles) {
//				File classFile = getClassFileFor(javaFile, srcDir, outputDir);
//				if (!classFile.exists() || javaFile.lastModified() > classFile.lastModified()) {
//					toCompile.add(javaFile);
//				}
//			}
//
//			if (toCompile.isEmpty()) {
//				System.out.println("✅ StepDefs already up-to-date, skipping compile for " + projectPath);
//				continue;
//			}
//
//			// Build classpath: target/dependency/*.jar + target/classes +
//			// target/test-classes
//			File depDir = new File("target/dependency"); // <-- use root, not per-project
//			StringBuilder classpath = new StringBuilder();
//			if (depDir.exists() && depDir.isDirectory()) {
//				File[] jars = depDir.listFiles((dir, name) -> name.endsWith(".jar"));
//				if (jars != null) {
//					for (File jar : jars) {
//						classpath.append(jar.getAbsolutePath()).append(File.pathSeparator);
//					}
//				}
//			}
//			classpath.append(new File(projectPath, "target/classes").getAbsolutePath()).append(File.pathSeparator);
//			classpath.append(outputDir.getAbsolutePath());
//
//// Build processor path from jars in target/dependency
//			StringBuilder processorPath = new StringBuilder();
//			if (depDir.exists() && depDir.isDirectory()) {
//				File[] jars = depDir.listFiles((dir, name) -> name.endsWith(".jar"));
//				if (jars != null) {
//					for (File jar : jars) {
//						processorPath.append(jar.getAbsolutePath()).append(File.pathSeparator);
//					}
//				}
//			}
//
//			List<String> options = Arrays.asList(
//					"-d", outputDir.getAbsolutePath(),
//					"-classpath", classpath.toString(),
//					"-processorpath", processorPath.toString(),
//					"-encoding", System.getProperty("file.encoding")
//			);
//
//			int result = compiler.run(null, null, null, Stream
//					.concat(options.stream(), toCompile.stream().map(File::getAbsolutePath)).toArray(String[]::new));
//
//			if (result != 0) {
//				throw new RuntimeException("❌ StepDefs compilation failed, exit code " + result);
//			} else {
//				System.out
//						.println("✅ Compiled " + toCompile.size() + " StepDef(s) into " + outputDir.getAbsolutePath());
//			}
//		}
//	}

public static void compileStepDefs(List<String> projectPaths) {
	for (String projectPath : projectPaths) {
		File srcDir = new File(projectPath, "src/test/java");
		File outputDir = new File(projectPath, "target/test-classes");
		File pomFile = new File(projectPath, "pom.xml");
		if (!pomFile.exists()) {
			System.out.println("⚠ No pom.xml found in " + projectPath + ", skipping Maven compile.");
			continue;
		}
		List<File> javaFiles = new ArrayList<>();
		collectJavaFiles(srcDir, javaFiles);

		boolean needsCompile = false;
		for (File javaFile : javaFiles) {
			File classFile = getClassFileFor(javaFile, srcDir, outputDir);
			if (!classFile.exists() || javaFile.lastModified() > classFile.lastModified()) {
				needsCompile = true;
				break;
			}
		}

		if (!needsCompile) {
			System.out.println("✅ StepDefs already up-to-date, skipping compile for " + projectPath);
			continue;
		}

		String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
		ProcessBuilder pb = new ProcessBuilder(mvnCmd, "test-compile");
		pb.directory(new File(projectPath));
		pb.redirectErrorStream(true);

		try {
			Process process = pb.start();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					System.out.println("[maven] " + line);
				}
			}
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new RuntimeException("❌ Maven test-compile failed for " + projectPath + ", exit code " + exitCode);
			} else {
				System.out.println("✅ Maven test-compile succeeded for " + projectPath);
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to run Maven test-compile for " + projectPath, e);
		}
	}
}

	private static void collectJavaFiles(File dir, List<File> javaFiles) {
		for (File file : dir.listFiles()) {
			if (file.isDirectory()) {
				collectJavaFiles(file, javaFiles);
			} else if (file.getName().endsWith(".java")) {
				javaFiles.add(file);
			}
		}
	}

	private static File getClassFileFor(File javaFile, File srcDir, File outputDir) {
		// Build class file path relative to src/test/java
		String relative = javaFile.getAbsolutePath().substring(srcDir.getAbsolutePath().length() + 1);
		String classNamePath = relative.replace(".java", ".class");
		return new File(outputDir, classNamePath);
	}
}
