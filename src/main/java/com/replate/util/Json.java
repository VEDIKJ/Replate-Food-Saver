package com.replate.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/*
 * Json is a small helper class that keeps our servlets short and readable.
 *
 * It does three things:
 *   1. send(...)      -> turn a Java object into JSON and write it to the response
 *   2. error(...)     -> send a simple {"error": "..."} message with a status code
 *   3. readBody(...)  -> read the JSON the frontend sent and give it back as a JsonObject
 *
 * We use Google's Gson library to convert between Java objects and JSON text.
 */
public class Json {

    private static final Gson GSON = new Gson();

    // Send any Java object back to the browser as JSON.
    public static void send(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(GSON.toJson(body));
    }

    // Shortcut for sending an error message, e.g. error(resp, 400, "Your cart is empty.")
    public static void error(HttpServletResponse resp, int status, String message) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        send(resp, status, body);
    }

    // Read the request body (the JSON the frontend sent) and parse it.
    // Returns an empty JsonObject if the body is empty.
    public static JsonObject readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        if (sb.length() == 0) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(sb.toString()).getAsJsonObject();
        } catch (RuntimeException e) {
            // Bad JSON should be handled by the servlet as a normal bad request,
            // instead of becoming an unexpected server error.
            return null;
        }
    }
}
