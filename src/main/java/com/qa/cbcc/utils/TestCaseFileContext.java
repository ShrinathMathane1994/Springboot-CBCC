package com.qa.cbcc.utils;

public class TestCaseFileContext {
    private final Long testCaseId;
    private final String inputFilePath;
    private final String outputFilePath;

    public TestCaseFileContext(Long testCaseId, String inputFile, String outputFile) {
        this.testCaseId = testCaseId;
        this.inputFilePath = "src/test/resources/" + inputFile;
        this.outputFilePath = "src/test/resources/" + outputFile;
    }

    public String getInputFilePath() {
        return inputFilePath;
    }

    public String getOutputFilePath() {
        return outputFilePath;
    }
}
