#Prerequisites
Ensure the following are installed and enviroment path set
JDK 11+
Maven 3.6+
Postgres 17+

#Update Postgres DB Details change under 
src\main\resources\application.properties file
spring.datasource.url=jdbc:postgresql://localhost:5432/cbcc_db - Here cbcc_db is DB Name 
spring.datasource.username=postgres - DB Username
spring.datasource.password=admin - DB Pass

#Execute MigrationQuery_CBCC.txt file query in cbcc_db via PgAdmin4 GUI

#For Make Server Live do this on Project root folder
Open CMD and then execute below command
mvn clean install 
mvn spring-boot: run

#Get Git Config
GET - http://localhost:8080/api/git-config

#Update Git Config
POST - http://localhost:8080/api/git-config
Body as JSON
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

#Sync Features Scenarios
GET - http://localhost:8080/api/sync-features

#Get Methods API 
GET - http://localhost:8080/api/tests/methods

#Get Scenarios API 
GET - http://localhost:8080/api/scenarios?tags= - For All Scenarios
GET - http://localhost:8080/api/scenarios?tags=US,A2 - For Tag Specific Scenarios
OR http://localhost:8080/api/scenarios?tags=US

#Create Test Case API 
POST - http://localhost:8080/api/test-cases/create 
Body(Payload) as form-data 
inputFile Key Type as File 
outputFile Key Type as File 
data Key Type as Text 
(e.g. {"tcName": "XML Scenario-1",
  "description": "XML Comparison-1",
  "featureScenarios": [
    {
      "feature": "compareXml.feature",
      "scenarios": ["XML files have differences","XML files are identical"]
    }
  ],
  "country": "USA",
  "region": "North America",
  "pod": "Pod-01"
})

#Get Test Case API 
GET - http://localhost:8080/api/test-cases - For All Test Cases
GET - http://localhost:8080/api/test-cases/{id} - For Specific Test Case ID
GET - http://localhost:8080/api/test-cases?country=US - For Specific Country
GET - http://localhost:8080/api/test-cases?region=IN&pod=Pod-2 - - For Specific Region & Pod

#Update Test Case API 
PUT - http://localhost:8080/api/test-cases/{id}
Body(Payload) as form-data 
inputFile Key Type as File 
outputFile Key Type as File 
data Key Type as Text 
(e.g. {"tcName": "XML Scenario-1-Modified",
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
})

#Delete Test Case API 
DELETE - http://localhost:8080/api/test-cases/{id}/delete

#Run Test Case API
POST - http://localhost:8080/api/test-cases/run
Body(Payload) as JSON
{
  "testCaseIds": [1]
} 

#Get Test Case History API 
GET - http://localhost:8080/api/test-cases/{id}/history
