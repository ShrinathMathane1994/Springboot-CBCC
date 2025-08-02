@US @A1
Feature: Login Feature Local v2

  @region=US @pod=A1
  Scenario Outline: Valid login
    Given user is on login page
    When user enters <username> and <password>
    Then user should be logged in

    Examples: 
      | username | password |
      | admin    | secret   |
      | shri     | shree    |

  @IND @pod=A1
  Scenario Outline: Valid login v2
    Given user is on login page
    When user enters <username> and <password>
    Then user should be logged in

    Examples: 
      | username | password |
      | admin    | secret   |
      | shri     | shree    |

  @IND
  Scenario: Valid login v3
    Given user is on login page
    When user enters <username> and <password>
    Then user should be logged in
