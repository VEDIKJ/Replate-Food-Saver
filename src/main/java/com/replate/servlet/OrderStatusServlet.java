package com.replate.servlet;

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
import java.security.SecureRandom;
import java.sql.SQLException;

/*
 * OrderStatusServlet changes the STATUS of an order. Mapped to /api/orders/status.
 * Restaurant only. The restaurant sends an action for one of ITS OWN orders:
 *
 *   { "orderId": 1024, "action": "accept" }  -> PENDING  -> ACCEPTED (pickup code + QR created)
 *   { "orderId": 1024, "action": "reject" }  -> PENDING  -> REJECTED
 *   { "orderId": 1024, "action": "pack"   }  -> ACCEPTED -> READY_FOR_PICKUP
 *
 * Each action only works from the correct starting status, and a restaurant can
 * only touch orders that belong to it (checked against the session restaurant id).
 */
public class OrderStatusServlet extends HttpServlet {

    private final OrderDAO orderDAO = new OrderDAO();
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!User.RESTAURANT.equals(SessionUtil.role(req))) {
            Json.error(resp, 403, "Only restaurants can change order status.");
            return;
        }
        Integer restaurantId = SessionUtil.restaurantId(req);

        JsonObject body = Json.readBody(req);
        if (body == null) {
            Json.error(resp, 400, "Please send valid JSON.");
            return;
        }
        if (!body.has("orderId") || !body.has("action")) {
            Json.error(resp, 400, "orderId and action are required.");
            return;
        }

        int orderId;
        String action;
        try {
            orderId = body.get("orderId").getAsInt();
            action = body.get("action").getAsString();
        } catch (RuntimeException e) {
            Json.error(resp, 400, "orderId must be a number and action must be text.");
            return;
        }

        try {
            Order order = orderDAO.findById(orderId);
            if (order == null) {
                Json.error(resp, 404, "Order not found.");
                return;
            }
            // Ownership: the order must belong to THIS restaurant.
            if (order.restaurantId != restaurantId) {
                Json.error(resp, 403, "You are not allowed to change this order.");
                return;
            }

            switch (action) {
                case "accept":
                    if (!Order.PENDING.equals(order.status)) {
                        Json.error(resp, 400, "Only pending orders can be accepted.");
                        return;
                    }
                    order.status = Order.ACCEPTED;
                    order.pickupCode = generatePickupCode(order.id);
                    order.qrInfo = order.pickupCode;
                    break;

                case "reject":
                    if (!Order.PENDING.equals(order.status)) {
                        Json.error(resp, 400, "Only pending orders can be rejected.");
                        return;
                    }
                    order.status = Order.REJECTED;
                    break;

                case "pack":
                    if (!Order.ACCEPTED.equals(order.status)) {
                        Json.error(resp, 400, "Only accepted orders can be packed.");
                        return;
                    }
                    order.status = Order.READY_FOR_PICKUP;
                    break;

                default:
                    Json.error(resp, 400, "Unknown action: " + action);
                    return;
            }

            orderDAO.updateStatus(order);
            Json.send(resp, 200, order);

        } catch (SQLException e) {
            Json.error(resp, 500, "Could not update the order.");
        }
    }

    // Build a unique pickup code for an order, e.g. "FS-1024-X7K9".
    private String generatePickupCode(int orderId) {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no confusing 0/O or 1/I
        StringBuilder randomPart = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            randomPart.append(letters.charAt(random.nextInt(letters.length())));
        }
        return "FS-" + orderId + "-" + randomPart;
    }
}
