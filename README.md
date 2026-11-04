# Replate 7.0 (Food Saver)

A food-saving website: restaurants list unsold food, customers order it, and the
order is verified at pickup with a QR code / pickup code.

**Replate 7.0** replaces the old in-memory storage with a real **MySQL database**
accessed through **JDBC**, and adds **user registration, login, BCrypt password
hashing and server-side sessions**. All important data (accounts, menus, orders,
order items) now lives permanently in MySQL and survives restarts.

The original home-page design (`index.html`, `style.css`) and both dashboards are
unchanged in look — only the backend and the login/registration flow changed.

---

## 1. What you need installed

| Tool | Version | Notes |
|------|---------|-------|
| **Java JDK** | 11 or newer (tested on 26) | Needed to compile and run. |
| **MySQL Server** | 8.0+ | The database. Tested on 8.0.45. |
| **MySQL Workbench** | any | Optional, to view data and run `database.sql`. |

No Maven and no separate Tomcat install — the web server is embedded. The MySQL
Connector/J and BCrypt libraries are included in `lib/`.

---

## 2. Set up the database (one time)

### Option A — MySQL Workbench
1. Open MySQL Workbench and connect to your local MySQL server.
2. `File ▸ Open SQL Script…` and choose **`database.sql`** from this folder.
3. Click the **lightning-bolt (Execute)** button.
4. In the left "Schemas" panel, refresh — you should see the **`replate_db`**
   database with 5 tables: `users`, `restaurants`, `food_items`, `orders`,
   `order_items`.

### Option B — command line
```
mysql -u root -p < database.sql
```

Running `database.sql` again later is safe — it only creates what is missing.

---

## 3. Tell Replate your MySQL password

Credentials are **never hardcoded**. Copy the template and fill in your details:

1. Copy **`db.properties.example`** to **`db.properties`** (same folder).
2. Edit `db.properties`:
   ```
   db.url=jdbc:mysql://localhost:3306/replate_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
   db.user=root
   db.password=YOUR_MYSQL_PASSWORD
   ```
`db.properties` is listed in `.gitignore`, so your real password is never committed.

> Alternatively, set the environment variables `DB_URL`, `DB_USER`, `DB_PASSWORD`
> (see `.env.example`). If present, they take priority over `db.properties`.

If MySQL is unavailable or the password is wrong, Replate prints a clear error at
startup and stops. **It never silently falls back to in-memory storage.**

---

## 4. Run Replate

1. Double-click **`run.bat`** (or run `run.bat` in a terminal in this folder).
   It compiles the Java backend and starts the embedded web server.
2. Wait for:
   ```
   Connected to MySQL database 'replate_db'.
   Replate (Food Saver) is running!
   ```
3. Open **http://localhost:8080** in your browser.
4. Press `Ctrl + C` in the window to stop the server.

Run on a different port if 8080 is busy:
```
java -Dreplate.port=8099 -cp "out;lib/*" com.replate.Main
```

---

## 5. Using the app

1. Open http://localhost:8080 and click **Log in**, then **Register here**.
2. **Register a Restaurant** (needs a restaurant name + address) — log in — add food items.
3. **Register a Customer** — log in — browse food, add to cart, place an order.
4. Back as the restaurant: **Accept** the order, **Mark as Packed**, then **Verify**
   the customer's QR code / pickup code to complete it.

Your registered **account type decides the dashboard** — you cannot pick it at
login, and typing `customer.html` / `restaurant.html` without logging in (or with
the wrong role) redirects you to the login page.

---

## 6. The database tables (in simple language)

| Table | What it stores | Key links |
|-------|----------------|-----------|
| **users** | Every account (customers and restaurant owners). Holds name, email (unique), phone, the **BCrypt password hash**, and the role. | — |
| **restaurants** | Extra info for a restaurant account: its name and address. | `user_id → users.id` (one row per restaurant user) |
| **food_items** | The menu. Each dish: name, price, category, description, image path, quantity, available flag. | `restaurant_id → restaurants.id` |
| **orders** | One row per placed order: who ordered, from which restaurant, total, status, pickup code. | `customer_id → users.id`, `restaurant_id → restaurants.id` |
| **order_items** | The individual lines inside an order. Name and unit price are **copied** here so history stays correct even if the menu changes later. | `order_id → orders.id`, `food_item_id → food_items.id` |

**Relationships:** a *user* can be a *restaurant* (1-to-1). A *restaurant* has many
*food_items*. A *user* (customer) places many *orders*; each *order* belongs to one
*restaurant* and has many *order_items*. Deleting a user/restaurant cascades to their
rows; deleting a food item keeps order history (its `food_item_id` becomes `NULL`).

---

## 7. How each flow works (frontend → Java → MySQL)

**Registration** — `register.html` validates the form and `POST`s JSON to
`/api/auth/register`. `AuthServlet` validates again, checks the email is not taken
(`UserDAO.existsByEmail`), hashes the password with **BCrypt** (`PasswordUtil`), and
inserts into `users` (plus `restaurants` in one transaction for restaurant accounts).

**Login** — `login.html` `POST`s to `/api/auth/login`. `AuthServlet` looks up the
user (`UserDAO.findByEmail`), verifies the password with BCrypt, then creates a
server-side **session** storing the user id, name, role and (for restaurants) the
restaurant id. The session id travels in an **HttpOnly cookie**. The response tells
the page which dashboard to open.

**Restaurant adds food** — `restaurant.js` sends a multipart form to `/api/food`.
`AuthenticationFilter` confirms the caller is a restaurant; `FoodItemServlet` saves
the image to `uploads/food/`, takes the **restaurant id from the session** (never the
browser), and inserts the row via `FoodItemDAO`.

**Customer orders** — `customer.js` sends `{foodItemId, quantity}` lines to
`/api/orders`. `OrderServlet` takes the **customer id from the session** and calls
`OrderDAO.createOrder`, which runs one **transaction**: lock the food rows, check
availability and stock, compute the total **from DB prices**, insert the `orders` and
`order_items` rows, and reduce stock — all-or-nothing.

**Order status & pickup** — the restaurant `POST`s to `/api/orders/status`
(accept/reject/pack) and `/api/pickup/verify`. Both check the order **belongs to the
logged-in restaurant** before updating MySQL. Accepting generates a unique pickup
code; verifying it once sets the order to `COMPLETED` and refuses a second time.

---

## 8. Project structure

```
index.html, style.css          Original home page (unchanged design)
login.html, login.css          Real email+password login
register.html                  New registration page (customer / restaurant)
customer.html, customer.js      Customer dashboard
restaurant.html, restaurant.js  Restaurant dashboard
app.css                         Shared dashboard styles

database.sql                    Creates replate_db and all tables + indexes
db.properties.example           Template for DB credentials (copy to db.properties)
.env.example                    Alternative: DB_URL / DB_USER / DB_PASSWORD
.gitignore                      Keeps db.properties, out/, uploads out of git

src/main/java/com/replate/
  Main.java                     Starts embedded Tomcat, checks the DB, registers servlets + filter
  config/DatabaseConnection.java  Opens JDBC connections (reads db.properties / env)
  model/                        User, Restaurant, FoodItem, Order, OrderItem
  dao/                          UserDAO, RestaurantDAO, FoodItemDAO, OrderDAO (all SQL lives here)
  servlet/                      AuthServlet, FoodItemServlet, OrderServlet, OrderStatusServlet, PickupServlet
  filter/AuthenticationFilter.java  Session + role gate for /api/* and the dashboards
  util/                         PasswordUtil (BCrypt), SessionUtil, Json

lib/                            Embedded Tomcat, Gson, MySQL Connector/J, jBCrypt
out/                            Compiled classes (created by run.bat)
uploads/food/                   Uploaded food images (relative paths stored in MySQL)
```

---

## 9. Dependencies added in 7.0

| Library | File in `lib/` | Why |
|---------|----------------|-----|
| **MySQL Connector/J** | `mysql-connector-j-8.4.0.jar` | The JDBC driver that lets Java talk to MySQL. |
| **jBCrypt** | `jbcrypt-0.4.jar` | Hashes and verifies passwords with BCrypt. |

`run.bat` already puts `lib/*` on the classpath, so no classpath change was needed.

---

## 10. Backend API

| Method | URL | Who | Purpose |
|--------|-----|-----|---------|
| POST | `/api/auth/register` | anyone | Create a customer or restaurant account |
| POST | `/api/auth/login` | anyone | Log in, start a session |
| GET  | `/api/auth/me` | logged in | Who am I? (id, name, role) |
| POST | `/api/auth/logout` | logged in | End the session |
| GET  | `/api/food` | customer / restaurant | Customer: available items; restaurant: own items |
| POST | `/api/food` | restaurant | Add a food item (multipart) |
| POST | `/api/food/{id}` | restaurant | Update own food item (multipart) |
| DELETE | `/api/food/{id}` | restaurant | Delete own food item |
| GET  | `/api/orders` | customer / restaurant | Own orders (session-scoped) |
| POST | `/api/orders` | customer | Place an order |
| POST | `/api/orders/status` | restaurant | accept / reject / pack an own order |
| POST | `/api/pickup/verify` | restaurant | Verify pickup code, complete the order |

> The QR scanner camera only works on `http://localhost` or HTTPS (a browser rule).
> Pickup-code verification by typing always works. One order must be from a single
> restaurant (mix from two restaurants and you'll be asked to split the order).

---

## 11. Tested scenarios (all passing)

Verified end-to-end against a running server + live MySQL (see §13 of the task):

**Authentication** — register customer ✓, register restaurant ✓, duplicate email
rejected (409) ✓, password mismatch rejected ✓, correct login ✓, wrong password
rejected (401) ✓, unregistered login rejected ✓, role-based redirect ✓, dashboard
blocked without login (redirect to login) ✓, API blocked without login (401) ✓,
logout invalidates session ✓.

**Restaurant** — add item (with image saved to `uploads/food/`) ✓, item appears in
MySQL ✓, list own items ✓, edit name/price/quantity/category/description ✓, change
quantity ✓, hide/show (available) ✓, delete ✓.

**Customer** — browse available items (hidden items not shown) ✓, place order ✓,
`orders` + `order_items` rows created ✓, server-computed total ✓, stock reduced ✓,
oversell rejected ✓, order history ✓.

**Order workflow** — accept → ACCEPTED (+ pickup code) ✓, reject → REJECTED ✓,
pack → READY_FOR_PICKUP ✓, verify → COMPLETED ✓, re-verify rejected ✓.

**Security / isolation** — customer cannot add food or verify pickups (403) ✓,
restaurant cannot place orders (403) ✓, a restaurant cannot view/edit/delete another
restaurant's items or orders (403) ✓, mixed-restaurant cart rejected ✓.

**Persistence** — after fully stopping and restarting the server, all users,
restaurants, food items and orders (with their statuses and pickup codes) remained
in MySQL ✓.
