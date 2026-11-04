-- ============================================================
--  Replate 7.0 - MySQL schema / initialization script
--
--  How to run (any ONE of these):
--    * MySQL Workbench:  File -> Open SQL Script -> pick this file -> Execute (lightning bolt)
--    * Command line:     mysql -u root -p < database.sql
--
--  It is safe to run more than once: it only creates things that
--  are missing (IF NOT EXISTS) and never deletes your data.
-- ============================================================

CREATE DATABASE IF NOT EXISTS replate_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE replate_db;

-- ------------------------------------------------------------
-- users: every account (customers AND restaurant owners)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    full_name     VARCHAR(100)  NOT NULL,
    email         VARCHAR(150)  NOT NULL,
    phone         VARCHAR(20)   NOT NULL,
    password_hash VARCHAR(100)  NOT NULL,            -- BCrypt hash (never plain text)
    role          ENUM('CUSTOMER','RESTAURANT') NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB;

-- ------------------------------------------------------------
-- restaurants: extra details for RESTAURANT accounts.
-- One row per restaurant user (user_id is unique).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurants (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    user_id         INT           NOT NULL,
    restaurant_name VARCHAR(150)  NOT NULL,
    address         VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_restaurants_user UNIQUE (user_id),
    CONSTRAINT fk_restaurants_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ------------------------------------------------------------
-- food_items: the menu. Every item belongs to one restaurant.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS food_items (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id INT           NOT NULL,
    name          VARCHAR(150)  NOT NULL,
    price         DECIMAL(10,2) NOT NULL,
    category      VARCHAR(50)   NOT NULL DEFAULT 'Other',
    description   VARCHAR(500)  NOT NULL DEFAULT '',
    image_url     VARCHAR(255)  NOT NULL DEFAULT '',  -- relative path like /uploads/food/x.jpg
    quantity      INT           NOT NULL DEFAULT 0,
    available     TINYINT(1)    NOT NULL DEFAULT 1,    -- 1 = shown to customers, 0 = hidden
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_food_restaurant FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_food_restaurant (restaurant_id),
    INDEX idx_food_available (available)
) ENGINE=InnoDB;

-- ------------------------------------------------------------
-- orders: one row per placed order. Orders start numbering at 1024
-- so the first order shows as #1024 (matches the original demo).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    customer_id   INT           NOT NULL,
    restaurant_id INT           NOT NULL,
    total_amount  DECIMAL(10,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    pickup_code   VARCHAR(30)   NULL,
    qr_info       VARCHAR(255)  NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_orders_pickup_code UNIQUE (pickup_code),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_orders_restaurant FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_orders_customer (customer_id),
    INDEX idx_orders_restaurant (restaurant_id),
    INDEX idx_orders_status (status)
) ENGINE=InnoDB AUTO_INCREMENT=1024;

-- ------------------------------------------------------------
-- order_items: the individual lines inside an order. The name and
-- unit_price are COPIED here at order time, so order history stays
-- correct even if the menu item is later edited or deleted.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_items (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    order_id     INT           NOT NULL,
    food_item_id INT           NULL,                  -- NULL if the menu item was later deleted
    item_name    VARCHAR(150)  NOT NULL,
    quantity     INT           NOT NULL,
    unit_price   DECIMAL(10,2) NOT NULL,
    subtotal     DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_oi_order FOREIGN KEY (order_id)
        REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_oi_food FOREIGN KEY (food_item_id)
        REFERENCES food_items(id) ON DELETE SET NULL,
    INDEX idx_oi_order (order_id)
) ENGINE=InnoDB;
