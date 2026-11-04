package com.replate.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/*
 * SessionUtil centralises how we read the logged-in user from the server-side
 * HTTP session, so servlets and the auth filter all agree on the attribute names.
 *
 * After a successful login AuthServlet stores these on the session:
 *   userId       (Integer)  - the users.id of the logged-in account
 *   userName     (String)   - their display name
 *   role         (String)   - "CUSTOMER" or "RESTAURANT"
 *   restaurantId (Integer)  - the restaurants.id (only for RESTAURANT accounts)
 *
 * The session id travels in an HttpOnly cookie (Tomcat's default), so page
 * JavaScript cannot read or steal it.
 */
public final class SessionUtil {

    public static final String USER_ID = "userId";
    public static final String USER_NAME = "userName";
    public static final String ROLE = "role";
    public static final String RESTAURANT_ID = "restaurantId";

    private SessionUtil() {
    }

    // Returns the existing session, or null if the user is not logged in.
    // Passing false means "do NOT create a new session if one is missing".
    public static HttpSession current(HttpServletRequest req) {
        return req.getSession(false);
    }

    public static boolean isLoggedIn(HttpServletRequest req) {
        HttpSession session = current(req);
        return session != null && session.getAttribute(USER_ID) != null;
    }

    public static Integer userId(HttpServletRequest req) {
        HttpSession session = current(req);
        return session == null ? null : (Integer) session.getAttribute(USER_ID);
    }

    public static String role(HttpServletRequest req) {
        HttpSession session = current(req);
        return session == null ? null : (String) session.getAttribute(ROLE);
    }

    public static String userName(HttpServletRequest req) {
        HttpSession session = current(req);
        return session == null ? null : (String) session.getAttribute(USER_NAME);
    }

    // The restaurants.id for a restaurant account, or null for customers / guests.
    public static Integer restaurantId(HttpServletRequest req) {
        HttpSession session = current(req);
        return session == null ? null : (Integer) session.getAttribute(RESTAURANT_ID);
    }

    public static boolean isRole(HttpServletRequest req, String expectedRole) {
        return expectedRole.equals(role(req));
    }
}
