@SIT @UAT @PPD @PPD-EMEA @prodLike-EMEA @U1-POD_Day2 @Regression_Day2 @Regression @CBPR_Day2 @UKPT_CBPR_PRS_ISV_Day2 @U1-POD @ISO @GB
Feature:  Regression_U1-POD_GB_CBPR_UKPT_PRS_ISV

  Background:
    When database connection established


  Scenario Outline:- UKPT PRS_ISV Message Validation in PDP Payload Results:<Scenario>
    When Execute or Ignore for rule "<RuleId_Desc>" having excel "syncResponseAndDBValidationUKChaps/Data Driven/PRS.xlsx"
    Given Scenario "<Scenario>" with report path "syncResponseAndDBValidationUKChaps\CBPR_UKPT_PRS_ISV-TestReport.xlsx"
    Given Request with body from file "syncResponseAndDBValidationUKChaps/requests/UKPT_PRS/<RequestPath>"
    And   Random MessageId
    And   Random UETR
    And   Extract Rules data with "syncResponseAndDBValidationUKChaps/Data Driven/PRS.xlsx" for Rule Id "<RuleId_Desc>"
    And   Dynamic BizSvc "<BizSvc>"
    And   Headers from file "syncResponseAndDBValidationUKChaps/headers/<Headers>"
    And   Substitutions
      | token | {{token}} |
    And   With manual token for profile "prs"
    When  Send request to path "<Path>" 
    Then  Response status code is 200
    And   PDP Payload DB Validation "<MessageStatus>"

  @DailyRegressionPack_Day2 @CorePlatformTest_Day2 @PRS_Day2
    Examples:
      | BizSvc            | Scenario                                                                  | RuleId_Desc | RequestPath                | Path                | Headers                    | MessageStatus     |
      | swift.cbprplus.03 | U1-POD_PRS_ISV_LIQ_GB_CBPR_OB_rule-48445_Pacs.008_GPE_Rule Validation_Pos | rule-48445  | UKPT_PRS_XSSL_Pacs.008.xml | /prs/isv/initiation | UKPT_XSSL_PRS_Headers.json | SENT_TO_PROCESSOR |
      | swift.cbprplus.03 | U1-POD_PRS_ISV_LIQ_RB_CBPR_OB_rule-48446_Pacs.008_GPE_Rule Validation_Pos | rule-48446  | UKPT_PRS_XSSL_Pacs.008.xml | /prs/isv/initiation | UKPT_XSSL_PRS_Headers.json | SENT_TO_PROCESSOR |
      | swift.cbprplus.03 | U1-POD_PRS_ISV_LIQ_GB_CBPR_OB_rule-48447_Pacs.009_GPE_Rule Validation_Pos | rule-48447  | UKPT_PRS_XSSL_Pacs.009.xml | /prs/isv/initiation | UKPT_XSSL_PRS_Headers.json | SENT_TO_PROCESSOR |
      | swift.cbprplus.03 | U1-POD_PRS_ISV_LIQ_RB_CBPR_OB_rule-48448_Pacs.009_GPE_Rule Validation_Pos | rule-48448  | UKPT_PRS_XSSL_Pacs.009.xml | /prs/isv/initiation | UKPT_XSSL_PRS_Headers.json | SENT_TO_PROCESSOR |
      | swift.cbprplus.03 | U1-POD_PRS_ISV_ODY_GB_CBPR_OB_rule-48480_Pacs.008_GPE_Rule Validation_Pos | rule-48480  | UKPT_PRS_XSSL_Pacs.008.xml | /prs/isv/initiation | UKPT_XSSL_PRS_Headers.json | SENT_TO_PROCESSOR |
      | swift.cbprplus.03 | U1-POD_PRS_ISV_ODY_GB_CBPR_OB_rule-48481_Pacs.009_GPE_Rule Validation_Pos | rule-48481  | UKPT_PRS_XSSL_Pacs.009.xml | /prs/isv/initiation | UKPT_XSSL_PRS_Headers.json | SENT_TO_PROCESSOR |
      
  Scenario Outline: Compare two XML files for equality
    Given XML file "<file1>"
    And XML file "<file2>"
    When I compare the two XML files
    Then the comparison result should indicate they are <result>

    Examples:
      | file1       | file2       | result    |
      | input.xml   | output.xml  | equal     |
      | input.xml   | output.xml  | not equal |    
