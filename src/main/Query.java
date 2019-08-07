import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.*;
import java.util.jar.Attributes;

/**
 * Runs queries against a back-end database
 */
public class Query {
    // DB Connection
    private Connection conn;

    // Password hashing parameter constants
    private static final int HASH_STRENGTH = 65536;
    private static final int KEY_LENGTH = 128;
    private static final int ATTEMPTS = 5;

    // Flags
    private static final boolean debug = false;

    // Open user session data
    private boolean openSession = false;
    private String openUser;

    // Search and reservation data
    private int itineraryNum = 0;   // Used to record number of search results
    private List<Itinerary> directResults = new ArrayList<>();
    private List<Itinerary> indirectResults = new ArrayList<>();
    private SortedMap<Integer, Itinerary> combinedResults = new TreeMap<>();

    // Canned queries
    private static final String BEGIN_TRANSACTION_SQL =
            "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE; BEGIN TRANSACTION;";
    protected PreparedStatement beginTransactionStatement;

    private static final String COMMIT_SQL = "COMMIT TRANSACTION";
    protected PreparedStatement commitTransactionStatement;

    private static final String ROLLBACK_SQL = "ROLLBACK TRANSACTION";
    protected PreparedStatement rollbackTransactionStatement;

    private static final String CHECK_FLIGHT_CAPACITY =
            "SELECT capacity " +
                    "FROM Flights " +
                    "WHERE fid = ?";
    private PreparedStatement checkFlightCapacityStatement;

    private static final String CLEAR_FLIGHTS_USERS =
            "DELETE FROM Flights_Users";
    private Statement clearFlightsUsersStatement;

    private static final String CLEAR_FLIGHTS_RESERVATIONS =
            "DELETE FROM Flights_Reservations " +
                    "DBCC CHECKIDENT('Flights_Reservations', RESEED, 0)";
    private Statement clearFlightsReservationsStatement;

    private static final String CLEAR_FLIGHTS_CAPACITY =
            "DELETE FROM Flights_Capacity";
    private Statement clearFlightsCapacityStatement;

    private static final String CREATE_USER =
            "INSERT INTO Flights_Users " +
                    "VALUES(?, ?, ?, ?)";
    private PreparedStatement createUserStatement;

    private static final String CHECK_USER_EXISTS =
            "SELECT *" +
                    "FROM Flights_Users " +
                    "WHERE username = ?";
    private PreparedStatement checkUserExistsStatement;

    private static final String GET_USER =
            "SELECT * " +
                    "FROM Flights_Users " +
                    "WHERE username = ?";
    private PreparedStatement getUserStatement;

    private static final String GET_FLIGHT =
            "SELECT TOP (?) fid, day_of_month, carrier_id, flight_num, origin_city, dest_city," +
                    "actual_time, capacity, price " +
                    "FROM Flights " +
                    "WHERE origin_city = ? AND dest_city = ? AND day_of_month = ? " +
                    "AND canceled = 0 " +
                    "ORDER BY actual_time, fid ASC";
    private PreparedStatement getFlightStatement;

    private static final String GET_INTERMEDIATE_FLIGHT =
            "WITH Stop_One AS (" +
                    "SELECT * " +
                    "FROM Flights " +
                    "WHERE origin_city = ? AND day_of_month = ?) " +
                    "SELECT TOP (?) s.fid AS s_fid, s.day_of_month AS s_day_of_month, s.carrier_id AS " +
                    "s_carrier_id, s.flight_num AS s_flight_num, s.origin_city AS " +
                    "s_origin_city, s.dest_city AS s_dest_city, s.actual_time AS " +
                    "s_actual_time, s.capacity AS s_capacity, s.price AS s_price, " +
                    "s.canceled AS s_canceled, " +
                    "f.fid AS f_fid, f.day_of_month AS f_day_of_month, f.carrier_id AS " +
                    "f_carrier_id, f.flight_num AS f_flight_num, f.origin_city AS " +
                    "f_origin_city, f.dest_city AS f_dest_city, f.actual_time AS " +
                    "f_actual_time, f.capacity AS f_capacity, f.price AS f_price, " +
                    "f.canceled AS f_canceled " +
                    "FROM Stop_One as s, Flights as f " +
                    "WHERE s.dest_city = f.origin_city AND f.dest_city = ? AND f.day_of_month = ? " +
                    "AND s.canceled = 0 AND f.canceled = 0 " +
                    "ORDER BY (s.actual_time + f.actual_time), s.fid, f.fid ASC";
    private PreparedStatement getIntermediateFlightStatement;

    private static final String CHECK_RESERVATION_DAY_EXISTS =
            "SELECT * " +
                    "FROM Flights_Reservations " +
                    "WHERE username = ? AND day = ?";
    private PreparedStatement checkReservationDayExistsStatement;

    private static final String BOOK_RESERVATION =
            "INSERT INTO Flights_Reservations (username, paid, info, day, price, fid1, fid2) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?)";
    private PreparedStatement bookReservationStatement;

    private static final String CHECK_USER_RESERVATION_EXISTS =
            "SELECT * " +
                    "FROM Flights_Reservations " +
                    "WHERE rid = ? AND username = ?";
    private PreparedStatement checkUserReservationExists;

    private static final String PAY_RESERVATION =
            "UPDATE Flights_Users " +
                    "SET balance = balance - ? " +
                    "OUTPUT inserted.balance AS balance " +
                    "WHERE username = ?";
    private PreparedStatement payReservationStatement;

    private static final String MARK_AS_PAID =
            "UPDATE Flights_Reservations " +
                    "SET paid = 1 " +
                    "WHERE username = ? AND rid = ?";
    private PreparedStatement markAsPaidStatement;

    private static final String GET_USER_RESERVATIONS =
            "SELECT * " +
                    "FROM Flights_Reservations " +
                    "WHERE username = ?";
    private PreparedStatement getUserReservationsStatement;

    private static final String REFUND_RESERVATION =
            "UPDATE Flights_Users " +
                    "SET Balance = balance + ? " +
                    "WHERE username = ?";
    private PreparedStatement refundReservationStatement;

    private static final String DELETE_RESERVATION =
            "DELETE FROM Flights_Reservations " +
                    "WHERE rid = ? AND username = ?";
    private PreparedStatement deleteReservationStatement;

    private static final String UPDATE_BOOKED_CAPACITY =
            "IF EXISTS (SELECT * FROM Flights_Capacity WHERE fid = ?) " +
                    "UPDATE Flights_Capacity " +
                    "SET capacity = capacity + ? " +
                    "WHERE fid = ? " +
                    "ELSE " +
                    "INSERT INTO Flights_Capacity (fid, capacity) " +
                    "VALUES (?, 1)";
    private PreparedStatement updateBookedCapacityStatement;

    private static final String GET_BOOKED_CAPACITY =
            "SELECT capacity " +
                    "FROM Flights_Capacity " +
                    "WHERE fid = ?";
    private PreparedStatement getBookedCapacityStatement;

    private static final String REMOVE_BOOKED_CAPACITY =
            "DELETE FROM Flights_Capacity " +
                    "WHERE fid = ?";
    private PreparedStatement removeBookedCapacityStatement;

    /**
     * Establishes a new application-to-database connection. Uses the
     * dbconn.properties configuration settings
     *
     * @throws IOException
     * @throws SQLException
     */
    public void openConnection() throws IOException, SQLException {
        // Connect to the database with the provided connection configuration
        Properties configProps = new Properties();
        configProps.load(new FileInputStream("dbconn.properties"));
        String serverURL = configProps.getProperty("hw1.server_url");
        String dbName = configProps.getProperty("hw1.database_name");
        String adminName = configProps.getProperty("hw1.username");
        String password = configProps.getProperty("hw1.password");
        String connectionUrl = String.format(
                "jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
                dbName, adminName, password);
        conn = DriverManager.getConnection(connectionUrl);

        // By default, automatically commit after each statement
        conn.setAutoCommit(false);

        // By default, set the transaction isolation level to serializable
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    /**
     * Closes the application-to-database connection
     */
    public void closeConnection() throws SQLException {
        conn.close();
    }

    /**
     * Clear the data in any custom tables created.
     * <p>
     * WARNING! Do not drop any tables and do not clear the flights table.
     */
    public void clearTables() {
        directResults.clear();
        indirectResults.clear();
        combinedResults.clear();
        openSession = false;
        openUser = "";
        try {
            beginTransaction();
            clearFlightsReservationsStatement.execute(CLEAR_FLIGHTS_RESERVATIONS);
            clearFlightsUsersStatement.execute(CLEAR_FLIGHTS_USERS);
            clearFlightsCapacityStatement.execute(CLEAR_FLIGHTS_CAPACITY);
            commitTransaction();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * prepare all the SQL statements in this method.
     */
    public void prepareStatements() throws SQLException {
        // Transaction statements
        beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION_SQL);
        commitTransactionStatement = conn.prepareStatement(COMMIT_SQL);
        rollbackTransactionStatement = conn.prepareStatement(ROLLBACK_SQL);

        // Flight statements
        checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
        clearFlightsUsersStatement = conn.createStatement();
        clearFlightsReservationsStatement = conn.createStatement();
        clearFlightsCapacityStatement = conn.createStatement();
        createUserStatement = conn.prepareStatement(CREATE_USER);
        checkUserExistsStatement = conn.prepareStatement(CHECK_USER_EXISTS);
        getUserStatement = conn.prepareStatement(GET_USER);
        getFlightStatement = conn.prepareStatement(GET_FLIGHT);
        getIntermediateFlightStatement = conn.prepareStatement(GET_INTERMEDIATE_FLIGHT);
        checkReservationDayExistsStatement = conn.prepareStatement(CHECK_RESERVATION_DAY_EXISTS);
        bookReservationStatement = conn.prepareStatement(BOOK_RESERVATION,
                bookReservationStatement.RETURN_GENERATED_KEYS);
        checkUserReservationExists = conn.prepareStatement(CHECK_USER_RESERVATION_EXISTS);
        payReservationStatement = conn.prepareStatement(PAY_RESERVATION);
        markAsPaidStatement = conn.prepareStatement(MARK_AS_PAID);
        getUserReservationsStatement = conn.prepareStatement(GET_USER_RESERVATIONS);
        refundReservationStatement = conn.prepareStatement(REFUND_RESERVATION);
        deleteReservationStatement = conn.prepareStatement(DELETE_RESERVATION);
        updateBookedCapacityStatement = conn.prepareStatement(UPDATE_BOOKED_CAPACITY);
        getBookedCapacityStatement = conn.prepareStatement(GET_BOOKED_CAPACITY);
        removeBookedCapacityStatement = conn.prepareStatement(REMOVE_BOOKED_CAPACITY);
    }

    public void beginTransaction() throws SQLException {
        conn.setAutoCommit(false);
        beginTransactionStatement.executeUpdate();
    }

    public void commitTransaction() throws SQLException {
        commitTransactionStatement.executeUpdate();
        conn.setAutoCommit(true);
    }

    public void rollbackTransaction() throws SQLException {
        rollbackTransactionStatement.executeUpdate();
        conn.setAutoCommit(true);
    }

    /**
     * Takes a user's username and password and attempts to log the user in.
     *
     * @param username user's username
     * @param password user's password
     * @return If someone has already logged in, then return "User already logged
     * in\n" For all other errors, return "Login failed\n". Otherwise,
     * return "Logged in as [username]\n".
     */
    public String transaction_login(String username, String password) {
        // Check if user account exists
        for (int i = 0; i < ATTEMPTS; i++) {
            try {
                beginTransaction();
                if (openSession) {
                    commitTransaction();
                    return "User already logged in\n";
                }

                //StringBuffer sb = new StringBuffer();
                // Convert the username to lowercase
                String lcUsername = username.toLowerCase();
                directResults.clear();
                indirectResults.clear();
                combinedResults.clear();

                checkUserExistsStatement.clearParameters();
                checkUserExistsStatement.setString(1, lcUsername);
                ResultSet existsResult = checkUserExistsStatement.executeQuery();

                if (!existsResult.isBeforeFirst()) {
                    commitTransaction();
                    return "Login failed\n";
                }

                getUserStatement.clearParameters();
                getUserStatement.setString(1, lcUsername);
                ResultSet userResult = getUserStatement.executeQuery();
                userResult.next();
                byte[] queryHash = userResult.getBytes(2);
                byte[] querySalt = userResult.getBytes(3);

                byte[] userHash = hashPassword(password, querySalt);

                // Check if password is correct
                if (Arrays.equals(queryHash, userHash)) {
                    // Password is correct, open user session
                    openSession = true;
                    openUser = lcUsername;
                    commitTransaction();
                    return "Logged in as " + username + "\n";
                } else {
                    commitTransaction();
                    return "Login failed\n";
                }
            } catch (SQLException ex) {
                if (debug) {
                    printSQLException(ex);
                }
                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    printSQLException(ex2);
                }
            }
        }
        return "Login failed\n";
    }

    /**
     * Implement the create user function.
     *
     * @param username   new user's username. User names are unique the system.
     * @param password   new user's password.
     * @param initAmount initial amount to deposit into the user's account, should
     *                   be >= 0 (failure otherwise).
     * @return either "Created user {@code username}\n" or "Failed to create user\n"
     * if failed.
     */
    public String transaction_createCustomer(String username, String password, int initAmount) {
        for (int i = 0; i < ATTEMPTS; i++) {
            try {
                beginTransaction();
                if (username.length() > 20 || password.length() > 20 || initAmount < 0) {
                    rollbackTransaction();
                    continue;
                }

                // Convert the username to lowercase
                String lcUsername = username.toLowerCase();

                checkUserExistsStatement.clearParameters();
                checkUserExistsStatement.setString(1, lcUsername);
                ResultSet existsResult = checkUserExistsStatement.executeQuery();
                if (existsResult.isBeforeFirst()) {
                    rollbackTransaction();
                    continue;
                }
				// Generate a random cryptographic salt
				SecureRandom random = new SecureRandom();
				byte[] salt = new byte[16];
				random.nextBytes(salt);

                byte[] hash = hashPassword(password, salt);

                createUserStatement.clearParameters();
                createUserStatement.setString(1, lcUsername);
                createUserStatement.setBytes(2, hash);
                createUserStatement.setBytes(3, salt);
                createUserStatement.setInt(4, initAmount);
                createUserStatement.execute();
                commitTransaction();
                return "Created user " + username + "\n";
            } catch (SQLException ex) {
                if (debug) {
                    printSQLException(ex);
                }

                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    if (debug) {
                        printSQLException(ex2);
                    }
                }
            }
        }
        return "Failed to create user\n";
    }

    /**
     * Implement the search function.
     * <p>
     * Searches for flights from the given origin city to the given destination
     * city, on the given day of the month. If {@code directFlight} is true, it only
     * searches for direct flights, otherwise is searches for direct flights and
     * flights with two "hops." Only searches for up to the number of itineraries
     * given by {@code numberOfItineraries}.
     * <p>
     * The results are sorted based on total flight time.
     *
     * @param originCity
     * @param destinationCity
     * @param directFlight        if true, then only search for direct flights,
     *                            otherwise include indirect flights as well
     * @param dayOfMonth
     * @param numberOfItineraries number of itineraries to return
     * @return If no itineraries were found, return "No flights match your
     * selection\n". If an error occurs, then return "Failed to search\n".
     * <p>
     * Otherwise, the sorted itineraries printed in the following format:
     * <p>
     * Itinerary [itinerary number]: [number of flights] flight(s), [total
     * flight time] minutes\n [first flight in itinerary]\n ... [last flight
     * in itinerary]\n
     * <p>
     * Each flight should be printed using the same format as in the
     * {@code Flight} class. Itinerary numbers in each search should always
     * start from 0 and increase by 1.
     * @see Flight#toString()
     */
    public String transaction_search(String originCity, String destinationCity,
                                     boolean directFlight, int dayOfMonth,
                                     int numberOfItineraries) {
        // Search for flights based on given parameters
        for (int j = 0; j < ATTEMPTS; j++) {
            try {

                beginTransaction();
                StringBuffer sb = new StringBuffer();

                // Clear the previously stored search results
                itineraryNum = 0;
                directResults.clear();
                indirectResults.clear();
                combinedResults.clear();

                getFlightStatement.clearParameters();
                getFlightStatement.setInt(1, numberOfItineraries);
                getFlightStatement.setString(2, originCity);
                getFlightStatement.setString(3, destinationCity);
                getFlightStatement.setInt(4, dayOfMonth);

                // Query for direct flights first
                ResultSet directResult = getFlightStatement.executeQuery();

                while (directResult.next()) {
                    Flight f = new Flight();
                    // Retrieve all necessary data about the flight and store in flight object
                    f.fid = directResult.getInt("fid");
                    f.dayOfMonth = directResult.getInt("day_of_month");
                    f.carrierId = directResult.getString("carrier_id");
                    f.flightNum = directResult.getString("flight_num");
                    f.originCity = directResult.getString("origin_city");
                    f.destCity = directResult.getString("dest_city");
                    f.time = directResult.getInt("actual_time");
                    f.capacity = directResult.getInt("capacity");
                    f.price = directResult.getInt("price");

                    // Store flight and important data in itinerary object
                    Itinerary direct = new Itinerary();
                    direct.f1 = f;
                    direct.totalTime = f.time;
                    direct.dayOfMonth = f.dayOfMonth;
                    direct.totalCost = f.price;
                    direct.numFlights = 1;

                    // Get the number of bookings for this flight
                    getBookedCapacityStatement.clearParameters();
                    getBookedCapacityStatement.setInt(1, f.fid);
                    ResultSet capacityResult = getBookedCapacityStatement.executeQuery();

                    int fCapacity = f.capacity;
                    if (capacityResult.isBeforeFirst()) {
                        capacityResult.next();
                        fCapacity -= capacityResult.getInt("capacity");
                    }

                    if (fCapacity < 1) {
                        direct.full = true;
                    }

                    // Add the itinerary to the direct flight search results
                    directResults.add(direct);
                    itineraryNum++;
                }

                // If the user enabled indirect flights and the max number of results hasn't been found,
                // then search for more indirect flights
                if (!directFlight && itineraryNum < numberOfItineraries) {
                    getIntermediateFlightStatement.clearParameters();
                    getIntermediateFlightStatement.setString(1, originCity);
                    getIntermediateFlightStatement.setInt(2, dayOfMonth);
                    getIntermediateFlightStatement.setInt(3,
                            numberOfItineraries - (itineraryNum));
                    getIntermediateFlightStatement.setString(4, destinationCity);
                    getIntermediateFlightStatement.setInt(5, dayOfMonth);

                    // Query for the indirect flight
                    ResultSet intermediateResult = getIntermediateFlightStatement.executeQuery();

                    while (intermediateResult.next()) {
                        Flight f1 = new Flight();
                        Flight f2 = new Flight();

                        // Retrieve all necessary information about the first flight
                        f1.fid = intermediateResult.getInt("s_fid");
                        f1.dayOfMonth = intermediateResult.getInt("s_day_of_month");
                        f1.carrierId = intermediateResult.getString("s_carrier_id");
                        f1.flightNum = intermediateResult.getString("s_flight_num");
                        f1.originCity = intermediateResult.getString("s_origin_city");
                        f1.destCity = intermediateResult.getString("s_dest_city");
                        f1.time = intermediateResult.getInt("s_actual_time");
                        f1.capacity = intermediateResult.getInt("s_capacity");
                        f1.price = intermediateResult.getInt("s_price");

                        // Retrieve all necessary information about the second flight
                        f2.fid = intermediateResult.getInt("f_fid");
                        f2.dayOfMonth = intermediateResult.getInt("f_day_of_month");
                        f2.carrierId = intermediateResult.getString("f_carrier_id");
                        f2.flightNum = intermediateResult.getString("f_flight_num");
                        f2.originCity = intermediateResult.getString("f_origin_city");
                        f2.destCity = intermediateResult.getString("f_dest_city");
                        f2.time = intermediateResult.getInt("f_actual_time");
                        f2.capacity = intermediateResult.getInt("f_capacity");
                        f2.price = intermediateResult.getInt("f_price");

                        // Store flights and important data in itinerary object
                        Itinerary indirect = new Itinerary();
                        indirect.f1 = f1;
                        indirect.f2 = f2;
                        indirect.totalTime = f1.time + f2.time;
                        indirect.dayOfMonth = f1.dayOfMonth;
                        indirect.totalCost = f1.price + f2.price;
                        indirect.numFlights = 2;

                        // Get the number of bookings for flight1
                        getBookedCapacityStatement.clearParameters();
                        getBookedCapacityStatement.setInt(1, f1.fid);
                        ResultSet capacityResult1 = getBookedCapacityStatement.executeQuery();


                        int f1Capacity = f1.capacity;
                        if (capacityResult1.isBeforeFirst()) {
                            capacityResult1.next();
                            f1Capacity -= capacityResult1.getInt("capacity");
                        }

                        // Get the number of bookings for flight2
                        getBookedCapacityStatement.clearParameters();
                        getBookedCapacityStatement.setInt(1, f2.fid);
                        ResultSet capacityResult2 = getBookedCapacityStatement.executeQuery();

                        int f2Capacity = f2.capacity;
                        if (capacityResult2.isBeforeFirst()) {
                            capacityResult2.next();
                            f2Capacity -= capacityResult2.getInt("capacity");
                        }

                        if (f1Capacity < 1 || f2Capacity < 1) {
                            indirect.full = true;
                        }

                        // Add itinerary to indirect search results
                        indirectResults.add(indirect);
                        itineraryNum++;
                    }
                }

                // Iterate through both the direct and indirect search results, adding to a combined
                // table ordered by flight time and flight Id
                int id = 0;
                while (directResults.size() != 0 && indirectResults.size() != 0) {
                    if (directResults.get(0).totalTime < indirectResults.get(0).totalTime) {
                        combinedResults.put(id, directResults.remove(0));
                    } else if (directResults.get(0).totalTime > indirectResults.get(0).totalTime) {
                        combinedResults.put(id, indirectResults.remove(0));
                    } else {
                        if (directResults.get(0).f1.fid < indirectResults.get(0).f1.fid) {
                            combinedResults.put(id, directResults.remove(0));
                        } else {
                            combinedResults.put(id, indirectResults.remove(0));
                        }
                    }
                    id++;
                }

                // Add any remaining flights to the combined table
                if (directResults.size() != 0) {
                    while (directResults.size() != 0) {
                        combinedResults.put(id, directResults.remove(0));
                        id++;
                    }
                } else if (indirectResults.size() != 0) {
                    while (indirectResults.size() != 0) {
                        combinedResults.put(id, indirectResults.remove(0));
                        id++;
                    }
                }

                // Generate the itinerary string for each itinerary in the combined table
                int itineraryId = 0;
                for (Map.Entry<Integer, Itinerary> entry : combinedResults.entrySet()) {
                    String itineraryString;
                    Itinerary i = entry.getValue();
                    itineraryString = "Itinerary " + itineraryId + ": " + i.numFlights + " flight(s), " + i.totalTime +
                            " minutes\n" + i.f1.toString() + "\n";

                    if (i.f2 != null) {
                        itineraryString += i.f2.toString() + "\n";
                    }

                    entry.getValue().itinerary = itineraryString;
                    itineraryId++;
                    sb.append(itineraryString);
                }
                commitTransaction();
                // If no search results were found, clear the tables and let the user know
                if (sb.toString().length() == 0) {
                    directResults.clear();
                    indirectResults.clear();
                    combinedResults.clear();
                    itineraryNum = 0;
                    return "No flights match your selection\n";
                } else {
                    return sb.toString();
                }
            } catch (SQLException ex) {
                if (debug) {
                    ex.printStackTrace();
                    printSQLException(ex);
                }

                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    if (debug) {
                        printSQLException(ex2);
                    }
                }
            }
        }
        return "Failed to search\n";
    }

    /**
     * Implements the book itinerary function.
     *
     * @param itineraryId ID of the itinerary to book. This must be one that is
     *                    returned by search in the current session.
     * @return If the user is not logged in, then return "Cannot book reservations,
     * not logged in\n". If try to book an itinerary with invalid ID, then
     * return "No such itinerary {@code itineraryId}\n". If the user already
     * has a reservation on the same day as the one that they are trying to
     * book now, then return "You cannot book two flights in the same
     * day\n". For all other errors, return "Booking failed\n".
     * <p>
     * And if booking succeeded, return "Booked flight(s), reservation ID:
     * [reservationId]\n" where reservationId is a unique number in the
     * reservation system that starts from 1 and increments by 1 each time a
     * successful reservation is made by any user in the system.
     */
    public String transaction_book(int itineraryId) {
        // Check if a reservation has already been made on the same day
        for (int j = 0; j < ATTEMPTS; j++) {
            try {
                beginTransaction();
                // Check if the user is logged in
                if (!openSession) {
                    commitTransaction();
                    return "Cannot book reservations, not logged in\n";
                }

                // Check if the iteneraryId is valid
                if (!(itineraryId >= 0 && itineraryId <= itineraryNum)) {
                    commitTransaction();
                    return "No such itinerary " + itineraryId + "\n";
                }

                // Check if there are any search results
                if (combinedResults == null || combinedResults.keySet().size() == 0) {
                    return "No such itinerary " + itineraryId + "\n";
                }
                Itinerary i = combinedResults.get(itineraryId);
                if (i == null) {
                    return "No such itinerary " + itineraryId + "\n";
                }
                int dayOfMonth = i.dayOfMonth;

                if (combinedResults.get(itineraryId).full) {
                    return "Booking failed\n";
                }

                checkReservationDayExistsStatement.clearParameters();
                checkReservationDayExistsStatement.setString(1, openUser);
                checkReservationDayExistsStatement.setInt(2, dayOfMonth);
                ResultSet existsResult = checkReservationDayExistsStatement.executeQuery();
                if (existsResult.isBeforeFirst()) {
                    commitTransaction();
                    return "You cannot book two flights in the same day\n";
                }

                bookReservationStatement.clearParameters();
                bookReservationStatement.setString(1, openUser);
                bookReservationStatement.setInt(2, 0);
                bookReservationStatement.setString(3, combinedResults.get(itineraryId).itinerary);
                bookReservationStatement.setInt(4, dayOfMonth);
                bookReservationStatement.setInt(5, combinedResults.get(itineraryId).totalCost);
                bookReservationStatement.setInt(6, i.f1.fid);
                if (i.numFlights == 2) {
                    bookReservationStatement.setInt(7, i.f2.fid);
                } else {
                    bookReservationStatement.setInt(7, -1);
                }
                int bookingResult = bookReservationStatement.executeUpdate();

                // If the booking was successful, retrieve the unique reservation ID
                if (bookingResult == 0) {
                    rollbackTransaction();
                    return "Booking failed\n";
                } else {
                    try (ResultSet generatedKeys = bookReservationStatement.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            // Update the capacity of the flight
                            updateBookedCapacityStatement.clearParameters();
                            updateBookedCapacityStatement.setInt(1, i.f1.fid);
                            updateBookedCapacityStatement.setInt(2, 1);
                            updateBookedCapacityStatement.setInt(3, i.f1.fid);
                            updateBookedCapacityStatement.setInt(4, i.f1.fid);
                            int updateResult = updateBookedCapacityStatement.executeUpdate();
                            if (updateResult == 0) {
                                rollbackTransaction();
                                return "Booking failed\n";
                            }

                            // If indirect flight, update second flight capacity as well
                            if (i.numFlights == 2) {
                                updateBookedCapacityStatement.clearParameters();
                                updateBookedCapacityStatement.setInt(1, i.f2.fid);
                                updateBookedCapacityStatement.setInt(2, 1);
                                updateBookedCapacityStatement.setInt(3, i.f2.fid);
                                updateBookedCapacityStatement.setInt(4, i.f2.fid);
                                updateResult = updateBookedCapacityStatement.executeUpdate();
                                if (updateResult == 0) {
                                    rollbackTransaction();
                                    return "Booking failed\n";
                                }
                            }

                            commitTransaction();
                            return "Booked flight(s), reservation ID: " + generatedKeys.getInt(1) +
                                    "\n";
                        } else {
                            rollbackTransaction();
                            return "Booking failed\n";
                        }
                    }
                }
            } catch (SQLException ex) {
                if (debug) {
                    printSQLException(ex);
                }

                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    if (debug) {
                        printSQLException(ex2);
                    }
                }
            }
        }
        return "Booking failed\n";
    }

    /**
     * Implements the pay function.
     *
     * @param reservationId the reservation to pay for.
     * @return If no user has logged in, then return "Cannot pay, not logged in\n"
     * If the reservation is not found / not under the logged in user's
     * name, then return "Cannot find unpaid reservation [reservationId]
     * under user: [username]\n" If the user does not have enough money in
     * their account, then return "User has only [balance] in account but
     * itinerary costs [cost]\n" For all other errors, return "Failed to pay
     * for reservation [reservationId]\n"
     * <p>
     * If successful, return "Paid reservation: [reservationId] remaining
     * balance: [balance]\n" where [balance] is the remaining balance in the
     * user's account.
     */
    public String transaction_pay(int reservationId) {


        for (int i = 0; i < ATTEMPTS; i++) {
            try {
                beginTransaction();

                // Check if a user is logged in
                if (!openSession) {
                    return "Cannot pay, not logged in\n";
                }
                // Check if reservationId exists for this user
                checkUserReservationExists.clearParameters();
                checkUserReservationExists.setInt(1, reservationId);
                checkUserReservationExists.setString(2, openUser);
                ResultSet existsResult = checkUserReservationExists.executeQuery();

                if (!existsResult.isBeforeFirst()) {
                    commitTransaction();
                    return "Cannot find unpaid reservation " + reservationId + " under " +
                            "user: " +
                            openUser + "\n";
                }
                existsResult.next();

                // Check if user has already paid
                int paid = existsResult.getInt("paid");
                if (paid == 1) {
                    commitTransaction();
                    return "Cannot find unpaid reservation " + reservationId + " under " +
                            "user: " +
                            openUser + "\n";
                }
                // Get the price of the itinerary
                int price = existsResult.getInt("price");

                // Get the user's balance and check if they have enough funds
                getUserStatement.clearParameters();
                getUserStatement.setString(1, openUser);
                ResultSet userInfo = getUserStatement.executeQuery();
                userInfo.next();
                int balance = userInfo.getInt(4);
                if (price > balance) {
                    commitTransaction();
                    return "User has only " + balance + " in account but itinerary costs " + price + "\n";
                }
                // Pay for the reservation
                payReservationStatement.clearParameters();
                payReservationStatement.setInt(1, price);
                payReservationStatement.setString(2, openUser);
                ResultSet payResult = payReservationStatement.executeQuery();

                if (payResult.isBeforeFirst()) {
                    // Get the remaining balance
                    payResult.next();
                    int updatedBalance = payResult.getInt("balance");

                    // Mark the reservation as paid for
                    markAsPaidStatement.clearParameters();
                    markAsPaidStatement.setString(1, openUser);
                    markAsPaidStatement.setInt(2, reservationId);
                    if (markAsPaidStatement.executeUpdate() == 0) {
                        rollbackTransaction();
                        return "Failed to pay for reservation " + reservationId + "\n";
                    }

                    commitTransaction();
                    return "Paid reservation: " + reservationId + " remaining balance: " + updatedBalance +
                            "\n";
                } else {
                    rollbackTransaction();
                    return "Failed to pay for reservation " + reservationId + "\n";
                }
            } catch (SQLException ex) {
                if (debug) {
                    printSQLException(ex);
                }

                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    if (debug) {
                        printSQLException(ex2);
                    }
                }
            }
        }

        return "Failed to pay for reservation " + reservationId + "\n";
    }

    /**
     * Implements the reservations function.
     *
     * @return If no user has logged in, then return "Cannot view reservations, not
     * logged in\n" If the user has no reservations, then return "No
     * reservations found\n" For all other errors, return "Failed to
     * retrieve reservations\n"
     * <p>
     * Otherwise return the reservations in the following format:
     * <p>
     * Reservation [reservation ID] paid: [true or false]:\n" [flight 1
     * under the reservation] [flight 2 under the reservation] Reservation
     * [reservation ID] paid: [true or false]:\n" [flight 1 under the
     * reservation] [flight 2 under the reservation] ...
     * <p>
     * Each flight should be printed using the same format as in the
     * {@code Flight} class.
     * @see Flight#toString()
     */
    public String transaction_reservations() {

        for (int i = 0; i < ATTEMPTS; i++) {
            try {
                beginTransaction();

                if (!openSession) {
                    return "Cannot view reservations, not logged in\n";
                }

                StringBuffer sb = new StringBuffer();

                getUserReservationsStatement.clearParameters();
                getUserReservationsStatement.setString(1, openUser);
                ResultSet userReservations = getUserReservationsStatement.executeQuery();

                if (!userReservations.isBeforeFirst()) {
                    commitTransaction();
                    return "No reservations found\n";
                }

                while (userReservations.next()) {
                    int reservationId = userReservations.getInt("rid");
                    int paid = userReservations.getInt("paid");
                    String paidString = (paid > 0) ? "true" : "false";
                    String[] flightInfo = userReservations.getString("info").
                            split("\\r?\\n");

                    String result = "Reservation " + reservationId + " paid: " + paidString + ":\n" +
                            flightInfo[1] + "\n";
                    sb.append(result);
                }
                commitTransaction();
                return sb.toString();
            } catch (SQLException ex) {
                if (debug) {
                    printSQLException(ex);
                }

                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    if (debug) {
                        printSQLException(ex2);
                    }
                }
            }
        }

        return "Failed to retrieve reservations\n";
    }

    /**
     * Implements the cancel operation.
     *
     * @param reservationId the reservation ID to cancel
     * @return If no user has logged in, then return "Cannot cancel reservations,
     * not logged in\n" For all other errors, return "Failed to cancel
     * reservation [reservationId]\n"
     * <p>
     * If successful, return "Canceled reservation [reservationId]\n"
     * <p>
     * Even though a reservation has been canceled, its ID should not be
     * reused by the system.
     */
    public String transaction_cancel(int reservationId) {
        for (int i = 0; i < ATTEMPTS; i++) {
            try {
                beginTransaction();
                // Check if a user is logged in
                if (!openSession) {
                    return "Cannot cancel reservations, not logged in\n";
                }

                // Check if the reservation exists
                checkUserReservationExists.clearParameters();
                checkUserReservationExists.setInt(1, reservationId);
                checkUserReservationExists.setString(2, openUser);
                ResultSet userReservation = checkUserReservationExists.executeQuery();

                if (!userReservation.isBeforeFirst()) {
                    commitTransaction();
                    return "Failed to cancel reservation " + reservationId + "\n";
                }

                // Check if the reservation has been paid for and retrieve fid's
                userReservation.next();
                int paid = userReservation.getInt("paid");
                int fid1 = userReservation.getInt("fid1");
                int fid2 = userReservation.getInt("fid2");
                if (paid == 1) {
                    // Refund the user for the price of the reservation
                    int price = userReservation.getInt("price");

                    refundReservationStatement.clearParameters();
                    refundReservationStatement.setInt(1, price);
                    refundReservationStatement.setString(2, openUser);
                    if (refundReservationStatement.executeUpdate() == 0) {
                        rollbackTransaction();
                        return "Failed to cancel reservation " + reservationId + "\n";
                    }
                }

                // Delete the reservation
                deleteReservationStatement.clearParameters();
                deleteReservationStatement.setInt(1, reservationId);
                deleteReservationStatement.setString(2, openUser);
                if (deleteReservationStatement.executeUpdate() == 0) {
                    rollbackTransaction();
                    return "Failed to cancel reservation " + reservationId + "\n";
                }

                // Update the booked capacities
                updateBookedCapacityStatement.clearParameters();
                updateBookedCapacityStatement.setInt(1, fid1);
                updateBookedCapacityStatement.setInt(2, -1);
                updateBookedCapacityStatement.setInt(3, fid1);
                updateBookedCapacityStatement.setInt(4, fid1);
                updateBookedCapacityStatement.executeUpdate();

                getBookedCapacityStatement.clearParameters();
                getBookedCapacityStatement.setInt(1, fid1);
                ResultSet result = getBookedCapacityStatement.executeQuery();

                result.next();
                if (result.getInt("capacity") == 0) {
                    removeBookedCapacityStatement.clearParameters();
                    removeBookedCapacityStatement.setInt(1, fid1);
                    removeBookedCapacityStatement.executeUpdate();
                }


                if (fid2 != -1) {
                    updateBookedCapacityStatement.clearParameters();
                    updateBookedCapacityStatement.setInt(1, fid2);
                    updateBookedCapacityStatement.setInt(2, -1);
                    updateBookedCapacityStatement.setInt(3, fid2);
                    updateBookedCapacityStatement.setInt(4, fid2);
                    updateBookedCapacityStatement.executeUpdate();

                    getBookedCapacityStatement.clearParameters();
                    getBookedCapacityStatement.setInt(1, fid2);
                    result = getBookedCapacityStatement.executeQuery();
                    result.next();

                    if (result.getInt("capacity") == 0) {
                        removeBookedCapacityStatement.clearParameters();
                        removeBookedCapacityStatement.setInt(1, fid2);
                        removeBookedCapacityStatement.executeUpdate();
                    }
                }

                commitTransaction();
                return "Canceled reservation " + reservationId + "\n";
            } catch (SQLException ex) {
                if (debug) {
                    printSQLException(ex);
                }

                try {
                    rollbackTransaction();
                } catch (SQLException ex2) {
                    if (debug) {
                        printSQLException(ex2);
                    }
                }
            }
        }

        return "Failed to cancel reservation " + reservationId + "\n";
    }

    /**
     * Example utility function that uses prepared statements
     */
    private int checkFlightCapacity(int fid) throws SQLException {
        checkFlightCapacityStatement.clearParameters();
        checkFlightCapacityStatement.setInt(1, fid);
        ResultSet results = checkFlightCapacityStatement.executeQuery();
        results.next();
        int capacity = results.getInt("capacity");

        return capacity;
    }

    private byte[] hashPassword(String password, byte[] salt) {
        // Specify the hash parameters
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

        // Generate the hash
        SecretKeyFactory factory = null;
        byte[] hash = null;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            hash = factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException();
        }

        return hash;
    }

    /**
     * Prints information about a SQLException.
     *
     * @param ex The given exception
     */
    private void printSQLException(SQLException ex) {
        for (Throwable e : ex) {
            if (e instanceof SQLException) {
                e.printStackTrace(System.err);
                System.err.println("SQLState: " +
                        ((SQLException) e).getSQLState());

                System.err.println("Error Code: " +
                        ((SQLException) e).getErrorCode());

                System.err.println("Message: " + e.getMessage());

                Throwable t = ex.getCause();
                while (t != null) {
                    System.out.println("Cause: " + t);
                    t = t.getCause();
                }
            }
        }
    }

    /**
     * A class to store flight information.
     */
    class Flight {
        public int fid;
        public int dayOfMonth;
        public String carrierId;
        public String flightNum;
        public String originCity;
        public String destCity;
        public int time;
        public int capacity;
        public int price;

        @Override
        public String toString() {
            return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " +
                    flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " +
                    time + " Capacity: " + capacity + " Price: " + price;
        }
    }

    class Itinerary {
        Flight f1;
        Flight f2;
        int totalTime;
        int totalCost;
        int numFlights;
        int dayOfMonth;
        String itinerary;
        boolean full;
    }
}
