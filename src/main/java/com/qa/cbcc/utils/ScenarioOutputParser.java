package com.qa.cbcc.utils;

import java.util.*;
import java.util.regex.*;

public class ScenarioOutputParser {

    public static class ParsedScenarioStatus {
        public String name;
        public String status;
        public String failedStep;
        public String message;
        public String featureFile;
    }

    public static class ParseResult {
        public Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();
        public Set<String> executedScenarioNames = new LinkedHashSet<>();
        public List<String> passedNames = new ArrayList<>();
        public List<String> failedNames = new ArrayList<>();
        public Set<String> unexecuted = new HashSet<>();
        public List<String> missingFeatures = new ArrayList<>();
        public String statusByExecution;
        public long durationMillis;
    }

    public static List<ParsedScenarioStatus> parseScenarioBlocks(String fullConsoleOutput) {
        List<ParsedScenarioStatus> results = new ArrayList<>();
        String[] lines = fullConsoleOutput.split("\\r?\\n");
        ParsedScenarioStatus current = null;

        for (String rawLine : lines) {
            String line = rawLine.replaceAll("\\u001B\\[[;\\d]*m", "").trim();

            if (line.startsWith("Scenario:")) {
                if (current != null) results.add(current);
                current = new ParsedScenarioStatus();
                current.name = line.replace("Scenario:", "").replaceAll("#.*", "").trim();
                current.status = "Passed";
            }

            if (current == null) continue;

            if (line.contains("java.lang.AssertionError") || line.toLowerCase().contains("expected [") ||
                line.toLowerCase().contains("assert") || line.toLowerCase().contains("but found")) {
                current.status = "Failed";
                current.message = line;
            }

            if (line.matches("^(Then|When|Given|And|But) .*")) {
                if (line.contains("expected") && current.failedStep == null) {
                    current.failedStep = line;
                }
            }
        }

        if (current != null) results.add(current);
        return results;
    }

    public static ParseResult parse(String fullOutput, List<ParsedScenarioStatus> blocks,
                                    Set<String> expectedScenarioNames, Map<String, String> scenarioToFeatureMap) {
        ParseResult result = new ParseResult();
        long start = System.currentTimeMillis();

        for (ParsedScenarioStatus block : blocks) {
            result.executedScenarioNames.add(block.name);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scenarioName", block.name);
            map.put("featureFileName", block.featureFile);
            map.put("status", block.status);

            if ("Failed".equals(block.status)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("xpath", null);
                diff.put("differenceType", "Assertion Result");
                diff.put("description", block.message);
                if (block.failedStep != null) diff.put("failedStep", block.failedStep);
                map.put("parsedDifferences", List.of(diff));
                map.put("diffCount", 1);
                result.failedNames.add(block.name);
            } else {
                result.passedNames.add(block.name);
            }

            result.executedScenarios.add(map);
        }

        result.unexecuted.addAll(expectedScenarioNames);
        result.unexecuted.removeAll(result.executedScenarioNames);

        boolean anyFailed = !result.failedNames.isEmpty();
        boolean allFailed = result.executedScenarios.size() == result.failedNames.size();
        result.statusByExecution = allFailed ? "Failed" : anyFailed ? "Partially Passed" : "Passed";
        result.durationMillis = System.currentTimeMillis() - start;

        return result;
    }
}
