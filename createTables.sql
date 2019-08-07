create table Flights_Users (
  username varchar(20) not null primary key,
  pass varbinary(20) not null,
  salt varbinary(16) not null,
  balance int not null
)

create table Flights_Reservations (
  rid int identity(1, 1),
  username varchar(20) not null references Flights_Users,
  paid bit,
  info text,
  day int,
  price int,
  fid1 int,
  fid2 int,
  primary key (rid, username)
)

create table Flights_Capacity (
  fid int primary key,
  capacity int
)