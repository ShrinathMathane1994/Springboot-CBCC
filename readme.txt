
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
  "repoUrl": "https://github.com/ShrinathMathane1994/Springboot-CBCC.git",
  "cloneDir": "features-repo",
  "featurePath": "src/test/resources/features",
  "branch": "testingv2",
  "username": "",
  "password": "",
  "localPath": "src/test/resources/features",
  "refreshInterval": 300000
}

🔄 Sync Feature Files
GET /api/sync-features

📑 Scenarios
GET /api/scenarios?tags=
GET /api/scenarios?tags=US,A2

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

🕓 Test Case History
GET /api/test-cases/{id}/run-history
GET /api/test-cases/{id}/run-history/latest - Latest Record Only

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
