package com.qa.cbcc.dto;

import java.util.List;

public class TestCaseRunDTO {
    private List<Long> testCaseIds;

    public List<Long> getTestCaseIds() {
        return testCaseIds;
    }

    public void setTestCaseIds(List<Long> testCaseIds) {
        this.testCaseIds = testCaseIds;
    }
}
