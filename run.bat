@echo off
mvn clean install
mvn test
start cmd /k "mvn spring-boot:run"
pause
