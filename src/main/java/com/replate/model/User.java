package com.replate.model;

/*
 * A User is one account. Both customers and restaurant owners are Users; the
 * "role" field says which kind. Restaurant owners also have a matching row in
 * the restaurants table (see Restaurant).
 *
 * IMPORTANT: passwordHash is marked "transient" so Gson NEVER includes it when
 * this object is turned into JSON for the browser. The password hash must never
 * leave the server.
 */
public class User {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String RESTAURANT = "RESTAURANT";

    public int id;
    public String fullName;
    public String email;
    public String phone;
    public String role;        // "CUSTOMER" or "RESTAURANT"

    // Never serialized to JSON (transient) - stays on the server only.
    public transient String passwordHash;

    public User() {
    }

    public User(int id, String fullName, String email, String phone, String role) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.role = role;
    }
}
