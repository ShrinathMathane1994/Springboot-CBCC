package com.qa.cbcc.dto;

import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public class ExampleDTO {
    private int index;
    private int lineNumber;
    private Map<String, String> values;

    // 🔹 Only include tags if not null/empty
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> tags;

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public Map<String, String> getValues() { return values; }
    public void setValues(Map<String, String> values) { this.values = values; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
