package com.qa.cbcc.utils;

import java.io.File;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

public class XmlComparator {

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
            result.append("❌ Error comparing XML files: ").append(e.getMessage());
        }

        System.out.println(result);
        return result.toString();
    }
}
