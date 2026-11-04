package com.replate.servlet;

import com.google.gson.JsonObject;
import com.replate.config.DatabaseConnection;
import com.replate.dao.RestaurantDAO;
import com.replate.dao.UserDAO;
import com.replate.model.Restaurant;
import com.replate.model.User;
import com.replate.util.Json;
import com.replate.util.PasswordUtil;
import com.replate.util.SessionUtil;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * AuthServlet handles everything about accounts. It is mapped to /api/auth/*:
 *
 *   POST /api/auth/register  -> create a new customer or restaurant account
 *   POST /api/auth/login     -> check email + password, start a session
 *   GET  /api/auth/me        -> who is logged in right now?
 *   POST /api/auth/logout    -> end the session
 *
 * Passwords are hashed with BCrypt before they ever touch the database, and the
 * hash is never sent back to the browser (the User model marks it transient).
 */
public class AuthServlet extends HttpServlet {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserDAO userDAO = new UserDAO();
    private final RestaurantDAO restaurantDAO = new RestaurantDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = pathOf(req);
        if ("/me".equals(path)) {
            handleMe(req, resp);
        } else {
            Json.error(resp, 404, "Unknown auth endpoint.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = pathOf(req);
        switch (path) {
            case "/register":
                handleRegister(req, resp);
                break;
            case "/login":
                handleLogin(req, resp);
                break;
            case "/logout":
                handleLogout(req, resp);
                break;
            default:
                Json.error(resp, 404, "Unknown auth endpoint.");
        }
    }

    /* ---------------------------------------------------------------
       POST /api/auth/register
       --------------------------------------------------------------- */
    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject body = Json.readBody(req);
        if (body == null) {
            Json.error(resp, 400, "Please send valid JSON.");
            return;
        }

        String fullName = getString(body, "fullName");
        String email = getString(body, "email").toLowerCase();
        String phone = getString(body, "phone");
        String password = getString(body, "password");
        String confirmPassword = getString(body, "confirmPassword");
        String role = getString(body, "role").toUpperCase();

        // --- Validate the common fields ---
        if (fullName.isEmpty()) {
            Json.error(resp, 400, "Please enter your full name.");
            return;
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            Json.error(resp, 400, "Please enter a valid email address.");
            return;
        }
        if (phone.isEmpty() || !phone.matches("[0-9+\\-\\s]{7,20}")) {
            Json.error(resp, 400, "Please enter a valid phone number.");
            return;
        }
        if (password.length() < 6) {
            Json.error(resp, 400, "Password must be at least 6 characters long.");
            return;
        }
        if (!password.equals(confirmPassword)) {
            Json.error(resp, 400, "Passwords do not match.");
            return;
        }
        if (!User.CUSTOMER.equals(role) && !User.RESTAURANT.equals(role)) {
            Json.error(resp, 400, "Please choose an account type.");
            return;
        }

        // Restaurant accounts need their name + address too.
        String restaurantName = getString(body, "restaurantName");
        String address = getString(body, "address");
        if (User.RESTAURANT.equals(role)) {
            if (restaurantName.isEmpty()) {
                Json.error(resp, 400, "Please enter your restaurant name.");
                return;
            }
            if (address.isEmpty()) {
                Json.error(resp, 400, "Please enter your restaurant address.");
                return;
            }
        }

        try {
            // Prevent duplicate email registrations.
            if (userDAO.existsByEmail(email)) {
                Json.error(resp, 409, "An account with this email already exists. Please log in instead.");
                return;
            }

            String passwordHash = PasswordUtil.hash(password);
            User user = new User(0, fullName, email, phone, role);

            if (User.RESTAURANT.equals(role)) {
                // Create the user AND the restaurant together in one transaction,
                // so we never end up with a restaurant account that has no profile.
                createRestaurantAccount(user, passwordHash, restaurantName, address);
            } else {
                userDAO.insert(user, passwordHash);
            }

            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("message", "Registration successful. You can now log in.");
            Json.send(resp, 201, ok);

        } catch (SQLException e) {
            Json.error(resp, 500, "Could not complete registration. Please try again.");
        }
    }

    // Insert the user row and the restaurant row inside a single transaction.
    private void createRestaurantAccount(User user, String passwordHash,
                                         String restaurantName, String address) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // Insert user (reusing a prepared statement here for the transaction).
            String sql = "INSERT INTO users (full_name, email, phone, password_hash, role) "
                       + "VALUES (?, ?, ?, ?, ?)";
            try (java.sql.PreparedStatement ps =
                         conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, user.fullName);
                ps.setString(2, user.email);
                ps.setString(3, user.phone);
                ps.setString(4, passwordHash);
                ps.setString(5, user.role);
                ps.executeUpdate();
                try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        user.id = keys.getInt(1);
                    }
                }
            }

            restaurantDAO.insert(conn, user.id, restaurantName, address);
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) { }
            }
            throw e;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) { }
            }
        }
    }

    /* ---------------------------------------------------------------
       POST /api/auth/login
       --------------------------------------------------------------- */
    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject body = Json.readBody(req);
        if (body == null) {
            Json.error(resp, 400, "Please send valid JSON.");
            return;
        }

        String email = getString(body, "email").toLowerCase();
        String password = getString(body, "password");

        if (email.isEmpty() || password.isEmpty()) {
            Json.error(resp, 400, "Please enter your email and password.");
            return;
        }

        try {
            User user = userDAO.findByEmail(email);
            // Same message whether the email is unknown or the password is wrong,
            // so attackers cannot tell which emails are registered.
            if (user == null || !PasswordUtil.matches(password, user.passwordHash)) {
                Json.error(resp, 401, "Incorrect email or password.");
                return;
            }

            // Build a fresh session (guards against session fixation).
            HttpSession old = req.getSession(false);
            if (old != null) {
                old.invalidate();
            }
            HttpSession session = req.getSession(true);
            session.setAttribute(SessionUtil.USER_ID, user.id);
            session.setAttribute(SessionUtil.USER_NAME, user.fullName);
            session.setAttribute(SessionUtil.ROLE, user.role);

            String redirect;
            if (User.RESTAURANT.equals(user.role)) {
                Restaurant restaurant = restaurantDAO.findByUserId(user.id);
                if (restaurant == null) {
                    Json.error(resp, 500, "Your restaurant profile is missing. Please contact support.");
                    return;
                }
                session.setAttribute(SessionUtil.RESTAURANT_ID, restaurant.id);
                redirect = "restaurant.html";
            } else {
                redirect = "customer.html";
            }

            Map<String, Object> ok = new HashMap<>();
            ok.put("id", user.id);
            ok.put("name", user.fullName);
            ok.put("role", user.role);
            ok.put("redirect", redirect);
            Json.send(resp, 200, ok);

        } catch (SQLException e) {
            Json.error(resp, 500, "Could not log in right now. Please try again.");
        }
    }

    /* ---------------------------------------------------------------
       GET /api/auth/me
       --------------------------------------------------------------- */
    private void handleMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!SessionUtil.isLoggedIn(req)) {
            Json.error(resp, 401, "Not logged in.");
            return;
        }
        Map<String, Object> me = new HashMap<>();
        me.put("id", SessionUtil.userId(req));
        me.put("name", SessionUtil.userName(req));
        me.put("role", SessionUtil.role(req));
        Json.send(resp, 200, me);
    }

    /* ---------------------------------------------------------------
       POST /api/auth/logout
       --------------------------------------------------------------- */
    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        Json.send(resp, 200, ok);
    }

    /* ---------------------------------------------------------------
       small helpers
       --------------------------------------------------------------- */

    // The part of the URL after /api/auth, e.g. "/login". "" if none.
    private String pathOf(HttpServletRequest req) {
        String info = req.getPathInfo();
        return info == null ? "" : info;
    }

    private String getString(JsonObject body, String key) {
        if (body.has(key) && !body.get(key).isJsonNull()) {
            try {
                return body.get(key).getAsString().trim();
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }
}
