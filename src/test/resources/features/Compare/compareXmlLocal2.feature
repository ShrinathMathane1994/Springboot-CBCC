Feature: Compare two XML files for structural and content equality

  Scenario: Two files not equal
    Given XML file "input.xml"  
    And XML file "output.xml"
    When I compare the two XML files
    Then the comparison result should indicate they are not equal
    
    Scenario: Two files are equal
    Given XML file "input.xml"  
    And XML file "output.xml"
    When I compare the two XML files
    Then the comparison result should indicate they are equal