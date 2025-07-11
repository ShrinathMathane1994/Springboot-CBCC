package com.qa.cbcc.dto;

import java.util.List;

public class TestCaseDTO {
    private String tcName;
    private List<String> methods;
    private String description;
    
	public String getTcName() {
		return tcName;
	}
	public void setTcName(String tcName) {
		this.tcName = tcName;
	}
	
	public List<String> getMethods() {
		return methods;
	}
	public void setMethods(List<String> methods) {
		this.methods = methods;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
}
