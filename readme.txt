
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

==================================
 Region — examples
==================================

Get all regions
---------------
GET /api/master/regions

Success (200):
{
  "data": [
    { "idRegion": 1, "regionName": "EMEA" },
    { "idRegion": 2, "regionName": "APAC" }
  ],
  "count": 2,
  "message": "OK"
}

Empty result (200):
{
  "data": [],
  "count": 0,
  "message": "No records found"
}

Create region
-------------
POST /api/master/region

Success (201):
{
  "data": { "idRegion": 3, "regionName": "North America" },
  "message": "Created"
}

Delete region
-------------
DELETE /api/master/region/{id}

204 No Content if found
404 Not Found if not

==================================
 Country — examples
==================================

Get all countries
-----------------
GET /api/master/countries

Success (200):
{
  "data": [
    { "idCountry": 1, "countryName": "GB", "idRegion": 1 },
    { "idCountry": 2, "countryName": "NZ", "idRegion": 2 }
  ],
  "count": 2,
  "message": "OK"
}

Get countries by region
-----------------------
GET /api/master/countries?region=1

Create country
--------------
POST /api/master/country

Success (201):
{
  "data": { "idCountry": 10, "countryName": "United States", "idRegion": 3 },
  "message": "Created"
}

Delete country
--------------
DELETE /api/master/country/{id}

204 No Content if found
404 Not Found if not


==================================
 Pod — examples
==================================

Get all pods
------------
GET /api/master/pods

Success (200):
{
  "data":[
    {"idPod":1,"podName":"E1-POD","idRegion":1,"idCountry":1},
    {"idPod":9,"podName":"A2-POD","idRegion":2,"idCountry":2}
  ],
  "count":2,
  "message":"OK"
}

Get pods by region
------------------
GET /api/master/pods?region=1

Get pods by country
-------------------
GET /api/master/pods?country=2

Get pods by region + country
----------------------------
GET /api/master/pods?region=2&country=2

Create pod
----------
POST /api/master/pod

Success (201):
{
  "data": { "idPod": 15, "podName": "U1-POD", "idRegion": 2, "idCountry": 2 },
  "message": "Created"
}

Delete pod
----------
DELETE /api/master/pod/{id}

204 No Content if found
404 Not Found if not
==================================

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
  "tcName": "XML Scenario-1",
  "description": "XML Comparison-1",
  "featureScenarios": [
    {
      "feature": "compareXml.feature",
      "scenarios": ["XML files have differences", "XML files are identical"]
    }
  ],
  "country": "USA",
  "region": "North America",
  "pod": "Pod-01"
}

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
http://localhost:8080/api/test-cases/{tcId}/html-latest

🕓 Testcase html report history id wise
http://localhost:8080/api/test-cases/run-history/{historyId}/html

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
