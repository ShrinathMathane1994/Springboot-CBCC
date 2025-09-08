package com.qa.cbcc.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Comparison;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

public class XmlComparator {

	private static final Logger logger = LoggerFactory.getLogger(XmlComparator.class);

	// Per-thread flag to prevent double logging
	private static final ThreadLocal<AtomicBoolean> hasLogged = ThreadLocal.withInitial(() -> new AtomicBoolean(false));

	// Config resource (must exist on classpath)
	private static final String CONFIG_RESOURCE = "sem-diff.properties";

	// Public-ish names retained
	private static final Set<String> SKIPPED_TAGS;
	private static final String SKIPPED_TAGS_REGEX;
	private static final Set<String> PLACEHOLDERS;
	private static final Pattern PLACEHOLDER_TOKEN;

	static {
		Properties props = new Properties();
		// Try to load config if present; proceed quietly if it's missing
		try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
			if (is != null) {
				props.load(is);
				logger.info("Loaded '{}' configuration from classpath.", CONFIG_RESOURCE);
			} else {
				logger.info(
						"Configuration '{}' not found on classpath — proceeding with empty SKIPPED_TAGS and PLACEHOLDERS (no skipping).",
						CONFIG_RESOURCE);
			}
		} catch (IOException e) {
			logger.warn("Failed to load '{}', proceeding with empty SKIPPED_TAGS and PLACEHOLDERS: {}", CONFIG_RESOURCE,
					e.getMessage(), e);
		}

		// Read properties but do NOT fail if they are missing/empty — treat as empty
		// sets
		String skipped = props.getProperty("skipped.tags");
		String placeholders = props.getProperty("placeholders");

		if (skipped == null || skipped.trim().isEmpty()) {
			SKIPPED_TAGS = new LinkedHashSet<>(); // empty => don't remove any tags
		} else {
			SKIPPED_TAGS = Arrays.stream(skipped.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty())
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		if (placeholders == null || placeholders.trim().isEmpty()) {
			PLACEHOLDERS = new LinkedHashSet<>(); // empty => no placeholders to ignore
		} else {
			PLACEHOLDERS = Arrays.stream(placeholders.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty())
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		// Build regex (will be empty string if SKIPPED_TAGS is empty)
		SKIPPED_TAGS_REGEX = buildSkippedTagsRegex(SKIPPED_TAGS);

		// If no placeholders provided, use a pattern that never matches so
		// placeholder-filtering is disabled.
		if (!PLACEHOLDERS.isEmpty()) {
			String joined = PLACEHOLDERS.stream().map(Pattern::quote).collect(Collectors.joining("|"));
			PLACEHOLDER_TOKEN = Pattern.compile("\\$\\{(" + joined + ")\\}");
		} else {
			PLACEHOLDER_TOKEN = Pattern.compile("(?!)"); // never matches
		}
	}

	public static String compareXmlFiles(String filePath1, String filePath2) {
		StringBuilder result = new StringBuilder();
		try {
			String xml1 = Files.readString(Paths.get(filePath1), StandardCharsets.UTF_8);
			String xml2 = Files.readString(Paths.get(filePath2), StandardCharsets.UTF_8);

			// Remove skipped tags from both XML strings
			String sanitized1 = removeSkippedTags(xml1, SKIPPED_TAGS_REGEX);
			String sanitized2 = removeSkippedTags(xml2, SKIPPED_TAGS_REGEX);

			Diff diff = DiffBuilder.compare(Input.fromString(sanitized1)).withTest(Input.fromString(sanitized2))
					.ignoreWhitespace().checkForSimilar().build();

			if (!diff.hasDifferences()) {
				result.append("✅ XML files are equal.");
			} else {
				result.append("❌ XML files are NOT equal.\nDifferences:\n");

				// Track unique namespace changes
				Set<String> seenNamespaces = new HashSet<>();

				String differences = StreamSupport.stream(diff.getDifferences().spliterator(), false).filter(d -> {
					Comparison c = d.getComparison();
					ComparisonType type = c.getType();

					// If it's a namespace change, skip if already seen
					if (type == ComparisonType.NAMESPACE_URI || type == ComparisonType.NAMESPACE_PREFIX) {
						String control = String.valueOf(c.getControlDetails().getValue());
						String test = String.valueOf(c.getTestDetails().getValue());
						String key = control + "→" + test;
						if (seenNamespaces.contains(key)) {
							return false;
						}
						seenNamespaces.add(key);
					}

					// If the difference values involve placeholders, skip it
					String left = safeToString(c.getControlDetails().getValue());
					String right = safeToString(c.getTestDetails().getValue());
					if (PLACEHOLDER_TOKEN.matcher(left).find() || PLACEHOLDER_TOKEN.matcher(right).find()) {
						return false;
					}

					return true;
				}).map(Difference::toString).collect(Collectors.joining("\n"));

				if (differences.trim().isEmpty()) {
					result.append("No actionable differences after applying SKIPPED_TAGS and PLACEHOLDERS filters.");
				} else {
					result.append(differences);
				}
			}

		} catch (Exception e) {
			// Convert paths to relative for display
			String relativeFile1 = toRelativePath(filePath1);
			String relativeFile2 = toRelativePath(filePath2);

			// Extract only the reason from the exception message (remove absolute paths &
			// file names)
			String cleanedMessage = e.getMessage() != null ? e.getMessage() : e.toString();
			int idx = cleanedMessage.indexOf('(');
			if (idx != -1 && cleanedMessage.endsWith(")")) {
				// If message is like: "D:\path\file.xml (reason)"
				cleanedMessage = cleanedMessage.substring(idx + 1, cleanedMessage.length() - 1);
			}

			result.append("❌ Error comparing XML files: ").append(relativeFile1).append(" vs ").append(relativeFile2)
					.append(" (").append(cleanedMessage).append(")");
		}

		// 🔐 Only log once per thread to avoid duplication
		if (!hasLogged.get().get()) {
			logger.info("===== Begin XML Comparison Output =====\n{}\n===== End XML Comparison Output =====",
					result.toString());
			hasLogged.get().set(true); // Mark as logged
		}

		return result.toString();
	}

	// Helper converts null to empty string
	private static String safeToString(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static String removeSkippedTags(String xml, String skippedRegex) {
		if (xml == null || xml.trim().isEmpty() || skippedRegex == null || skippedRegex.isEmpty())
			return xml;
		try {
			return xml.replaceAll(skippedRegex, "");
		} catch (Exception ex) {
			// if regex fails for some reason, return original xml (defensive)
			logger.error("Failed to remove skipped tags using regex: {}", ex.getMessage(), ex);
			return xml;
		}
	}

	private static String toRelativePath(String absolutePath) {
		String base = Paths.get("src", "main", "resources").toAbsolutePath().toString();
		return absolutePath.replace(base + File.separator, "").replace("\\", "/");
	}

	/**
	 * Build a regex that matches any of the tag names (optionally with a namespace
	 * prefix). This matches both <Tag>..</Tag> and self-closing <Tag/> forms
	 * (namespace-aware).
	 */
	private static String buildSkippedTagsRegex(Set<String> tags) {
		if (tags == null || tags.isEmpty())
			return "";
		List<String> perTag = tags.stream().filter(t -> t != null && !t.trim().isEmpty()).map(t -> {
			String tq = Pattern.quote(t.trim());
			String openClose = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*>.*?</(?:[^:\\s>]+:)?" + tq + "\\s*>";
			String selfClose = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*/\\s*>";
			return "(?:" + openClose + "|" + selfClose + ")";
		}).collect(Collectors.toList());
		return perTag.stream().collect(Collectors.joining("|"));
	}

	// ----Old Code----//

//    private static final Logger logger = LoggerFactory.getLogger(XmlComparator.class);
//
//    // Per-thread flag to prevent double logging
//    private static final ThreadLocal<AtomicBoolean> hasLogged = ThreadLocal.withInitial(() -> new AtomicBoolean(false));
//
//    public static String compareXmlFiles(String filePath1, String filePath2) {
//        StringBuilder result = new StringBuilder();
//
//        try {
//            File file1 = new File(filePath1);
//            File file2 = new File(filePath2);
//
//            Diff diff = DiffBuilder.compare(Input.fromFile(file1))
//                                   .withTest(Input.fromFile(file2))
//                                   .ignoreWhitespace()
//                                   .checkForSimilar()
//                                   .build();
//
//            if (!diff.hasDifferences()) {
//                result.append("✅ XML files are equal.");
//            } else {
//                result.append("❌ XML files are NOT equal.\nDifferences:\n");
//
//                // Track unique namespace changes
//                Set<String> seenNamespaces = new HashSet<>();
//
//                String differences = StreamSupport.stream(diff.getDifferences().spliterator(), false)
//                        .filter(d -> {
//                            Comparison c = d.getComparison();
//                            String type = c.getType().name();
//
//                            // If it's a namespace change, skip if already seen
//                            if ("NAMESPACE_URI".equals(type)) {
//                                String control = String.valueOf(c.getControlDetails().getValue());
//                                String test = String.valueOf(c.getTestDetails().getValue());
//                                String key = control + "→" + test;
//                                if (seenNamespaces.contains(key)) {
//                                    return false;
//                                }
//                                seenNamespaces.add(key);
//                            }
//                            return true;
//                        })
//                        .map(Difference::toString)
//                        .collect(Collectors.joining("\n"));
//
//                result.append(differences);
//            }
//
//        } catch (Exception e) {
//            // Convert paths to relative for display
//            String relativeFile1 = toRelativePath(filePath1);
//            String relativeFile2 = toRelativePath(filePath2);
//
//            // Extract only the reason from the exception message (remove absolute paths & file names)
//            String cleanedMessage = e.getMessage();
//            int idx = cleanedMessage.indexOf('(');
//            if (idx != -1 && cleanedMessage.endsWith(")")) {
//                // If message is like: "D:\path\file.xml (reason)"
//                cleanedMessage = cleanedMessage.substring(idx + 1, cleanedMessage.length() - 1);
//            }
//
//            result.append("❌ Error comparing XML files: ")
//                  .append(relativeFile1)
//                  .append(" vs ")
//                  .append(relativeFile2)
//                  .append(" (")
//                  .append(cleanedMessage)
//                  .append(")");
//        }
//
//        // 🔐 Only log once per thread to avoid duplication
//        if (!hasLogged.get().get()) {
//            logger.info("===== Begin XML Comparison Output =====\n{}\n===== End XML Comparison Output =====", result.toString());
//            hasLogged.get().set(true); // Mark as logged
//        }
//
//        return result.toString();
//    }
//    
//    private static String toRelativePath(String absolutePath) {
//        String base = Paths.get("src", "main", "resources").toAbsolutePath().toString();
//        return absolutePath.replace(base + File.separator, "").replace("\\", "/");
//    }
}
