@UK @North-America
Feature: Compare two XML files for structural and content equality
  
  @Pod-01
  Scenario: Two files not equal
    Given XML file "input.xml"  
    And XML file "output.xml"
    When I compare the two XML files
    Then the comparison result should indicate they are not equal
    
    @Pod-02
    Scenario: Two files are equal
    Given XML file "input.xml"  
    And XML file "output.xml"
    When I compare the two XML files
    Then the comparison result should indicate they are equal