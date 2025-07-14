#For Make Server Live
mvn clean install
mvn spring-boot: run

#Update Postgres DB Details change under src\main\resources\application.properties file

#Get Scenario API
GET - http://localhost:8080/api/scenarios?tags=US,A2 OR http://localhost:8080/api/scenarios?tags=US

#Create Test API
POST - http://localhost:8080/api/test-cases/create
Body(Payload) as form-data
inputFile Key , Type as File
outputFile Key , Type as File
data Key, Type as Text (e.g. {
  "tcName": "My Multi Scenario Test1",
  "description": "Covers login/logout scenarios2",
  "featureScenarios": [
    {
      "feature": "login.feature",
      "scenarios": ["Valid Login", "Invalid Login"]
    },
    {
      "feature": "logout.feature",
      "scenarios": ["Successful Logout"]
    }
  ]
})
