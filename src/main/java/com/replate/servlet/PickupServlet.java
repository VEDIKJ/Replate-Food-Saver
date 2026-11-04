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
import java.sql.SQLException;

/*
 * PickupServlet VERIFIES a pickup and completes the order. Mapped to
 * /api/pickup/verify. Restaurant only.
 *
 * Both ways of verifying end up here:
 *   1. Scan the customer's QR code -> the scanned text is the pickup code
 *   2. Type the pickup code by hand
 * Either way the frontend sends:  { "code": "FS-1024-X7K9" }
 *
 * The checking happens on the backend against MySQL, so a fake or reused code
 * cannot complete an order, and a restaurant can only complete its OWN orders.
 */
public class PickupServlet extends HttpServlet {

    private final OrderDAO orderDAO = new OrderDAO();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!User.RESTAURANT.equals(SessionUtil.role(req))) {
            Json.error(resp, 403, "Only restaurants can verify pickups.");
            return;
        }
        Integer restaurantId = SessionUtil.restaurantId(req);

        JsonObject body = Json.readBody(req);
        if (body == null) {
            Json.error(resp, 400, "Please send valid JSON.");
            return;
        }
        if (!body.has("code") || body.get("code").getAsString().trim().isEmpty()) {
            Json.error(resp, 400, "Please provide a pickup code.");
            return;
        }
        String code = body.get("code").getAsString();

        try {
            Order order = orderDAO.findByPickupCode(code);
            if (order == null) {
                Json.error(resp, 404, "Invalid pickup code.");
                return;
            }
            // A restaurant may only complete pickups for its own orders.
            if (order.restaurantId != restaurantId) {
                Json.error(resp, 403, "This pickup code is not for your restaurant.");
                return;
            }
            // Already picked up -> do NOT complete again (stops code reuse).
            if (Order.COMPLETED.equals(order.status)) {
                Json.error(resp, 400, "This order has already been picked up.");
                return;
            }
            // Must be packed and ready before pickup.
            if (!Order.READY_FOR_PICKUP.equals(order.status)) {
                Json.error(resp, 400, "This order is not ready for pickup yet.");
                return;
            }

            order.status = Order.COMPLETED;
            orderDAO.updateStatus(order);
            Json.send(resp, 200, order);

        } catch (SQLException e) {
            Json.error(resp, 500, "Could not verify the pickup.");
        }
    }
}
