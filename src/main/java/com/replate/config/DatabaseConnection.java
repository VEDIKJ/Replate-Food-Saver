package com.replate.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/*
 * DatabaseConnection is the ONE place that knows how to open a connection to
 * MySQL. Every DAO (UserDAO, FoodItemDAO, ...) calls getConnection() instead of
 * repeating the connection details, so the credentials live in exactly one spot.
 *
 * WHERE DO THE CREDENTIALS COME FROM?  (checked in this order)
 *   1. Environment variables:  DB_URL, DB_USER, DB_PASSWORD
 *   2. A file named "db.properties" in the project folder, e.g.
 *          db.url=jdbc:mysql://localhost:3306/replate_db
 *          db.user=root
 *          db.password=your-password
 *
 * We NEVER hardcode the password in the Java source. db.properties is listed in
 * .gitignore so a real password is never committed. See db.properties.example.
 */
public final class DatabaseConnection {

    // Defaults used only for the parts that are not secret (host/db name).
    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/replate_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private static String url;
    private static String user;
    private static String password;
    private static boolean loaded = false;

    private DatabaseConnection() {
        // Utility class - no instances.
    }

    // Load the settings once (from env vars, then db.properties) and make sure
    // the MySQL JDBC driver class is present on the classpath.
    private static synchronized void init() {
        if (loaded) {
            return;
        }

        // Start with values from the environment (highest priority).
        url = System.getenv("DB_URL");
        user = System.getenv("DB_USER");
        password = System.getenv("DB_PASSWORD");

        // Fill any blanks from db.properties in the project folder.
        Properties props = loadPropertiesFile();
        if (props != null) {
            if (isBlank(url))      { url = props.getProperty("db.url"); }
            if (isBlank(user))     { user = props.getProperty("db.user"); }
            if (isBlank(password)) { password = props.getProperty("db.password"); }
        }

        if (isBlank(url)) {
            url = DEFAULT_URL;
        }

        if (isBlank(user) || password == null) {
            throw new IllegalStateException(
                "Database credentials are missing. Set DB_USER and DB_PASSWORD as environment "
              + "variables, or create a db.properties file in the project folder "
              + "(copy db.properties.example and fill in your MySQL user and password).");
        }

        // Make sure the MySQL Connector/J driver is loadable. On modern
        // Connector/J the driver auto-registers, but loading it explicitly
        // gives a clear error if the jar is missing from the classpath.
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "MySQL Connector/J driver not found. Make sure lib/mysql-connector-j-*.jar "
              + "is on the classpath (run.bat already includes lib/*).", e);
        }

        loaded = true;
    }

    private static Properties loadPropertiesFile() {
        File file = new File(System.getProperty("user.dir"), "db.properties");
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read db.properties: " + e.getMessage(), e);
        }
    }

    // Open a brand new connection. Callers MUST close it (use try-with-resources).
    public static Connection getConnection() throws SQLException {
        init();
        return DriverManager.getConnection(url, user, password);
    }

    // Called once at startup. Opens a connection and throws a clear error if the
    // database is unavailable, so the app NEVER silently falls back to memory.
    public static void verifyConnectionOrThrow() {
        init();
        try (Connection ignored = getConnection()) {
            // A successful open-and-close is all we need to confirm MySQL is reachable.
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Could not connect to MySQL at \"" + url + "\" as user \"" + user + "\".\n"
              + "  * Is the MySQL server running?\n"
              + "  * Did you create the database by running database.sql?\n"
              + "  * Are the user/password in db.properties (or DB_USER/DB_PASSWORD) correct?\n"
              + "  Underlying error: " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
