package edu.uw.cs;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.sql.*;

import static org.junit.Assert.assertTrue;

/**
 * Autograder for the transaction assignment
 *
 */
@RunWith(Parameterized.class)
public class FlightServiceTest {
  /** Maximum number of concurrent users we will be testing */
  private static final int MAX_USERS = 5;
  /** Max time in seconds to wait for a response for a user */
  private static final int RESPONSE_TIME = 60;
  /** Thread pool used to run different users */
  private static ExecutorService pool;
  /** Folder name and path that contains the test cases */
  private static String casesFolder;

  /** Denotes a comment */
  static final String COMMENTS = "#";
  /** Denotes information mode change */
  static final String DELIMITER = "*";
  /** Denotes alternate result */
  static final String SEPARATOR = "|";

  /**
   * Models a single user. Callable from a thread.
   */
  static class User implements Callable<String> {
    private Query q;
    private List<String> cmds; // commands that this user will execute
    private List<String> results; // the expected results from those commands

    public User(List<String> cmds, List<String> results) throws IOException, SQLException {
      this.q = new Query();
      q.openConnection();
      q.prepareStatements();
      this.cmds = cmds;
      this.results = results;
    }

    public List<String> results() {
      return results;
    }

    @Override
    public String call() {
      StringBuffer sb = new StringBuffer();
      for (String cmd : cmds) {
        sb.append(FlightService.execute(q, cmd));
      }

      return sb.toString();
    }

    public void shutdown() throws Exception {
      this.q.closeConnection();
    }
  }

  /**
   * Parse the input test case. Format expected is
   * 
   * @param filename test case's path and file name
   * @return new User objects with commands to run and expected results
   * @throws Exception
   */
  static List<User> parse(String filename) throws IOException, SQLException {
    List<User> users = new ArrayList<>();
    List<String> cmds = new ArrayList<>();
    List<String> results = new ArrayList<>();
    String r = "";
    boolean isCmd = true;
    BufferedReader reader = new BufferedReader(new FileReader(filename));
    String l;
    int lineNumber = 0;
    while ((l = reader.readLine()) != null) {
      lineNumber++;

      // Skip comment lines
      if (l.startsWith(COMMENTS)) {
        continue;

        // Switch between recording commands and recording results
      } else if (l.startsWith(DELIMITER)) {
        if (isCmd) {
          isCmd = false;
        } else {
          // Result recordings finished for a user so user is fully specified
          results.add(r);
          users.add(new User(cmds, results));
          cmds = new ArrayList<>();
          results = new ArrayList<>();
          r = "";
          isCmd = true;
        }

        // Record an alternate outcome result
      } else if (l.startsWith(SEPARATOR)) {
        if (isCmd) {
          reader.close();
          throw new IllegalArgumentException("ERROR: input file is malformatted on line: " + lineNumber);
        } else {
          results.add(r);
          r = "";
        }

        // Build command list or result string
      } else {
        // Ignore trailing comments
        l = l.split(COMMENTS, 2)[0];
        // Add new command or build current result
        if (isCmd) {
          cmds.add(l);
        } else {
          r = r + l + "\n";
        }
      }
    }
    reader.close();

    // Everything should be parsed by now and put into user objects
    if (cmds.size() > 0 || r.length() > 0 || results.size() > 0) {
      throw new IllegalArgumentException(String.format(
          "ERROR: input file is malformatted, extra information found #commands=%s, len(result)=%s, #results=%s",
          cmds.size(), r.length(), results.size()));
    }

    // check that all users have the same number of possible scenarios
    int n = users.get(0).results().size();
    for (int i = 1; i < users.size(); ++i) {
      int m = users.get(i).results().size();
      if (m != n) {
        throw new IllegalArgumentException(String.format(
            "ERROR: input file is malformatted, user %s should have %s possible results rather than %s", i, n, m));
      }
    }

    return users;
  }

  /**
   * Creates the thread pool to execute test cases with multiple users.
   */
  @BeforeClass
  public static void setup() {
    System.out.println("running setup");
    pool = Executors.newFixedThreadPool(MAX_USERS);
  }

  /** A file that will be parsed as a test case scenario */
  protected String file;

  /**
   * Initialize a test case with a file name
   */
  public FlightServiceTest(String file) {
    this.file = file;
  }

  /**
   * Gets test case scenario files from the specified folder.
   */
  @Parameterized.Parameters
  public static List<String> files() throws IOException {
    try (Stream<Path> paths = Files.walk(Paths.get("cases"))) {
      return paths.filter(Files::isRegularFile).map(p -> p.toAbsolutePath().toString()).collect(Collectors.toList());
    }
  }

  /**
   * Calls the clearTables method in Query so tests do not interfere with each
   * other
   */
  @Before
  public void clearDB() {
    try {
      Query q = new Query();
      q.openConnection();
      q.prepareStatements();
      q.clearTables();
      q.closeConnection();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Runs the test case scenario
   */
  @Test
  public void runTest() throws Exception {
    System.out.println("running test scenario: " + this.file);

    // Loads the scenario and initializes users
    List<User> users = parse(this.file);
    List<Future<String>> futures = new ArrayList<>();
    for (User user : users) {
      futures.add(pool.submit(user));
    }

    // Waits for an output for each user
    List<String> outputs = new ArrayList<>();
    for (Future<String> f : futures) {
      try {
        outputs.add(f.get(RESPONSE_TIME, TimeUnit.SECONDS));
      } catch (TimeoutException e) {
        System.out.println("Timed out!");
      }
    }

    // For each possible outcome, check if each user matches the respective output
    // for the given outcome
    boolean passed = false;
    Map<Integer, List<String>> outcomes = new HashMap<Integer, List<String>>();
    int n = users.get(0).results().size(); // number of possible outcomes
    for (int i = 0; i < n; ++i) {
      boolean isSame = true;
      for (int j = 0; j < users.size(); ++j) {
        isSame = isSame && outputs.get(j).equals(users.get(j).results().get(i));
        if (!outcomes.containsKey(i)) {
          outcomes.put(i, new ArrayList<String>());
        }
        outcomes.get(i).add(users.get(j).results().get(i));
      }
      passed = passed || isSame;
    }

    // Print the result and debugging info if applicable under the assertion
    System.out.println(passed ? "passed" : "failed");
    String outcomesFormatted = "";
    if (!passed) {
      for (Map.Entry<Integer, List<String>> outcome : outcomes.entrySet()) {
        outcomesFormatted += "===== Outcome " + outcome.getKey() + " =====\n";
        outcomesFormatted += outcome.getValue().toString() + "\n";
      }
    }
    assertTrue(String.format("Failed: actual outputs for %s were: \n%s\n\nPossible outcomes were: \n%s", this.file,
        outputs, outcomesFormatted), passed);

    // Cleanup
    for (User u : users) {
      u.shutdown();
    }
  }
}
