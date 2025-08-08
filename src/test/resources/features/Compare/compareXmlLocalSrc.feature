@UK @North-America @Pod-01
Feature: Compare two XML files for structural and content equality using Scenario Outline

  Scenario Outline: Compare two XML files for equality
    Given XML file "<file1>"
    And XML file "<file2>"
    When I compare the two XML files
    Then the comparison result should indicate they are <result>

    Examples:
      | file1       | file2       | result    |
      | input.xml   | output.xml  | equal     |
      | input.xml   | output.xml  | not equal |