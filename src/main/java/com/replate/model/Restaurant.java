package com.replate.model;

/*
 * A Restaurant holds the extra details for a RESTAURANT account: its display
 * name and address. It is linked to the owning User through userId.
 *
 * Note the two different ids:
 *   userId       -> the id in the users table (the login account)
 *   id           -> the id in the restaurants table (used as food_items.restaurant_id)
 */
public class Restaurant {

    public int id;
    public int userId;
    public String restaurantName;
    public String address;

    public Restaurant() {
    }

    public Restaurant(int id, int userId, String restaurantName, String address) {
        this.id = id;
        this.userId = userId;
        this.restaurantName = restaurantName;
        this.address = address;
    }
}
