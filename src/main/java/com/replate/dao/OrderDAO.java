package com.replate.dao;

import com.replate.config.DatabaseConnection;
import com.replate.model.Order;
import com.replate.model.OrderItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/*
 * OrderDAO reads/writes "orders" and "order_items".
 *
 * The most important method is createOrder(...). It uses a DATABASE TRANSACTION
 * so that checking stock, saving the order, saving its items, and reducing stock
 * all happen together. If anything fails, everything is rolled back and no order
 * (and no stock change) is left behind.
 *
 * Prices and totals are read from the database here - never trusted from the
 * browser - so a customer cannot change what they are charged.
 */
public class OrderDAO {

    // A single requested cart line coming in from the customer: just the id and
    // how many. The real name/price are looked up from the DB during checkout.
    public static class Line {
        public final int foodItemId;
        public final int quantity;
        public Line(int foodItemId, int quantity) {
            this.foodItemId = foodItemId;
            this.quantity = quantity;
        }
    }

    // Raised when an order cannot be placed for a reason the customer should see
    // (empty cart, out of stock, mixed restaurants...). The servlet turns this
    // into a 400 response with the message.
    public static class OrderException extends Exception {
        public OrderException(String message) {
            super(message);
        }
    }

    /*
     * Place an order inside one transaction.
     *
     * Steps (all-or-nothing):
     *   1. Lock each requested food row (SELECT ... FOR UPDATE) so two customers
     *      cannot both buy the last unit at the same moment.
     *   2. Validate: item exists, is available, has enough stock, and every item
     *      belongs to the SAME restaurant (one order = one restaurant).
     *   3. Insert the order, then its order_items, using DB prices.
     *   4. Reduce each item's stock.
     *   5. Commit. Any error rolls the whole thing back.
     */
    public Order createOrder(int customerId, List<Line> lines) throws SQLException, OrderException {
        if (lines == null || lines.isEmpty()) {
            throw new OrderException("Your cart is empty.");
        }

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false); // start transaction

            int restaurantId = -1;
            double total = 0;
            List<OrderItem> orderItems = new ArrayList<>();

            // Step 1 & 2: lock and validate each line.
            String lockSql = "SELECT id, restaurant_id, name, price, quantity, available "
                           + "FROM food_items WHERE id = ? FOR UPDATE";
            for (Line line : lines) {
                if (line.quantity <= 0) {
                    throw new OrderException("Item quantity must be at least 1.");
                }
                try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                    ps.setInt(1, line.foodItemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new OrderException("One of the items is no longer available.");
                        }
                        int itemRestaurant = rs.getInt("restaurant_id");
                        String name = rs.getString("name");
                        double price = rs.getDouble("price");
                        int stock = rs.getInt("quantity");
                        boolean available = rs.getBoolean("available");

                        if (!available) {
                            throw new OrderException("\"" + name + "\" is no longer available to order.");
                        }
                        if (stock < line.quantity) {
                            throw new OrderException("\"" + name + "\" has only " + stock + " available.");
                        }
                        if (restaurantId == -1) {
                            restaurantId = itemRestaurant;
                        } else if (restaurantId != itemRestaurant) {
                            throw new OrderException(
                                "Please order from one restaurant at a time. Remove items from other "
                              + "restaurants before placing this order.");
                        }

                        orderItems.add(new OrderItem(line.foodItemId, name, line.quantity, price));
                        total += price * line.quantity;
                    }
                }
            }

            // Step 3: insert the order row.
            int orderId;
            String insertOrder = "INSERT INTO orders (customer_id, restaurant_id, total_amount, status) "
                               + "VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertOrder, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, customerId);
                ps.setInt(2, restaurantId);
                ps.setDouble(3, total);
                ps.setString(4, Order.PENDING);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    orderId = keys.getInt(1);
                }
            }

            // Step 3b: insert each order item line.
            String insertItem = "INSERT INTO order_items "
                              + "(order_id, food_item_id, item_name, quantity, unit_price, subtotal) "
                              + "VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertItem)) {
                for (OrderItem oi : orderItems) {
                    ps.setInt(1, orderId);
                    ps.setInt(2, oi.foodItemId);
                    ps.setString(3, oi.name);
                    ps.setInt(4, oi.quantity);
                    ps.setDouble(5, oi.price);
                    ps.setDouble(6, oi.lineTotal());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Step 4: reduce stock for each item.
            String reduce = "UPDATE food_items SET quantity = quantity - ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(reduce)) {
                for (OrderItem oi : orderItems) {
                    ps.setInt(1, oi.quantity);
                    ps.setInt(2, oi.foodItemId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit(); // Step 5: everything succeeded - make it permanent.

            Order order = new Order(orderId, customerId, restaurantId, orderItems, total);
            return order;

        } catch (SQLException | OrderException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            closeQuietly(conn);
        }
    }

    // Save a status change (and pickup code / QR info) back to the orders table.
    public void updateStatus(Order order) throws SQLException {
        String sql = "UPDATE orders SET status = ?, pickup_code = ?, qr_info = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, order.status);
            ps.setString(2, order.pickupCode);
            ps.setString(3, order.qrInfo);
            ps.setInt(4, order.id);
            ps.executeUpdate();
        }
    }

    public Order findById(int id) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            return findById(conn, id);
        }
    }

    public Order findByPickupCode(String code) throws SQLException {
        if (code == null) {
            return null;
        }
        String cleaned = code.trim();
        String sql = "SELECT * FROM orders WHERE UPPER(pickup_code) = UPPER(?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cleaned);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Order order = mapRow(rs);
                order.items = loadItems(conn, order.id);
                return order;
            }
        }
    }

    public List<Order> findByCustomer(int customerId) throws SQLException {
        String sql = "SELECT * FROM orders WHERE customer_id = ? ORDER BY id DESC";
        return queryOrders(sql, customerId);
    }

    public List<Order> findByRestaurant(int restaurantId) throws SQLException {
        String sql = "SELECT * FROM orders WHERE restaurant_id = ? ORDER BY id DESC";
        return queryOrders(sql, restaurantId);
    }

    // ---- internal helpers ----

    private List<Order> queryOrders(String sql, int idParam) throws SQLException {
        List<Order> orders = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idParam);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(mapRow(rs));
                }
            }
            // Load items for each order (kept simple and readable).
            for (Order order : orders) {
                order.items = loadItems(conn, order.id);
            }
        }
        return orders;
    }

    private Order findById(Connection conn, int id) throws SQLException {
        String sql = "SELECT * FROM orders WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Order order = mapRow(rs);
                order.items = loadItems(conn, id);
                return order;
            }
        }
    }

    private List<OrderItem> loadItems(Connection conn, int orderId) throws SQLException {
        String sql = "SELECT food_item_id, item_name, quantity, unit_price "
                   + "FROM order_items WHERE order_id = ? ORDER BY id";
        List<OrderItem> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int foodItemId = rs.getInt("food_item_id"); // 0 if the food row was deleted (NULL)
                    items.add(new OrderItem(
                            foodItemId,
                            rs.getString("item_name"),
                            rs.getInt("quantity"),
                            rs.getDouble("unit_price")));
                }
            }
        }
        return items;
    }

    private Order mapRow(ResultSet rs) throws SQLException {
        Order order = new Order(
                rs.getInt("id"),
                rs.getInt("customer_id"),
                rs.getInt("restaurant_id"),
                new ArrayList<>(),            // items loaded separately
                rs.getDouble("total_amount"));
        order.status = rs.getString("status");
        order.pickupCode = rs.getString("pickup_code");
        order.qrInfo = rs.getString("qr_info");
        Timestamp created = rs.getTimestamp("created_at");
        order.createdTime = (created != null) ? created.getTime() : System.currentTimeMillis();
        return order;
    }

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException ignored) { }
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) { }
        }
    }
}
