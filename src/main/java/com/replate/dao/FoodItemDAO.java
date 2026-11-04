package com.replate.dao;

import com.replate.config.DatabaseConnection;
import com.replate.model.FoodItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/*
 * FoodItemDAO reads/writes the "food_items" table - the restaurant menu.
 *
 * Note the two different "list" methods:
 *   findByRestaurant(id)  -> everything a restaurant owns (for its own dashboard)
 *   findAvailable()       -> only items marked available with stock left
 *                            (for the customer menu, across all restaurants)
 */
public class FoodItemDAO {

    // Add a new item. The returned FoodItem has its generated id filled in.
    public FoodItem insert(FoodItem item) throws SQLException {
        String sql = "INSERT INTO food_items "
                   + "(restaurant_id, name, price, category, description, image_url, quantity, available) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, item.restaurantId);
            ps.setString(2, item.name);
            ps.setDouble(3, item.price);
            ps.setString(4, item.category);
            ps.setString(5, item.description);
            ps.setString(6, item.imageUrl);
            ps.setInt(7, item.quantity);
            ps.setBoolean(8, item.available);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    item.id = keys.getInt(1);
                }
            }
        }
        return item;
    }

    // Update an existing item's editable fields.
    public void update(FoodItem item) throws SQLException {
        String sql = "UPDATE food_items SET name = ?, price = ?, category = ?, description = ?, "
                   + "image_url = ?, quantity = ?, available = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.name);
            ps.setDouble(2, item.price);
            ps.setString(3, item.category);
            ps.setString(4, item.description);
            ps.setString(5, item.imageUrl);
            ps.setInt(6, item.quantity);
            ps.setBoolean(7, item.available);
            ps.setInt(8, item.id);
            ps.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        String sql = "DELETE FROM food_items WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public FoodItem findById(int id) throws SQLException {
        String sql = "SELECT f.*, r.restaurant_name FROM food_items f "
                   + "JOIN restaurants r ON f.restaurant_id = r.id WHERE f.id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    // Every item a restaurant owns (available or hidden), newest first.
    public List<FoodItem> findByRestaurant(int restaurantId) throws SQLException {
        String sql = "SELECT f.*, r.restaurant_name FROM food_items f "
                   + "JOIN restaurants r ON f.restaurant_id = r.id "
                   + "WHERE f.restaurant_id = ? ORDER BY f.id DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, restaurantId);
            return readList(ps);
        }
    }

    // Items shown to customers: available AND still in stock, across all restaurants.
    public List<FoodItem> findAvailable() throws SQLException {
        String sql = "SELECT f.*, r.restaurant_name FROM food_items f "
                   + "JOIN restaurants r ON f.restaurant_id = r.id "
                   + "WHERE f.available = 1 AND f.quantity > 0 ORDER BY f.id DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            return readList(ps);
        }
    }

    private List<FoodItem> readList(PreparedStatement ps) throws SQLException {
        List<FoodItem> items = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(mapRow(rs));
            }
        }
        return items;
    }

    private FoodItem mapRow(ResultSet rs) throws SQLException {
        FoodItem item = new FoodItem(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getDouble("price"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getString("image_url"),
                rs.getInt("quantity"),
                rs.getBoolean("available"),
                rs.getInt("restaurant_id"));
        // Owning restaurant's display name, fetched via the JOIN above.
        String restaurantName = rs.getString("restaurant_name");
        item.restaurantName = (restaurantName == null) ? "" : restaurantName;
        return item;
    }
}
