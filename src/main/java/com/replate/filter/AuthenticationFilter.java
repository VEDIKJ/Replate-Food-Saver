package com.replate.filter;

import com.replate.model.User;
import com.replate.util.SessionUtil;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/*
 * AuthenticationFilter is the GATE in front of protected URLs. It runs BEFORE
 * any servlet, so every servlet behind it can trust that the user is logged in
 * and has the right role.
 *
 * It is mapped (in Main) to:
 *   /api/*            - the backend API
 *   /customer.html    - the customer dashboard page
 *   /restaurant.html  - the restaurant dashboard page
 *
 * Two kinds of response when access is denied:
 *   * For API calls   -> a JSON error with status 401 (not logged in) or 403 (wrong role).
 *   * For page visits -> a redirect to login.html (so typing the URL by hand is useless).
 */
public class AuthenticationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String uri = req.getRequestURI();        // context path is "" so this is e.g. /api/food
        String method = req.getMethod();

        // ---- 1. Dashboard PAGE guards ----
        if (uri.equals("/customer.html") || uri.equals("/restaurant.html")) {
            guardPage(req, resp, chain, uri);
            return;
        }

        // ---- 2. Auth endpoints manage their own access (register/login/me/logout) ----
        if (uri.startsWith("/api/auth/")) {
            chain.doFilter(request, response);
            return;
        }

        // ---- 3. All other /api/* calls require a logged-in user ----
        if (!SessionUtil.isLoggedIn(req)) {
            sendJsonError(resp, 401, "Please log in first.");
            return;
        }

        String role = SessionUtil.role(req);

        // ---- 4. Role rules per endpoint ----
        if (uri.equals("/api/food") || uri.startsWith("/api/food/")) {
            // GET = browse/list (any logged-in user); writes = restaurant only.
            if (!"GET".equalsIgnoreCase(method) && !User.RESTAURANT.equals(role)) {
                sendJsonError(resp, 403, "Only restaurants can change the menu.");
                return;
            }
        } else if (uri.equals("/api/orders")) {
            // GET = your own orders; POST (place order) = customer only.
            if ("POST".equalsIgnoreCase(method) && !User.CUSTOMER.equals(role)) {
                sendJsonError(resp, 403, "Only customers can place orders.");
                return;
            }
        } else if (uri.equals("/api/orders/status") || uri.equals("/api/pickup/verify")) {
            // Managing orders and verifying pickups = restaurant only.
            if (!User.RESTAURANT.equals(role)) {
                sendJsonError(resp, 403, "Only restaurants can perform this action.");
                return;
            }
        }

        // Passed all checks.
        chain.doFilter(request, response);
    }

    // Guard a dashboard page: must be logged in AND have the matching role.
    private void guardPage(HttpServletRequest req, HttpServletResponse resp, FilterChain chain,
                           String uri) throws IOException, ServletException {
        if (!SessionUtil.isLoggedIn(req)) {
            resp.sendRedirect("login.html");
            return;
        }
        String role = SessionUtil.role(req);

        if (uri.equals("/restaurant.html") && !User.RESTAURANT.equals(role)) {
            // A customer tried to open the restaurant page -> send them to their own.
            resp.sendRedirect("customer.html");
            return;
        }
        if (uri.equals("/customer.html") && !User.CUSTOMER.equals(role)) {
            resp.sendRedirect("restaurant.html");
            return;
        }
        chain.doFilter(req, resp);
    }

    private void sendJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        // Simple hand-built JSON so the filter has no extra dependencies.
        resp.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
