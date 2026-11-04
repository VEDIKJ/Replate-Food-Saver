package com.replate.model;

/*
 * A FoodItem is a single dish that a restaurant adds to the menu.
 * Customers see these items on the Customer Dashboard and can add them to the cart.
 *
 * The fields are kept "public" on purpose so the code stays simple and
 * easy to read for beginners. Gson (our JSON library) reads these fields
 * directly when it converts a FoodItem into JSON for the frontend. That
 * means every field below is sent to BOTH dashboards exactly the same way,
 * which is why the image now shows up for the customer too.
 */
public class FoodItem {

    public int id;             // unique number for this food item (e.g. 1, 2, 3...)
    public String name;        // name of the dish, e.g. "Paneer Sandwich"
    public double price;       // price in rupees, e.g. 80
    public String category;    // one of: Meals, Snacks, Desserts, Beverages, Other
    public String description; // optional short description ("" if none)
    public String imageUrl;    // RELATIVE url like "/uploads/food/ab12.jpg" ("" if no image)
    public int quantity;       // how many units are currently available
    public boolean available;  // true = shown to customers, false = hidden
    public int restaurantId;   // which restaurant owns this item
    public String restaurantName = ""; // display name of the owning restaurant (filled from a JOIN)

    public FoodItem(int id, String name, double price, String category,
                    String description, String imageUrl, int quantity,
                    boolean available, int restaurantId) {
        this.id = id;
        this.name = name;
        this.price = price;
        // Guard against nulls so the JSON always has clean, predictable values.
        this.category = (category == null || category.isEmpty()) ? "Other" : category;
        this.description = (description == null) ? "" : description;
        this.imageUrl = (imageUrl == null) ? "" : imageUrl;
        this.quantity = Math.max(0, quantity);
        this.available = available;
        this.restaurantId = restaurantId;
    }
}
