package com.replate.dao;

import com.replate.config.DatabaseConnection;
import com.replate.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * UserDAO ("Data Access Object") is the only class that reads/writes the
 * "users" table. Servlets call these methods instead of writing SQL directly.
 *
 * Every method uses:
 *   * PreparedStatement  -> values are sent separately from the SQL text, which
 *                           prevents SQL injection, and
 *   * try-with-resources -> connections/statements/results close automatically.
 */
public class UserDAO {

    // Insert a new user and return it with its freshly generated id filled in.
    public User insert(User user, String passwordHash) throws SQLException {
        String sql = "INSERT INTO users (full_name, email, phone, password_hash, role) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.fullName);
            ps.setString(2, user.email);
            ps.setString(3, user.phone);
            ps.setString(4, passwordHash);
            ps.setString(5, user.role);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    user.id = keys.getInt(1);
                }
            }
        }
        return user;
    }

    // True if an account with this email already exists (emails are unique).
    public boolean existsByEmail(String email) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE email = ? LIMIT 1";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Find a user by email. Returns null if there is no such account.
    // The returned User DOES carry passwordHash (needed to verify login);
    // that field is transient so it is never sent to the browser.
    public User findByEmail(String email) throws SQLException {
        String sql = "SELECT id, full_name, email, phone, password_hash, role "
                   + "FROM users WHERE email = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    // Find a user by id. Returns null if not found.
    public User findById(int id) throws SQLException {
        String sql = "SELECT id, full_name, email, phone, password_hash, role "
                   + "FROM users WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User(
                rs.getInt("id"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("role"));
        user.passwordHash = rs.getString("password_hash");
        return user;
    }
}
