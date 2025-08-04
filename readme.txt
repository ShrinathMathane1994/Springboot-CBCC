#For Make Server Live 
mvn clean install 
mvn spring-boot: run

#Update Postgres DB Details change under 
src\main\resources\application.properties file
spring.datasource.url=jdbc:postgresql://localhost:5432/cbcc_db - Here cbcc_db is DB Name 

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
      "feature": "compareXml.feature",
      "scenarios": ["XML files have differences","XML files are identical"]
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

#Sync Features Scenarios
GET - http://localhost:8080/api/sync-features

