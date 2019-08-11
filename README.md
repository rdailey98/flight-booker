## Overview
This is a simple flight booking application built on top of an Azure database which uses flight data drawn from the US Department of Transportation. It is fully supportive of concurrent transactions and multi-user interaction through the use of 2-phase locking. This application IS NOT a real tool for booking reservations, rather a fun tool to play around with, and an introduction to distributed system design and computing.

See the "Testing" section below for how to run the application.

Once the application is running, you may use the following commands on any command line interface:
- `create <username> <password> <initial amount>`
- `login <username> <password>`
- `search <origin city> <destination city> <direct> <day> <num itineraries>`
- `book <itinerary id>`
- `pay <reservation id>`
- `reservations`
- `cancel <reservation id>`
- `quit`

## Tools and Skills Learned:
- SQL/Java
- Microsoft Azure
- Maven
- Concurency management (2-phase locking)
- SQL injection prevention
- Relational database deployment (Microsoft Azure)
- Scaled database and schema design
- Table indexing for improved query efficiency
- Unit testing
- Concurency testing

## Importing Flight Data
Use the following SQL statements to import the flight data into your database:

`CREATE EXTERNAL DATA SOURCE rdailey98-flight-blob
WITH (TYPE = BLOB_STORAGE,
      LOCATION = 'https://rdailey98-flight.blob.core.windows.net/flights'
);`

`bulk insert Carriers from 'carriers.csv'
with (ROWTERMINATOR = '0x0a',
      DATA_SOURCE = 'cse344blob',
      FORMAT='CSV',
      CODEPAGE = 65001,
      FIRSTROW=1,
      TABLOCK);`

`bulk insert Months from 'months.csv'
with (ROWTERMINATOR = '0x0a',
      DATA_SOURCE = 'cse344blob',
      FORMAT='CSV',
      CODEPAGE = 65001,
      FIRSTROW=1,
      TABLOCK);`

`bulk insert Weekdays from 'weekdays.csv'
with (ROWTERMINATOR = '0x0a',
      DATA_SOURCE = 'cse344blob',
      FORMAT='CSV',
      CODEPAGE = 65001,
      FIRSTROW=1,
      TABLOCK);`

`bulk insert Flights from 'flights-small.csv'
with (ROWTERMINATOR = '0x0a',
      DATA_SOURCE = 'cse344blob',
      FORMAT='CSV',
      CODEPAGE = 65001,
      FIRSTROW=1,
      TABLOCK);`

## Setup

You will need several tools to deploy this project:

- Azure
- Azure SQL Server
- Maven

## Testing

To test your solutions, type `mvn test` inside the `application` folder.
To run the application in an interactive mode, type `mvn clean compile assembly:single` then `java -jar target/application-1.0-jar-with-dependencies.jar` inside the `application` folder.
