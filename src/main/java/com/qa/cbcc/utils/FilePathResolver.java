package com.qa.cbcc.utils;

import java.io.File;

public class FilePathResolver {

 public static File resolveInputFile(String inputFileName) {
     Long id = TestContext.getTestCaseId();
     return new File("src/main/resources/testData/" + id + "/" + inputFileName);
 }

 public static File resolveOutputFile(String outputFileName) {
     Long id = TestContext.getTestCaseId();
     return new File("src/main/resources/testData/" + id + "/" + outputFileName);
 }
}

