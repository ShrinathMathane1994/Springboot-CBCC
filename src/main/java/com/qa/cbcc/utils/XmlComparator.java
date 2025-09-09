package com.qa.cbcc.utils;

//Place this in your package as appropriate
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

//	private static final Logger logger = LoggerFactory.getLogger(XmlComparator.class);
//
//	// Per-thread flag to prevent double logging
//	private static final ThreadLocal<AtomicBoolean> hasLogged = ThreadLocal
//			.withInitial(new java.util.function.Supplier<AtomicBoolean>() {
//				@Override
//				public AtomicBoolean get() {
//					return new AtomicBoolean(false);
//				}
//			});
//
//	// Config resource (must exist on classpath)
//	private static final String CONFIG_RESOURCE = "sem-diff.properties";
//
//	// Public-ish names retained
//	private static final Set<String> SKIPPED_TAGS;
//	private static final Set<String> PLACEHOLDERS;
//	private static final Pattern PLACEHOLDER_TOKEN;
//
//	static {
//		Properties props = new Properties();
//		// Try to load config if present; proceed quietly if it's missing
//		InputStream is = null;
//		try {
//			is = Thread.currentThread().getContextClassLoader().getResourceAsStream(CONFIG_RESOURCE);
//			if (is != null) {
//				props.load(is);
//				logger.info("Loaded '{}' configuration from classpath.", CONFIG_RESOURCE);
//			} else {
//				logger.info(
//						"Configuration '{}' not found on classpath — proceeding with empty SKIPPED_TAGS and PLACEHOLDERS (no skipping).",
//						CONFIG_RESOURCE);
//			}
//		} catch (IOException e) {
//			logger.warn("Failed to load '{}', proceeding with empty SKIPPED_TAGS and PLACEHOLDERS: {}", CONFIG_RESOURCE,
//					e.getMessage(), e);
//		} finally {
//			if (is != null) {
//				try {
//					is.close();
//				} catch (IOException ignored) {
//				}
//			}
//		}
//
//		// Read properties but do NOT fail if they are missing/empty — treat as empty
//		// sets
//		String skipped = props.getProperty("skipped.tags");
//		String placeholders = props.getProperty("placeholders");
//
//		if (skipped == null || skipped.trim().isEmpty()) {
//			SKIPPED_TAGS = new LinkedHashSet<String>(); // empty => don't remove any tags
//		} else {
//			SKIPPED_TAGS = Arrays.stream(skipped.split("[,;]")).map(String::trim)
//					.filter(new java.util.function.Predicate<String>() {
//						@Override
//						public boolean test(String s) {
//							return s != null && !s.isEmpty();
//						}
//					}).collect(Collectors.toCollection(new java.util.function.Supplier<LinkedHashSet<String>>() {
//						@Override
//						public LinkedHashSet<String> get() {
//							return new LinkedHashSet<String>();
//						}
//					}));
//		}
//
//		if (placeholders == null || placeholders.trim().isEmpty()) {
//			PLACEHOLDERS = new LinkedHashSet<String>(); // empty => no placeholders to ignore
//		} else {
//			PLACEHOLDERS = Arrays.stream(placeholders.split("[,;]")).map(String::trim)
//					.filter(new java.util.function.Predicate<String>() {
//						@Override
//						public boolean test(String s) {
//							return s != null && !s.isEmpty();
//						}
//					}).collect(Collectors.toCollection(new java.util.function.Supplier<LinkedHashSet<String>>() {
//						@Override
//						public LinkedHashSet<String> get() {
//							return new LinkedHashSet<String>();
//						}
//					}));
//		}
//
//		buildSkippedTagsRegex(SKIPPED_TAGS);
//
//		// If no placeholders provided, use a pattern that never matches so
//		// placeholder-filtering is disabled.
//		if (!PLACEHOLDERS.isEmpty()) {
//			String joined = PLACEHOLDERS.stream().map(Pattern::quote).collect(Collectors.joining("|"));
//			PLACEHOLDER_TOKEN = Pattern.compile("\\$\\{(" + joined + ")\\}");
//		} else {
//			PLACEHOLDER_TOKEN = Pattern.compile("(?!)"); // never matches
//		}
//	}
//
//	/**
//	 * Main comparison method (two-stage): compares outer envelope (with inner
//	 * payload replaced by a placeholder) and then compares inner payload
//	 * separately.
//	 */
//	public static String compareXmlFiles(String filePath1, String filePath2) {
//		StringBuilder result = new StringBuilder();
//		try {
//			// === Read XML files ===
//			String xml1 = new String(Files.readAllBytes(Paths.get(filePath1)), StandardCharsets.UTF_8);
//			String xml2 = new String(Files.readAllBytes(Paths.get(filePath2)), StandardCharsets.UTF_8);
//
//			// === Global skipped-tags removal (same behavior as buildSemanticXmlDiff) ===
//			String sanitized1 = xml1;
//			String sanitized2 = xml2;
//			try {
//				if (SKIPPED_TAGS != null && !SKIPPED_TAGS.isEmpty()) {
//					for (String tag : SKIPPED_TAGS) {
//						if (tag == null || tag.trim().isEmpty())
//							continue;
//						String tq = Pattern.quote(tag);
//
//						// remove full open/close form (namespace tolerant)
//						String openCloseRegex = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*>.*?</(?:[^:\\s>]+:)?" + tq
//								+ "\\s*>";
//						sanitized1 = sanitized1.replaceAll(openCloseRegex, "");
//						sanitized2 = sanitized2.replaceAll(openCloseRegex, "");
//
//						// remove self-closing form
//						String selfCloseRegex = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*/\\s*>";
//						sanitized1 = sanitized1.replaceAll(selfCloseRegex, "");
//						sanitized2 = sanitized2.replaceAll(selfCloseRegex, "");
//					}
//				}
//			} catch (Exception ex) {
//				logger.warn("Global skipped-tags sanitization failed; falling back to raw XML: {}", ex.getMessage());
//				sanitized1 = xml1;
//				sanitized2 = xml2;
//			}
//
//			sanitized1 = sanitized1.trim();
//			sanitized2 = sanitized2.trim();
//
//			// === Extract inner payloads using DOM extraction (preferred) with regex
//			// fallback ===
//			String inner1 = extractInnerPayloadUsingDom(sanitized1);
//			String inner2 = extractInnerPayloadUsingDom(sanitized2);
//
//			if (inner1 == null && inner2 == null) {
//				// fallback to regex extraction (collect all CDATA sections if any)
//				inner1 = extractInnerPayloadWithRegexAll(sanitized1);
//				inner2 = extractInnerPayloadWithRegexAll(sanitized2);
//			}
//
//			final String placeholder = "<!--INNER_PAYLOAD_REPLACED-->";
//
//			// Replace the first CDATA or payload body with placeholder for outer comparison
//			String outer1 = replaceInnerWithPlaceholder(sanitized1, placeholder);
//			String outer2 = replaceInnerWithPlaceholder(sanitized2, placeholder);
//
//			outer1 = outer1.trim();
//			outer2 = outer2.trim();
//
//			// === Stage 1: compare outer envelopes (headers etc.) ===
//			Diff outerDiff = DiffBuilder.compare(Input.fromString(outer1)).withTest(Input.fromString(outer2))
//					.ignoreWhitespace().checkForSimilar()
//					.withNodeMatcher(new DefaultNodeMatcher(ElementSelectors.byNameAndText, ElementSelectors.byName))
//					.build();
//
//			// Collect outer differences (apply same namespace and placeholder filters)
//			List<String> outerDiffLines = collectFilteredDifferences(outerDiff);
//
//			// === Stage 2: compare inner payloads if either exists ===
//			List<String> innerDiffLines = new ArrayList<String>();
//			if (inner1 != null || inner2 != null) {
//				String i1 = inner1 == null ? "" : inner1.trim();
//				String i2 = inner2 == null ? "" : inner2.trim();
//
//				// Optionally strip leading XML declaration inside the payload for normalized
//				// comparison
//				i1 = stripXmlDecl(i1);
//				i2 = stripXmlDecl(i2);
//
//				Diff innerDiff = DiffBuilder.compare(Input.fromString(i1)).withTest(Input.fromString(i2))
//						.ignoreWhitespace().checkForSimilar()
//						.withNodeMatcher(
//								new DefaultNodeMatcher(ElementSelectors.byNameAndText, ElementSelectors.byName))
//						.build();
//
//				innerDiffLines = collectFilteredDifferences(innerDiff);
//
//				// Debug pairing logs for inner payload
//				for (Difference d : innerDiff.getDifferences()) {
//					Comparison comp = d.getComparison();
//					String ctrlXPath = comp.getControlDetails() != null
//							? String.valueOf(comp.getControlDetails().getXPath())
//							: null;
//					String testXPath = comp.getTestDetails() != null ? String.valueOf(comp.getTestDetails().getXPath())
//							: null;
//					logger.debug("[INNER] Paired controlXPath='{}' with testXPath='{}' (type={})", ctrlXPath, testXPath,
//							comp.getType());
//				}
//			}
//
//			// Debug pairing logs for outer
//			for (Difference d : outerDiff.getDifferences()) {
//				Comparison comp = d.getComparison();
//				String ctrlXPath = comp.getControlDetails() != null
//						? String.valueOf(comp.getControlDetails().getXPath())
//						: null;
//				String testXPath = comp.getTestDetails() != null ? String.valueOf(comp.getTestDetails().getXPath())
//						: null;
//				logger.debug("[OUTER] Paired controlXPath='{}' with testXPath='{}' (type={})", ctrlXPath, testXPath,
//						comp.getType());
//			}
//
//			// Combine results: outer diffs first, then inner diffs
//			List<String> allDiffs = new ArrayList<String>();
//			allDiffs.addAll(outerDiffLines);
//			allDiffs.addAll(innerDiffLines);
//
//			if (allDiffs.isEmpty()) {
//				result.append("✅ XML files are equal.");
//			} else {
//				result.append("❌ XML files are NOT equal.\nDifferences:\n");
//				result.append(String.join("\n", allDiffs));
//			}
//
//		} catch (Exception e) {
//			// Convert paths to relative for display
//			String relativeFile1 = toRelativePath(filePath1);
//			String relativeFile2 = toRelativePath(filePath2);
//
//			String cleanedMessage = e.getMessage() != null ? e.getMessage() : e.toString();
//			int idx = cleanedMessage.indexOf('(');
//			if (idx != -1 && cleanedMessage.endsWith(")")) {
//				cleanedMessage = cleanedMessage.substring(idx + 1, cleanedMessage.length() - 1);
//			}
//
//			result.append("❌ Error comparing XML files: ").append(relativeFile1).append(" vs ").append(relativeFile2)
//					.append(" (").append(cleanedMessage).append(")");
//		}
//
//		// 🔐 Only log once per thread
//		if (!hasLogged.get().get()) {
//			logger.info("===== Begin XML Comparison Output =====\n{}\n===== End XML Comparison Output =====",
//					result.toString());
//			hasLogged.get().set(true);
//		}
//
//		return result.toString();
//	}
//
//	// Helper: collects and filters differences using namespace dedupe and
//	// placeholder filtering
//	private static List<String> collectFilteredDifferences(Diff diff) {
//		List<String> lines = new ArrayList<String>();
//		Set<String> seenNamespaces = new HashSet<String>();
//		for (Difference d : diff.getDifferences()) {
//			Comparison c = d.getComparison();
//			ComparisonType type = c.getType();
//
//			if (type == ComparisonType.NAMESPACE_URI || type == ComparisonType.NAMESPACE_PREFIX) {
//				String control = String.valueOf(c.getControlDetails().getValue());
//				String test = String.valueOf(c.getTestDetails().getValue());
//				String key = type.name() + ":" + control + "→" + test;
//				if (seenNamespaces.contains(key))
//					continue;
//				seenNamespaces.add(key);
//			}
//
//			String left = safeToString(c.getControlDetails().getValue());
//			String right = safeToString(c.getTestDetails().getValue());
//			if (PLACEHOLDER_TOKEN.matcher(left).find() || PLACEHOLDER_TOKEN.matcher(right).find()) {
//				continue;
//			}
//
//			lines.add(d.toString());
//		}
//		return lines;
//	}
//
//	// Helper: try DOM extraction of inner payload (returns CDATA contents or
//	// serialized inner XML)
//	private static String extractInnerPayloadUsingDom(String xml) {
//		if (xml == null || xml.trim().isEmpty())
//			return null;
//		try {
//			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
//			dbf.setNamespaceAware(true);
//			try {
//				dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
//			} catch (ParserConfigurationException ignored) {
//			}
//
//			DocumentBuilder db = dbf.newDocumentBuilder();
//			Document doc = db.parse(new InputSource(new StringReader(xml)));
//
//			NodeList payloads = doc.getElementsByTagName("Payload");
//			if (payloads == null || payloads.getLength() == 0)
//				return null;
//
//			Node p = payloads.item(0);
//
//			// Prefer CDATA sections if present; concatenate them
//			StringBuilder sb = new StringBuilder();
//			NodeList children = p.getChildNodes();
//			boolean found = false;
//			for (int i = 0; i < children.getLength(); i++) {
//				Node n = children.item(i);
//				if (n.getNodeType() == Node.CDATA_SECTION_NODE) {
//					sb.append(((CDATASection) n).getData());
//					found = true;
//				} else if (n.getNodeType() == Node.TEXT_NODE) {
//					String t = n.getTextContent();
//					if (t != null && !t.trim().isEmpty()) {
//						sb.append(t.trim());
//						found = true;
//					}
//				}
//			}
//
//			if (found) {
//				return sb.toString();
//			}
//
//			// Fallback: serialize inner child nodes
//			StringWriter sw = new StringWriter();
//			TransformerFactory tf = TransformerFactory.newInstance();
//			Transformer t = tf.newTransformer();
//			t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
//			NodeList childNodes = p.getChildNodes();
//			for (int i = 0; i < childNodes.getLength(); i++) {
//				t.transform(new DOMSource(childNodes.item(i)), new StreamResult(sw));
//			}
//			String serialized = sw.toString().trim();
//			return serialized.isEmpty() ? null : serialized;
//
//		} catch (Exception ex) {
//			logger.warn("Failed to extract inner payload via DOM: {}. Falling back to regex.", ex.getMessage());
//			return null;
//		}
//	}
//
//	// Fallback regex extraction: concatenates ALL CDATA sections found
//	private static String extractInnerPayloadWithRegexAll(String xml) {
//		if (xml == null)
//			return null;
//		Pattern cdataPat = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
//		Matcher m = cdataPat.matcher(xml);
//		StringBuilder sb = new StringBuilder();
//		boolean found = false;
//		while (m.find()) {
//			sb.append(m.group(1));
//			found = true;
//		}
//		return found ? sb.toString() : null;
//	}
//
//	// Replace the first CDATA content (or the first Payload content) with a stable
//	// placeholder
//	private static String replaceInnerWithPlaceholder(String xml, String placeholder) {
//		if (xml == null)
//			return null;
//
//		// 1) Try replacing the first CDATA section
//		Pattern cdataPat = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
//		Matcher m = cdataPat.matcher(xml);
//		if (m.find()) {
//			return m.replaceFirst(placeholder);
//		}
//
//		// 2) If no CDATA, try to replace contents of the first <Payload>...</Payload>
//		Pattern payloadPat = Pattern.compile("(?s)(<Payload\\b[^>]*>).*?(</Payload>)");
//		Matcher pm = payloadPat.matcher(xml);
//		if (pm.find()) {
//			String before = pm.group(1);
//			String after = pm.group(2);
//			return pm.replaceFirst(before + placeholder + after);
//		}
//
//		// 3) Fallback: return original
//		return xml;
//	}
//
//	// Strip leading XML declaration if present inside inner payload
//	private static String stripXmlDecl(String s) {
//		if (s == null)
//			return null;
//		return s.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "").trim();
//	}
//
//	// Helper converts null to empty string
//	private static String safeToString(Object o) {
//		return o == null ? "" : String.valueOf(o);
//	}
//
//	private static String toRelativePath(String absolutePath) {
//		String base = Paths.get("src", "main", "resources").toAbsolutePath().toString();
//		return absolutePath.replace(base + File.separator, "").replace("\\", "/");
//	}
//
//	/**
//	 * Build a regex that matches any of the tag names (optionally with a namespace
//	 * prefix). This matches both <Tag>..</Tag> and self-closing <Tag/> forms
//	 * (namespace-aware).
//	 */
//	private static String buildSkippedTagsRegex(Set<String> tags) {
//		if (tags == null || tags.isEmpty())
//			return "";
//		List<String> perTag = new ArrayList<String>();
//		for (String t : tags) {
//			if (t == null || t.trim().isEmpty())
//				continue;
//			String tq = Pattern.quote(t.trim());
//			String openClose = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*>.*?</(?:[^:\\s>]+:)?" + tq + "\\s*>";
//			String selfClose = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*/\\s*>";
//			perTag.add("(?: " + openClose + " | " + selfClose + " )");
//		}
//		return perTag.isEmpty() ? "" : perTag.stream().collect(Collectors.joining("|"));
//	}

	
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
	                logger.info("Configuration '{}' not found on classpath — proceeding with empty SKIPPED_TAGS and PLACEHOLDERS (no skipping).", CONFIG_RESOURCE);
	            }
	        } catch (IOException e) {
	            logger.warn("Failed to load '{}', proceeding with empty SKIPPED_TAGS and PLACEHOLDERS: {}", CONFIG_RESOURCE, e.getMessage(), e);
	        }

	        // Read properties but do NOT fail if they are missing/empty — treat as empty sets
	        String skipped = props.getProperty("skipped.tags");
	        String placeholders = props.getProperty("placeholders");

	        if (skipped == null || skipped.trim().isEmpty()) {
	            SKIPPED_TAGS = new LinkedHashSet<>(); // empty => don't remove any tags
	        } else {
	            SKIPPED_TAGS = Arrays.stream(skipped.split("[,;]"))
	                    .map(String::trim)
	                    .filter(s -> !s.isEmpty())
	                    .collect(Collectors.toCollection(LinkedHashSet::new));
	        }

	        if (placeholders == null || placeholders.trim().isEmpty()) {
	            PLACEHOLDERS = new LinkedHashSet<>(); // empty => no placeholders to ignore
	        } else {
	            PLACEHOLDERS = Arrays.stream(placeholders.split("[,;]"))
	                    .map(String::trim)
	                    .filter(s -> !s.isEmpty())
	                    .collect(Collectors.toCollection(LinkedHashSet::new));
	        }

	        // Build regex (will be empty string if SKIPPED_TAGS is empty)
	        SKIPPED_TAGS_REGEX = buildSkippedTagsRegex(SKIPPED_TAGS);

	        // If no placeholders provided, use a pattern that never matches so placeholder-filtering is disabled.
	        if (!PLACEHOLDERS.isEmpty()) {
	            String joined = PLACEHOLDERS.stream().map(Pattern::quote).collect(Collectors.joining("|"));
	            PLACEHOLDER_TOKEN = Pattern.compile("\\$\\{(" + joined + ")\\}");
	        } else {
	            PLACEHOLDER_TOKEN = Pattern.compile("(?!)"); // never matches
	        }
	    }

	    /**
	     * Compare two XML files.
	     * Removes configured SKIPPED_TAGS elements from both files before diffing,
	     * and ignores differences involving configured placeholders.
	     *
	     * @param filePath1 path to first XML file (expected)
	     * @param filePath2 path to second XML file (actual)
	     * @return human-friendly comparison result
	     */
	    public static String compareXmlFiles(String filePath1, String filePath2) {
	        StringBuilder result = new StringBuilder();
	        try {
	        	String xml1 = new String(Files.readAllBytes(Paths.get(filePath1)), StandardCharsets.UTF_8);
				String xml2 = new String(Files.readAllBytes(Paths.get(filePath2)), StandardCharsets.UTF_8);

	            // Remove skipped tags from both XML strings
	            String sanitized1 = removeSkippedTags(xml1, SKIPPED_TAGS_REGEX);
	            String sanitized2 = removeSkippedTags(xml2, SKIPPED_TAGS_REGEX);

	            Diff diff = DiffBuilder.compare(Input.fromString(sanitized1))
	                                   .withTest(Input.fromString(sanitized2))
	                                   .ignoreWhitespace()
	                                   .checkForSimilar()
	                                   .build();

	            if (!diff.hasDifferences()) {
	                result.append("✅ XML files are equal.");
	            } else {
	                result.append("❌ XML files are NOT equal.\nDifferences:\n");

	                // Track unique namespace changes
	                Set<String> seenNamespaces = new HashSet<>();

	                String differences = StreamSupport.stream(diff.getDifferences().spliterator(), false)
	                        .filter(d -> {
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
	                        })
	                        .map(Difference::toString)
	                        .collect(Collectors.joining("\n"));

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

	            // Extract only the reason from the exception message (remove absolute paths & file names)
	            String cleanedMessage = e.getMessage() != null ? e.getMessage() : e.toString();
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

	    // Helper converts null to empty string
	    private static String safeToString(Object o) {
	        return o == null ? "" : String.valueOf(o);
	    }

	    private static String removeSkippedTags(String xml, String skippedRegex) {
	        if (xml == null || xml.trim().isEmpty() || skippedRegex == null || skippedRegex.isEmpty()) return xml;
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
	     * Build a regex that matches any of the tag names (optionally with a namespace prefix).
	     * This matches both <Tag>..</Tag> and self-closing <Tag/> forms (namespace-aware).
	     */
	    private static String buildSkippedTagsRegex(Set<String> tags) {
	        if (tags == null || tags.isEmpty()) return "";
	        List<String> perTag = tags.stream()
	                .filter(t -> t != null && !t.trim().isEmpty())
	                .map(t -> {
	                    String tq = Pattern.quote(t.trim());
	                    String openClose = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*>.*?</(?:[^:\\s>]+:)?" + tq + "\\s*>";
	                    String selfClose = "(?s)<(?:[^:\\s>]+:)?" + tq + "\\b[^>]*/\\s*>";
	                    return "(?:" + openClose + "|" + selfClose + ")";
	                })
	                .collect(Collectors.toList());
	        return perTag.stream().collect(Collectors.joining("|"));
	    }

}
