package com.qa.cbcc.stepdefinitions;

import static org.testng.Assert.assertTrue;

import com.qa.cbcc.utils.XmlComparator;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;

public class CompareXmlSteps {

    private String firstXmlPath;
    private String secondXmlPath;
    private String comparisonResult;

    @Given("XML file {string}")
    public void xml_file(String fileName) {
        String basePath = "src/main/resources/testData/";
        String fullPath = basePath + fileName;

        if (firstXmlPath == null) {
            firstXmlPath = fullPath;
        } else {
            secondXmlPath = fullPath;
        }
    }


    @When("I compare the two XML files")
    public void i_compare_the_two_xml_files() {
        if (firstXmlPath == null || secondXmlPath == null) {
            throw new IllegalArgumentException("Both XML file paths must be provided before comparison.");
        }
        comparisonResult = XmlComparator.compareXmlFiles(firstXmlPath, secondXmlPath);
    }

    @Then("the comparison result should indicate they are equal")
    public void the_comparison_result_should_indicate_they_are_equal() {
        assertTrue(
            comparisonResult.contains("✅ XML files are equal."),
            "Expected XML files to be equal, but got:\n" + comparisonResult
        );
    }

    @Then("the comparison result should indicate they are not equal")
    public void the_comparison_result_should_indicate_they_are_not_equal() {
        assertTrue(
            comparisonResult.contains("❌ XML files are NOT equal."),
            "Expected XML files to be NOT equal, but got:\n" + comparisonResult
        );
    }
}
