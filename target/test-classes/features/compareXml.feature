Feature: Compare two XML files for structural and content equality

  Scenario: XML files are identical
    Given XML file "sample1.xml"
    And XML file "sample2.xml"
    When I compare the two XML files
    Then the comparison result should indicate they are equal

  Scenario: XML files have differences
    Given XML file "sample1.xml"
    And XML file "sample3.xml"
    When I compare the two XML files
    Then the comparison result should indicate they are not equal