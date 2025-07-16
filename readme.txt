#For Make Server Live 
mvn clean install 
mvn spring-boot: run

#Update Postgres DB Details change under 
src\main\resources\application.properties file
spring.datasource.url=jdbc:postgresql://localhost:5432/cbcc_db - Here cbcc_db is DB Name 

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
(e.g. { "tcName": "My Multi Scenario Test1", "description": "Covers login/logout scenarios2", "featureScenarios": [ { "feature": "login.feature", "scenarios": ["Valid Login", "Invalid Login"] }, { "feature": "logout.feature", "scenarios": ["Successful Logout"] } ] })

#Get Test Case API 
GET - http://localhost:8080/api/test-cases - For All Test Cases
GET - http://localhost:8080/api/test-cases/{id} - For Specific Test Case ID

#Update Test Case API 
PUT - http://localhost:8080/api/test-cases/{id}
Body(Payload) as form-data 
inputFile Key Type as File 
outputFile Key Type as File 
data Key Type as Text 
(e.g. { "tcName": "My Multi Scenario Test1", "description": "Covers login/logout scenarios2", "featureScenarios": [ { "feature": "login.feature", "scenarios": ["Valid Login", "Invalid Login"] }, { "feature": "logout.feature", "scenarios": ["Successful Logout"] } ] })

#Get Test Case History API 
GET - http://localhost:8080/api/test-cases/{id}/history

#Delete Test Case API 
DELETE - http://localhost:8080/api/test-cases/{id}/delete

