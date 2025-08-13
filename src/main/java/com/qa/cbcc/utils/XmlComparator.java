package com.qa.cbcc.utils;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

public class XmlComparator {

    private static final Logger logger = LoggerFactory.getLogger(XmlComparator.class);

    // Per-thread flag to prevent double logging
    private static final ThreadLocal<AtomicBoolean> hasLogged = ThreadLocal.withInitial(() -> new AtomicBoolean(false));

    public static String compareXmlFiles(String filePath1, String filePath2) {
        StringBuilder result = new StringBuilder();

        try {
            File file1 = new File(filePath1);
            File file2 = new File(filePath2);

            Diff diff = DiffBuilder.compare(Input.fromFile(file1))
                                   .withTest(Input.fromFile(file2))
                                   .ignoreWhitespace()
                                   .checkForSimilar()
                                   .build();

            if (!diff.hasDifferences()) {
                result.append("✅ XML files are equal.");
            } else {
                result.append("❌ XML files are NOT equal.\nDifferences:\n");

                String differences = StreamSupport.stream(diff.getDifferences().spliterator(), false)
                        .map(Difference::toString)
                        .collect(Collectors.joining("\n"));

                result.append(differences);
            }

        } catch (Exception e) {
        	// Convert paths to relative for display
            String relativeFile1 = toRelativePath(filePath1);
            String relativeFile2 = toRelativePath(filePath2);

            // Extract only the reason from the exception message (remove absolute paths & file names)
            String cleanedMessage = e.getMessage();
            int idx = cleanedMessage.indexOf('(');
            if (idx != -1 && cleanedMessage.endsWith(")")) {
                // If message is like: "D:\path\file.xml (reason)"
                cleanedMessage = cleanedMessage.substring(idx + 1, cleanedMessage.length() - 1);
            }

            result.append("❌ Error comparing XML files: ")
                  .append(relativeFile1)
                  .append(" vs ")
                  .append(relativeFile2)
                  .append(" (")
                  .append(cleanedMessage)
                  .append(")");
        }

        // 🔐 Only log once per thread to avoid duplication
        if (!hasLogged.get().get()) {
            logger.info("===== Begin XML Comparison Output =====\n{}\n===== End XML Comparison Output =====", result.toString());
            hasLogged.get().set(true); // Mark as logged
        }

        return result.toString();
    }
    
    private static String toRelativePath(String absolutePath) {
        String base = Paths.get("src", "main", "resources").toAbsolutePath().toString();
        return absolutePath.replace(base + File.separator, "").replace("\\", "/");
    }
}
