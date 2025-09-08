@SIT @UAT @PPD @PPD-EMEA @prodLike-EMEA @U1-POD_Day2 @Regression_Day2 @Regression @CBPR_Day2 @UKPT_CBPR_PRS_ISV_Day2 @U1-POD @ISO @GB
Feature:  Regression_U1-POD_GB_CBPR_UKPT_PRS_ISV

Scenario Outline: Compare two XML files for equality
    Given XML file "<file1>"
    And XML file "<file2>"
    When I compare the two XML files
    Then the comparison result should indicate they are <result>

    Examples:
      | file1       | file2       | result    |
      | input.xml   | output.xml  | equal     |
      | input.xml   | output.xml  | not equal |    
