## Setup

You will need several tools to deploy this project:

- Azure
- Azure SQL Server
- Maven

## Testing

To test your solutions, type `mvn test` inside the `application` folder.
To run your solutions in an interactive mode, type `mvn clean compile assembly:single` then `java -jar target/application-1.0-jar-with-dependencies.jar` inside the `application` folder.