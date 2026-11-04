/*
 * customer.js – Customer Dashboard logic.
 *
 * Backend API calls (unchanged from original):
 *   GET  /api/food                -> load all food items
 *   POST /api/orders              -> place an order
 *   GET  /api/orders?customerId=1 -> load this customer's orders
 *
 * The cart lives in the browser (localStorage) until "Place Order" is clicked.
 * Search and category filtering are client-side only.
 */

// The logged-in customer's identity comes from the server session, never from
// a hardcoded id. We fetch it from /api/auth/me on startup (see checkAuth).
let currentUser = null;

// The cart: array of { id, name, price, quantity }.
let cart = loadCart();

// All food items fetched from backend (used for client-side search/filter).
let allFoodItems = [];

// Current filter/search state.
let activeCategory = 'all';
let searchQuery = '';
let sortMode = 'default';

/* ==============================================================
   LOADING THE MENU
   ============================================================== */

// Fetch food items from the backend and display them as cards.
async function loadMenu() {
  try {
    const response = await fetch('/api/food');
    if (response.status === 401) { redirectToLogin(); return; }
    const items = await response.json();
    allFoodItems = items;
    syncCartWithMenu();
    renderCart();
    renderMenu();
  } catch (e) {
    document.getElementById('menu').innerHTML =
      '<p class="empty-note">Could not load food items. Is the server running?</p>';
  }
}

// Normalise a category string for case-insensitive comparison.
// Anything empty or unknown is treated as "other" so it still shows under
// "All" and under the "Other" filter - never lost.
function normalizeCategory(value) {
  const known = ['meals', 'snacks', 'desserts', 'beverages', 'other'];
  const c = (value || '').trim().toLowerCase();
  return known.includes(c) ? c : 'other';
}

// Render food items after applying availability, search, category and sort.
// This single function is shared by every category button and the search box,
// so the two filters always work together.
function renderMenu() {
  const menu = document.getElementById('menu');
  let items = [...allFoodItems];

  // 1. Only show items the restaurant has marked available.
  //    (available is true by default; only an explicit false hides an item.)
  items = items.filter(item => item.available !== false);

  // 2. Category filter (uses the REAL category from the backend).
  if (activeCategory && activeCategory !== 'all') {
    items = items.filter(item => normalizeCategory(item.category) === activeCategory);
  }

  // 3. Search filter (match item name, case-insensitive).
  if (searchQuery) {
    const q = searchQuery.toLowerCase();
    items = items.filter(item => item.name.toLowerCase().includes(q));
  }

  // 4. Sort.
  if (sortMode === 'price-low') {
    items.sort((a, b) => a.price - b.price);
  } else if (sortMode === 'price-high') {
    items.sort((a, b) => b.price - a.price);
  } else if (sortMode === 'name') {
    items.sort((a, b) => a.name.localeCompare(b.name));
  }

  if (items.length === 0) {
    if (allFoodItems.length === 0) {
      menu.innerHTML = '<p class="empty-note">No food has been added yet. Please check back soon.</p>';
    } else {
      menu.innerHTML = '<p class="empty-note">No items match your search or category.</p>';
    }
    return;
  }

  menu.innerHTML = '';
  items.forEach(item => {
    const cartItem = cart.find(c => c.id === item.id);
    const qty = cartItem ? cartItem.quantity : 0;
    const stock = Number.isInteger(Number(item.quantity)) ? Number(item.quantity) : 0;
    const soldOut = stock <= 0;

    // Restaurant this item belongs to (comes from the backend JOIN). Shown so
    // the customer can see which restaurant they are ordering from.
    const storeHtml = item.restaurantName
      ? '<p class="food-store">' + escapeHtml(item.restaurantName) + '</p>'
      : '';

    // Image: real uploaded picture if present, else a placeholder icon.
    let imgHtml;
    if (item.imageUrl) {
      imgHtml = '<img src="' + encodeURI(item.imageUrl) + '" alt="' + escapeHtml(item.name) + '">';
    } else {
      imgHtml = '<div class="img-placeholder">🍽</div>';
    }

    const card = document.createElement('div');
    card.className = 'food-card';
    card.innerHTML =
      '<div class="food-img">' + imgHtml +
        '<span class="qty-badge' + (soldOut ? ' out-of-stock' : '') + '">' +
          (soldOut ? 'Out of stock' : item.quantity + ' available') +
        '</span>' +
      '</div>' +
      '<div class="food-info">' +
        '<h3 class="food-name">' + escapeHtml(item.name) + '</h3>' +
        storeHtml +
        '<p class="food-restaurant">' + escapeHtml(item.category || 'Other') + '</p>' +
        '<div class="food-price-row">' +
          '<span class="food-price">₹' + item.price + '</span>' +
        '</div>' +
      '</div>' +
      '<div class="card-action">' +
        '<div class="qty-control">' +
          '<button class="qty-minus" aria-label="Decrease quantity">−</button>' +
          '<span class="qty-display">' + qty + '</span>' +
          '<button class="qty-plus" aria-label="Increase quantity"' + (soldOut || qty >= stock ? ' disabled' : '') + '>+</button>' +
        '</div>' +
        '<button class="btn btn-small add-btn"' + (soldOut ? ' disabled' : '') + '>' +
          (soldOut ? 'Out of Stock' : 'Add to Cart') +
        '</button>' +
      '</div>';

    // Wire up quantity buttons.
    card.querySelector('.qty-minus').addEventListener('click', () => {
      changeQuantity(item.id, -1);
      renderMenu(); // re-render to update displayed qty
    });
    card.querySelector('.qty-plus').addEventListener('click', () => {
      changeQuantity(item.id, 1);
      renderMenu();
    });

    // Add to cart button adds 1 (or uses displayed qty if > 0).
    card.querySelector('.add-btn').addEventListener('click', () => {
      addToCart(item);
    });

    menu.appendChild(card);
  });
}

/* ==============================================================
   SEARCH, CATEGORY & SORT
   ============================================================== */

// Search input: filter as user types.
document.getElementById('searchInput').addEventListener('input', function () {
  searchQuery = this.value.trim();
  renderMenu();
});

// Sort dropdown.
document.getElementById('sortSelect').addEventListener('change', function () {
  sortMode = this.value;
  renderMenu();
});

// Category filter buttons.
document.getElementById('categoryFilters').addEventListener('click', function (e) {
  const btn = e.target.closest('.category-btn');
  if (!btn) return;

  // Update active state.
  this.querySelectorAll('.category-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  activeCategory = btn.dataset.category;
  renderMenu();
});

/* ==============================================================
   THE CART
   ============================================================== */

function addToCart(item) {
  const existing = cart.find(c => c.id === item.id);
  if (!item.quantity || (existing && existing.quantity >= item.quantity)) {
    return;
  }
  if (existing) {
    existing.quantity++;
  } else {
    cart.push({ id: item.id, name: item.name, price: item.price,
      imageUrl: item.imageUrl || '', quantity: 1 });
  }
  saveCart();
  renderCart();
  renderMenu(); // update qty display on cards
}

function changeQuantity(id, change) {
  const line = cart.find(c => c.id === id);
  if (!line) {
    // If not in cart yet and trying to increase, do nothing
    // (they should click "Add to Cart" first).
    if (change > 0) {
      // Find the item in allFoodItems and add it.
      const item = allFoodItems.find(i => i.id === id);
      if (item) addToCart(item);
    }
    return;
  }
  const item = allFoodItems.find(i => i.id === id);
  if (change > 0 && item && line.quantity >= item.quantity) {
    return;
  }
  line.quantity += change;
  if (line.quantity <= 0) {
    cart = cart.filter(c => c.id !== id);
  }
  saveCart();
  renderCart();
}

function removeFromCart(id) {
  cart = cart.filter(c => c.id !== id);
  saveCart();
  renderCart();
  renderMenu();
}

// Keep old localStorage cart entries in sync with the latest menu data.
// This also adds the uploaded image to items saved before this feature existed.
function syncCartWithMenu() {
  cart = cart.filter(line => {
    const item = allFoodItems.find(i => i.id === line.id);
    if (!item || item.quantity <= 0) return false;
    line.name = item.name;
    line.price = item.price;
    line.imageUrl = item.imageUrl || '';
    line.quantity = Math.min(line.quantity, item.quantity);
    return line.quantity > 0;
  });
  saveCart();
}

function clearCart() {
  cart = [];
  saveCart();
  renderCart();
  renderMenu();
}

// Draw the cart in the sidebar.
function renderCart() {
  const cartDiv = document.getElementById('cart');
  const summaryDiv = document.getElementById('cartSummary');
  const countSpan = document.getElementById('cartCount');
  const totalSpan = document.getElementById('cartTotal');
  const itemCountSpan = document.getElementById('cartItemCount');

  const totalItems = cart.reduce((sum, c) => sum + c.quantity, 0);
  countSpan.textContent = totalItems;

  if (cart.length === 0) {
    cartDiv.innerHTML = '<p class="empty-note">Your cart is empty.</p>';
    summaryDiv.style.display = 'none';
    return;
  }

  let total = 0;
  cartDiv.innerHTML = '';

  cart.forEach(line => {
    const lineTotal = line.price * line.quantity;
    total += lineTotal;

    const row = document.createElement('div');
    row.className = 'cart-item';
    const cartImage = line.imageUrl
      ? '<img src="' + encodeURI(line.imageUrl) + '" alt="' + escapeHtml(line.name) + '">'
      : '<div class="thumb-placeholder">🍽</div>';
    row.innerHTML =
      '<div class="cart-thumb">' + cartImage + '</div>' +
      '<div class="cart-item-info">' +
        '<p class="cart-item-name">' + escapeHtml(line.name) + '</p>' +
        '<span class="cart-item-price">₹' + line.price + '</span>' +
      '</div>' +
      '<div class="cart-qty">' +
        '<button aria-label="Decrease">−</button>' +
        '<span>' + line.quantity + '</span>' +
        '<button aria-label="Increase">+</button>' +
      '</div>' +
      '<button class="cart-remove" title="Remove item" aria-label="Remove ' + escapeHtml(line.name) + '">✕</button>';

    // Quantity buttons.
    const qtyBtns = row.querySelectorAll('.cart-qty button');
    qtyBtns[0].addEventListener('click', () => {
      changeQuantity(line.id, -1);
      renderMenu();
    });
    qtyBtns[1].addEventListener('click', () => {
      changeQuantity(line.id, 1);
      renderMenu();
    });

    // Remove button.
    row.querySelector('.cart-remove').addEventListener('click', () => removeFromCart(line.id));

    cartDiv.appendChild(row);
  });

  // Show summary.
  summaryDiv.style.display = 'block';
  itemCountSpan.textContent = totalItems;
  totalSpan.textContent = '₹' + total;
}

/* ==============================================================
   PLACING AN ORDER
   ============================================================== */

async function placeOrder() {
  if (cart.length === 0) {
    showCartMessage('Your cart is empty.', 'bad');
    return;
  }

  // Send only id + quantity; the backend looks up the real price.
  const items = cart.map(line => ({ foodItemId: line.id, quantity: line.quantity }));

  try {
    const response = await fetch('/api/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items: items })
    });
    const data = await response.json();

    if (!response.ok) {
      showCartMessage(data.error || 'Could not place the order.', 'bad');
      return;
    }

    // Success: clear cart and refresh orders.
    cart = [];
    saveCart();
    renderCart();
    renderMenu();
    showCartMessage('Order #' + data.id + ' placed! Waiting for the restaurant to accept it.', 'good');
    loadOrders();
  } catch (e) {
    showCartMessage('Could not reach the server.', 'bad');
  }
}

function showCartMessage(text, type) {
  const box = document.getElementById('cartMessage');
  box.textContent = text;
  box.className = 'message ' + (type === 'bad' ? 'bad' : 'good');
  box.style.display = 'block';
  // Auto-hide after 6 seconds.
  setTimeout(() => { box.style.display = 'none'; }, 6000);
}

/* ==============================================================
   MY ORDERS
   ============================================================== */

async function loadOrders() {
  const ordersDiv = document.getElementById('orders');
  try {
    // The backend returns ONLY this logged-in customer's orders (session-based).
    const response = await fetch('/api/orders');
    if (response.status === 401) { redirectToLogin(); return; }
    const orders = await response.json();

    if (orders.length === 0) {
      ordersDiv.innerHTML = '<p class="empty-note">You have not placed any orders yet.</p>';
      return;
    }

    // Newest first.
    orders.sort((a, b) => b.id - a.id);
    ordersDiv.innerHTML = '';
    orders.forEach(order => ordersDiv.appendChild(buildOrderCard(order)));
  } catch (e) {
    ordersDiv.innerHTML = '<p class="empty-note">Could not load your orders.</p>';
  }
}

// Build a single order card with status and QR code.
function buildOrderCard(order) {
  const card = document.createElement('div');
  card.className = 'order-card';

  let itemsHtml = '';
  order.items.forEach(item => {
    itemsHtml += '<div>' + escapeHtml(item.name) + ' × ' + item.quantity + '</div>';
  });

  card.innerHTML =
    '<div class="order-head">' +
      '<h3>Order #' + order.id + '</h3>' +
      '<span class="status ' + order.status.toLowerCase() + '">' + prettyStatus(order.status) + '</span>' +
    '</div>' +
    '<div class="order-items">' + itemsHtml + '</div>' +
    '<div class="order-total">Total: ₹' + order.totalAmount + '</div>';

  // Status-specific messages and QR code.
  if (order.status === 'PENDING') {
    card.innerHTML += '<div class="message">Waiting for the restaurant to accept your order.</div>';

  } else if (order.status === 'REJECTED') {
    card.innerHTML += '<div class="message bad">Order not accepted by the restaurant.</div>';

  } else if (order.status === 'ACCEPTED' || order.status === 'READY_FOR_PICKUP') {
    if (order.status === 'READY_FOR_PICKUP') {
      card.innerHTML += '<div class="message good">Your order is ready for pickup. Show your QR code at the restaurant.</div>';
    } else {
      card.innerHTML += '<div class="message good">Order accepted! The restaurant is preparing your order.</div>';
    }

    // QR code box.
    const qrBox = document.createElement('div');
    qrBox.className = 'qr-box';
    qrBox.innerHTML =
      '<p class="label">Your pickup QR</p>' +
      '<div class="qr" id="qr-' + order.id + '"></div>' +
      '<div class="pickup-code">' + order.pickupCode + '</div>' +
      '<small>Show this QR code or read out the pickup code when collecting your order.</small>';
    card.appendChild(qrBox);

    // Generate QR image after the element is in the DOM.
    setTimeout(() => {
      const target = document.getElementById('qr-' + order.id);
      if (target && window.QRCode) {
        target.innerHTML = '';
        new QRCode(target, { text: order.qrInfo, width: 150, height: 150 });
      }
    }, 0);

  } else if (order.status === 'COMPLETED') {
    card.innerHTML += '<div class="message good"><strong>Order completed.</strong> Your order has been successfully picked up. Thank you for using Replate!</div>';
  }

  return card;
}

/* ==============================================================
   HELPERS
   ============================================================== */

function prettyStatus(status) {
  return status.toLowerCase().split('_')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

function saveCart() {
  try { localStorage.setItem('replateCart', JSON.stringify(cart)); } catch (e) {}
}
function loadCart() {
  try { return JSON.parse(localStorage.getItem('replateCart')) || []; } catch (e) { return []; }
}

// Prevent XSS if a name contains < or >.
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

/* ==============================================================
   AUTHENTICATION (session-based)
   ============================================================== */

function redirectToLogin() {
  window.location.href = 'login.html';
}

// Confirm we are logged in as a CUSTOMER before showing anything.
async function checkAuth() {
  try {
    const response = await fetch('/api/auth/me');
    if (!response.ok) { redirectToLogin(); return false; }
    currentUser = await response.json();
    if (currentUser.role !== 'CUSTOMER') {
      // A restaurant account landed here - send it to its own dashboard.
      window.location.href = 'restaurant.html';
      return false;
    }
    const greeting = document.getElementById('userGreeting');
    if (greeting) greeting.textContent = 'Hi, ' + currentUser.name;
    return true;
  } catch (e) {
    redirectToLogin();
    return false;
  }
}

async function logout() {
  try {
    await fetch('/api/auth/logout', { method: 'POST' });
  } catch (e) { /* ignore - we redirect either way */ }
  redirectToLogin();
}

/* ==============================================================
   START
   ============================================================== */

document.getElementById('placeOrderBtn').addEventListener('click', placeOrder);
document.getElementById('clearCartBtn').addEventListener('click', clearCart);
document.getElementById('logoutBtn').addEventListener('click', function (e) {
  e.preventDefault();
  logout();
});

// Only start loading data once we know who is logged in.
checkAuth().then(ok => {
  if (!ok) return;
  loadMenu();
  renderCart();
  loadOrders();

  // Auto-refresh menu and orders every 4 seconds.
  setInterval(() => {
    loadMenu();
    loadOrders();
  }, 4000);
});
