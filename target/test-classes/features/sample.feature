@APAC @A2
Feature: Login Feature

  @US
  Scenario Outline: GTRF Inbound Flow e2e Tests | "<Scenario>"
    Given user is on login page
    When user enters
    Then user should be logged in

    Examples:
  | RuleID     | Scenario                                   | RequestPath                      | Headers    |
  | rule-514003| CBPR_IB_rule-514003_PAIN001                | PAIN001_TO_MT103.xml             | US_IB_Hea  |
  | rule-514004| CBPR_IB_rule-514004_PAIN001                | PAIN001_TO_MT101.xml             | US_IB_Hea  |
