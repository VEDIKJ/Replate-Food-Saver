package com.replate.model;

import java.util.List;


public class Order {

    // These constants are the only valid status values.
    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String READY_FOR_PICKUP = "READY_FOR_PICKUP";
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";

    public int id;                 // unique order number (e.g. 1024)
    public int customerId;         // which customer placed the order
    public int restaurantId;       // which restaurant should prepare it
    public double totalAmount;     // total price of the whole order
    public String status;          // current status (see constants above)
    public String pickupCode;      // unique text code for pickup, e.g. "FS-1024-X7K9" (null until accepted)
    public String qrInfo;          // the value stored inside the QR code (we use the same pickup code)
    public long createdTime;       // when the order was created (milliseconds)
    public List<OrderItem> items;  // the list of items in this order

    public Order(int id, int customerId, int restaurantId, List<OrderItem> items, double totalAmount) {
        this.id = id;
        this.customerId = customerId;
        this.restaurantId = restaurantId;
        this.items = items;
        this.totalAmount = totalAmount;
        this.status = PENDING;   // every new order starts as PENDING
        this.pickupCode = null;  // no pickup code until the restaurant accepts
        this.qrInfo = null;
        this.createdTime = System.currentTimeMillis();
    }
}
