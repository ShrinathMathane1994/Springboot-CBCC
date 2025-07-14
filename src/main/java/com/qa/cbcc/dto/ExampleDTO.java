package com.qa.cbcc.dto;

import java.util.Map;

public class ExampleDTO {
    private int index;
    private int lineNumber;
    private Map<String, String> values;

    // Getters and Setters
    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public Map<String, String> getValues() { return values; }
    public void setValues(Map<String, String> values) { this.values = values; }
}
