package com.qa.cbcc.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.diff.JsonDiff;

import java.io.File;
import java.util.Iterator;

public class SimpleJsonComparator {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String compareJsonFiles(String filePath1, String filePath2) {
        StringBuilder result = new StringBuilder();
        try {
            JsonNode node1 = mapper.readTree(new File(filePath1));
            JsonNode node2 = mapper.readTree(new File(filePath2));

            if (node1.equals(node2)) {
                result.append("✅ JSON files are equal.");
            } else {
                result.append("❌ JSON files are NOT equal.\n");

                // Option 1: Use JsonDiff
                JsonNode diff = JsonDiff.asJson(node1, node2);
                result.append("Differences:\n").append(diff.toPrettyString()).append("\n");

                // Option 2: Use detailed difference listing
                result.append("Detailed comparison:\n");
                compareJsonNodes(node1, node2, "", result);
            }
        } catch (Exception e) {
            result.append("❌ Error comparing JSON files: ").append(e.getMessage());
        }

        System.out.println(result);
        return result.toString();
    }

    private static void compareJsonNodes(JsonNode node1, JsonNode node2, String path, StringBuilder result) {
        Iterator<String> fieldNames = node1.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            JsonNode val1 = node1.get(field);
            JsonNode val2 = node2.get(field);
            String currentPath = path.isEmpty() ? field : path + "." + field;

            if (val2 == null) {
                result.append(" - KEY missing in OUTPUT JSON: ").append(currentPath).append("\n");
            } else if (!val1.equals(val2)) {
                if (val1.isObject() && val2.isObject()) {
                    compareJsonNodes(val1, val2, currentPath, result);
                } else {
                    result.append(" - Difference in VALUE for KEY ").append(currentPath).append(":\n")
                          .append("   INPUT: ").append(val1).append("\n")
                          .append("   OUTPUT: ").append(val2).append("\n");
                }
            }
        }

        // Check for fields missing in the first JSON
        Iterator<String> fieldNames2 = node2.fieldNames();
        while (fieldNames2.hasNext()) {
            String field = fieldNames2.next();
            if (!node1.has(field)) {
                String currentPath = path.isEmpty() ? field : path + "." + field;
                result.append(" - KEY missing in INPUT JSON: ").append(currentPath).append("\n");
            }
        }
    }
}
