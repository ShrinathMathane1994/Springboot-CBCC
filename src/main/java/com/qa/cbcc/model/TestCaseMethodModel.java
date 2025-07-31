package com.qa.cbcc.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;


@Entity
@Table(name = "test_case_methods")  // Add this
public class TestCaseMethodModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idTC;

    @Column(name = "tc_name", nullable = false)
    private String tcName;

    @Column(name = "input_file")
    private String inputFile;

    @Column(name = "output_file")
    private String outputFile;

    @Column(name = "methods")
    private String methods;

    @Column(name = "description")
    private String description;

	public Long getIdTC() {
		return idTC;
	}

	public void setId(Long id) {
		this.idTC = id;
	}

	public String getTcName() {
		return tcName;
	}

	public void setTcName(String tcName) {
		this.tcName = tcName;
	}

	public String getInputFile() {
		return inputFile;
	}

	public void setInputFile(String inputFile) {
		this.inputFile = inputFile;
	}

	public String getOutputFile() {
		return outputFile;
	}

	public void setOutputFile(String outputFile) {
		this.outputFile = outputFile;
	}

	public String getMethods() {
		return methods;
	}

	public void setMethods(String methods) {
		this.methods = methods;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

}
