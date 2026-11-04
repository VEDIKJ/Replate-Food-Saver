package com.replate.model;

public class OrderItem {

    public int foodItemId;   // which food item was ordered
    public String name;      // name copied from the food item (so it still shows even if the menu changes)
    public int quantity;     // how many of this item the customer wants
    public double price;     // price of ONE unit (copied from the food item at order time)

    public OrderItem(int foodItemId, String name, int quantity, double price) {
        this.foodItemId = foodItemId;
        this.name = name;
        this.quantity = quantity;
        this.price = price;
    }

    // Total price for this single line = unit price * quantity.
    public double lineTotal() {
        return price * quantity;
    }
}
