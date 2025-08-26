package com.qa.cbcc.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class StepDefCompiler {

    public static void compileStepDefs(List<String> projectPaths) {
        for (String projectPath : projectPaths) {
            File srcDir = new File(projectPath, "src/test/java");
            if (!srcDir.exists()) {
                throw new IllegalArgumentException("Source dir not found: " + srcDir.getAbsolutePath());
            }

            File outputDir = new File(projectPath, "target/test-classes");
            outputDir.mkdirs();

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("❌ No system Java compiler. Are you running on a JRE instead of a JDK?");
            }

            // Collect all .java files
            List<File> javaFiles = new ArrayList<>();
            collectJavaFiles(srcDir, javaFiles);

            if (javaFiles.isEmpty()) {
                System.out.println("⚠ No stepDef .java files found in " + srcDir);
                continue;
            }

            // Filter only those that are newer than .class
            List<File> toCompile = new ArrayList<>();
            for (File javaFile : javaFiles) {
                File classFile = getClassFileFor(javaFile, srcDir, outputDir);
                if (!classFile.exists() || javaFile.lastModified() > classFile.lastModified()) {
                    toCompile.add(javaFile);
                }
            }

            if (toCompile.isEmpty()) {
                System.out.println("✅ StepDefs already up-to-date, skipping compile for " + projectPath);
                continue;
            }

            List<String> options = Arrays.asList("-d", outputDir.getAbsolutePath());
            int result = compiler.run(null, null, null,
                    Stream.concat(options.stream(), toCompile.stream().map(File::getAbsolutePath))
                          .toArray(String[]::new));

            if (result != 0) {
                throw new RuntimeException("❌ StepDefs compilation failed, exit code " + result);
            } else {
                System.out.println("✅ Compiled " + toCompile.size() + " StepDef(s) into " + outputDir.getAbsolutePath());
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
