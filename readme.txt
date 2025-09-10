
📘 CBCC Spring Boot Project – README

🛠️ Prerequisites
Make sure the following tools are installed and available in your system's environment PATH:

- JDK 11+
- Maven 3.6+
- PostgreSQL 17+

🗂️ Database Configuration
Edit the following file:

src/main/resources/application.properties

Update these PostgreSQL DB settings as per your local environment:

spring.datasource.url=jdbc:postgresql://localhost:5432/cbcc_db  # cbcc_db is your DB name
spring.datasource.username=postgres                             # Your DB username
spring.datasource.password=admin                                # Your DB password

🔄 Run Migration Script
Execute the SQL file MigrationQuery_CBCC.txt in your cbcc_db using PgAdmin4 or any PostgreSQL GUI.

🚀 Run the Application
From the project root folder, run the following commands:

mvn clean install
mvn spring-boot:run

🔗 API Endpoints Overview

🧬 Git Configuration
- GET  /api/git-config
- POST /api/git-config

Body (JSON):
{
    "sourceType": "local",
    "repoUrl": "https://alm-github.systems.uk.hsbc/POL/pol-testing-framework.git",
    "cloneDir": "features-repo",
    "gitFeaturePath": "e2e/src/test/resources/syncResponseAndDBValidationUKChaps",
    "branch": "master",
    "username": "45458765",
    "password": "",
    "localFeatherPath": "src/test/resources/features",
    "refreshInterval": 300000,
    "stepDefsProjectPathGit": "features-repo/e2e",
    "stepDefsProjectPathLocal": "src/test/java",
    "gluePackage": "steps",
    "mavenEnv":"uat"
}

🔄 Sync Feature Files
GET /api/sync-features

📥 Get Countries
GET /api/master/countries
➕ Create Country
POST /api/master/country
Content-Type: application/json

{
  "countryName": "United States"
}
❌ Delete Country
DELETE /api/master/country/{id}

📥 Get Regions
GET /api/master/regions → all active regions
GET /api/master/regions?countryId=1 → only regions of countryId 1
➕ Create Region
POST /api/master/region
Content-Type: application/json
{
  "regionName": "North America",
  "idCountry": 1
}
❌ Delete Region
DELETE /api/master/region/{id}


📥 Get Pods
GET /api/master/pods → all active pods
GET /api/master/pods?regionId=2 → only pods of regionId 2
➕ Create Pod
POST /api/master/pod
Content-Type: application/json

{
  "podName": "U1-POD",
  "idRegion": 2
}
❌ Delete Pod
DELETE /api/master/pod/{id}

Dashboard
/api/dashboard/rows -- For All Test Cases Run Details (Last Run & Status Wise Count)
/api/dashboard/rows/{id}/runs - For Sepcific Test Case Each Run Details

📑 Scenarios
GET /api/scenarios?tags=
GET /api/scenarios?tags=US,A2
OR
GET http://localhost:8080/api/scenarios/filter - For All Scenarios
GET http://localhost:8080/api/scenarios/filter?country=UK&region=North-America&pod=Pod-01 - For Filtering

🔧 Methods
GET /api/tests/methods

🧪 Test Case Management

➕ Create Test Case
POST /api/test-cases/create

Payload: multipart/form-data
- inputFile   → File
- outputFile  → File
- data        → Text

Example data (Text):
{
  "tcName": "TC22 - CBCC Regression – Mixed",
  "description": "Selected Outline examples",
  "country": "UK",
  "region": "EMEA",
  "pod": "Pod-01",
  "featureScenarios": [
    {
      "feature": "compareXmlLocalSrc.feature",
      "selections": [
        {
          "scenarioName": "UKPT PRS_ISV Message Validation in PDP Payload Results:<Scenario>",
          "type": "Scenario Outline",
          "selectedExamples": {
            "headers": ["BizSvc","Scenario","RuleId_Desc","RequestPath","Path","Headers","MessageStatus"],
            "rows": [
              {
                "BizSvc":"swift.cbprplus.03",
                "Scenario":"U1-POD_PRS_ISV_LIQ_GB_CBPR_OB_rule-48445_Pacs.008_GPE_Rule Validation_Pos",
                "RuleId_Desc":"rule-48445",
                "RequestPath":"UKPT_PRS_XSSL_Pacs.008.xml",
                "Path":"/prs/isv/initiation",
                "Headers":"UKPT_XSSL_PRS_Headers.json",
                "MessageStatus":"SENT_TO_PROCESSOR"
              },
              {
                "BizSvc":"swift.cbprplus.03",
                "Scenario":"U1-POD_PRS_ISV_ODY_GB_CBPR_OB_rule-48480_Pacs.008_GPE_Rule Validation_Pos",
                "RuleId_Desc":"rule-48480",
                "RequestPath":"UKPT_PRS_XSSL_Pacs.008.xml",
                "Path":"/prs/isv/initiation",
                "Headers":"UKPT_XSSL_PRS_Headers.json",
                "MessageStatus":"SENT_TO_PROCESSOR"
              }
            ]
          }
        }
      ]
    }
  ]
}
-----
{
  "tcName": "TC23 - CBCC Regression – Mixed",
  "description": "Selected Outline examples",
  "country": "UK",
  "region": "EMEA",
  "pod": "Pod-01",
  "featureScenarios": [
    {
      "feature": "compareXmlLocalSrc2.feature",
      "selections": [
        {
          "scenarioName": "Compare two XML files for equality",
          "type": "Scenario Outline",
          "selectedExamples": {
            "headers": ["file1","file2","result"],
            "rows": [
              {
                "file1":"input.xml",
                "file2":"output.xml",
                "result":"equal"              
             }            
            ]
          }
        }
      ]
    }
  ]
}
-----

📥 Get Test Cases
GET /api/test-cases
GET /api/test-cases/{id}
GET /api/test-cases?country=US
GET /api/test-cases?region=IN&pod=Pod-2

✏️ Update Test Case
PUT /api/test-cases/{id}

Payload: multipart/form-data

Example data (Text):
{
  "tcName": "XML Scenario-1-Modified",
  "description": "XML Comparison-1-Modified",
  "featureScenarios": [
    {
      "feature": "compareXmlLocalSrc.feature",
      "scenarios": ["XML files are identical"]
    }
  ],
  "country": "UK",
  "region": "South America",
  "pod": "Pod-02"
}

❌ Delete Test Case
DELETE /api/test-cases/{id}/delete

▶️ Run Test Case
POST /api/test-cases/run

Payload:
{
  "testCaseIds": [1]
}

🕓 Test Case History
GET /api/test-cases/{id}/history

🕓 Test Run Case History
GET /api/test-cases/{id}/run-history
GET /api/test-cases/{id}/run-history/latest - Latest Record Only

🕓 Testcase last run html report
http://localhost:8080/api/test-cases/{id}/html-latest

📁 Download Input/Output Files
GET /api/test-cases/{id}/download?fileType=input
GET /api/test-cases/{id}/download?fileType=output

💡 Tips for Frontend Developers

1. File Uploads:
   - Use `multipart/form-data` when sending inputFile, outputFile, and data fields.
   - Ensure 'data' field is sent as a plain text string containing JSON.

2. File Downloads:
   - Trigger a file download by calling:
     GET /api/test-cases/{id}/download?fileType=input
     GET /api/test-cases/{id}/download?fileType=output

3. Dynamic Filters:
   - Use dropdowns or chips for country, region, pod filters.
   - Combine query parameters like:
     /api/test-cases?country=US&region=North&pod=Pod-01

4. History Display:
   - Call /api/test-cases/{id}/history to get full execution history.
   - Call /api/test-cases/{id}/run-history/latest for the latest record.
   - Sort histories by execution timestamp on the frontend if needed.

5. Git Configuration UI:
   - Provide input fields for repoUrl, branch, cloneDir, etc.
   - Allow optional fields like username/password for private repos.

6. JSON Editor:
   - Use a JSON editor for complex "data" field inputs like featureScenarios.
   - Validate JSON before sending.

7. Error Handling:
   - Most API errors return in the structure:
     {
       "error": "error title",
       "details": "detailed error message"
     }
   - Handle gracefully and show detailed messages to users where appropriate.

8. Response Display:
   - Test case response contains metadata (id, tcName, description, timestamps).
   - Show these in lists or detail views.
