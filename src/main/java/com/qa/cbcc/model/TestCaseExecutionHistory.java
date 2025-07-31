package com.qa.cbcc.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

@Entity
@Data
public class TestCaseExecutionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long testCaseId;
    private String status;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executionOn;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String outputLog;


	public Long getId() {
		return id;
	}


	public void setId(Long id) {
		this.id = id;
	}


	public Long getTestCaseId() {
		return testCaseId;
	}


	public void setTestCaseId(Long testCaseId) {
		this.testCaseId = testCaseId;
	}


	public String getStatus() {
		return status;
	}


	public void setStatus(String status) {
		this.status = status;
	}


	public LocalDateTime getExecutionOn() {
		return executionOn;
	}


	public void setExecutionOn(LocalDateTime executionOn) {
		this.executionOn = executionOn;
	}


	public String getOutputLog() {
		return outputLog;
	}


	public void setOutputLog(String outputLog) {
		this.outputLog = outputLog;
	}
}
