package com.replate.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.replate.dao.OrderDAO;
import com.replate.model.Order;
import com.replate.model.User;
import com.replate.util.Json;
import com.replate.util.SessionUtil;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/*
 * OrderServlet handles /api/orders, backed by MySQL (OrderDAO).
 *
 *   GET  /api/orders   -> a CUSTOMER gets their own orders;
 *                         a RESTAURANT gets the orders placed to it.
 *   POST /api/orders   -> a CUSTOMER places a new order.
 *
 * The customer id and restaurant id come from the logged-in SESSION, never from
 * the browser, so nobody can read another person's orders or order "as" someone
 * else. Prices and totals are calculated in the database layer (see OrderDAO).
 */
public class OrderServlet extends HttpServlet {

    private final OrderDAO orderDAO = new OrderDAO();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String role = SessionUtil.role(req);
        try {
            if (User.RESTAURANT.equals(role)) {
                Integer restaurantId = SessionUtil.restaurantId(req);
                Json.send(resp, 200, orderDAO.findByRestaurant(restaurantId));
            } else {
                // Customer: only ever their own orders.
                Integer customerId = SessionUtil.userId(req);
                Json.send(resp, 200, orderDAO.findByCustomer(customerId));
            }
        } catch (SQLException e) {
            Json.error(resp, 500, "Could not load orders.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Only customers place orders. (The filter also enforces this.)
        if (!User.CUSTOMER.equals(SessionUtil.role(req))) {
            Json.error(resp, 403, "Only customers can place orders.");
            return;
        }
        Integer customerId = SessionUtil.userId(req);

        JsonObject body = Json.readBody(req);
        if (body == null) {
            Json.error(resp, 400, "Please send valid JSON.");
            return;
        }
        if (!body.has("items") || !body.get("items").isJsonArray()
                || body.getAsJsonArray("items").size() == 0) {
            Json.error(resp, 400, "Your cart is empty.");
            return;
        }

        JsonArray requestedItems = body.getAsJsonArray("items");
        List<OrderDAO.Line> lines = new ArrayList<>();

        // We read ONLY foodItemId + quantity from the browser. The real price and
        // availability are looked up in the DB transaction (OrderDAO.createOrder).
        for (JsonElement element : requestedItems) {
            if (!element.isJsonObject()) {
                Json.error(resp, 400, "Each cart item must be an object.");
                return;
            }
            JsonObject line = element.getAsJsonObject();
            if (!line.has("foodItemId") || !line.has("quantity")) {
                Json.error(resp, 400, "Each cart item needs foodItemId and quantity.");
                return;
            }
            int foodItemId;
            int quantity;
            try {
                foodItemId = line.get("foodItemId").getAsInt();
                quantity = line.get("quantity").getAsInt();
            } catch (RuntimeException e) {
                Json.error(resp, 400, "foodItemId and quantity must be numbers.");
                return;
            }
            if (quantity <= 0) {
                Json.error(resp, 400, "Item quantity must be at least 1.");
                return;
            }
            lines.add(new OrderDAO.Line(foodItemId, quantity));
        }

        try {
            Order order = orderDAO.createOrder(customerId, lines);
            Json.send(resp, 201, order);
        } catch (OrderDAO.OrderException e) {
            // Expected, user-facing problems (stock, availability, mixed restaurants).
            Json.error(resp, 400, e.getMessage());
        } catch (SQLException e) {
            Json.error(resp, 500, "Could not place the order. Please try again.");
        }
    }
}
