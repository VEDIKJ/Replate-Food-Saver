package com.replate.dao;

import com.replate.config.DatabaseConnection;
import com.replate.model.Restaurant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * RestaurantDAO reads/writes the "restaurants" table. A restaurant row is
 * created at registration time, right after the matching users row.
 */
public class RestaurantDAO {

    // Insert using an EXISTING connection, so registration can create the user
    // and the restaurant together inside one transaction.
    public Restaurant insert(Connection conn, int userId, String restaurantName, String address)
            throws SQLException {
        String sql = "INSERT INTO restaurants (user_id, restaurant_name, address) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, restaurantName);
            ps.setString(3, address);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                int id = keys.next() ? keys.getInt(1) : 0;
                return new Restaurant(id, userId, restaurantName, address);
            }
        }
    }

    // Find the restaurant that belongs to a given user account. Null if none.
    public Restaurant findByUserId(int userId) throws SQLException {
        String sql = "SELECT id, user_id, restaurant_name, address FROM restaurants WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public Restaurant findById(int id) throws SQLException {
        String sql = "SELECT id, user_id, restaurant_name, address FROM restaurants WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    private Restaurant mapRow(ResultSet rs) throws SQLException {
        return new Restaurant(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getString("restaurant_name"),
                rs.getString("address"));
    }
}
