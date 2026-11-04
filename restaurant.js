/*
 * restaurant.js – Restaurant Dashboard logic.
 *
 * Backend API calls:
 *   GET    /api/food         -> list the menu
 *   POST   /api/food         -> add a food item   (multipart: name, price, category, description, image)
 *   POST   /api/food/{id}    -> update a food item (multipart: same fields; image optional)
 *   DELETE /api/food/{id}    -> delete a food item
 *   GET    /api/orders       -> list all orders
 *   POST   /api/orders/status-> accept / reject / pack an order
 *   POST   /api/pickup/verify-> verify a QR scan or typed pickup code
 *
 * Images are uploaded to the SERVER (not the browser). The server returns an
 * imageUrl like "/uploads/food/ab12.jpg" which both this page and the customer
 * page use to load the picture - that is what keeps the two dashboards in sync.
 */

// The logged-in restaurant user (from the server session, not hardcoded).
let currentUser = null;

// Local copy of the menu, used to pre-fill the edit modal.
let menuItems = [];

// The id of the food item currently being edited.
let editingItemId = null;

/* ==============================================================
   ADD A FOOD ITEM
   ============================================================== */

async function addFoodItem() {
  const name = document.getElementById('foodName').value.trim();
  const priceText = document.getElementById('foodPrice').value.trim();
  const quantityText = document.getElementById('foodQuantity').value.trim();
  const category = document.getElementById('foodCategory').value;
  const desc = document.getElementById('foodDesc').value.trim();
  const imageInput = document.getElementById('foodImage');

  // Client-side validation (the server validates again).
  if (name === '') {
    showAddMessage('Please enter a food item name.', 'bad');
    return;
  }
  const price = Number(priceText);
  if (priceText === '' || isNaN(price) || price <= 0) {
    showAddMessage('Please enter a valid price greater than 0.', 'bad');
    return;
  }
  const quantity = Number(quantityText);
  if (quantityText === '' || !Number.isInteger(quantity) || quantity < 0) {
    showAddMessage('Please enter a whole quantity of 0 or more.', 'bad');
    return;
  }
  if (!category) {
    showAddMessage('Please choose a category.', 'bad');
    return;
  }

  // Build a multipart form so the image file can be sent along with the text.
  const form = new FormData();
  form.append('name', name);
  form.append('price', price);
  form.append('quantity', quantity);
  form.append('category', category);
  form.append('description', desc);
  if (imageInput.files && imageInput.files.length > 0) {
    form.append('image', imageInput.files[0]);
  }

  try {
    // NOTE: do NOT set Content-Type here; the browser sets the correct
    // multipart boundary automatically when the body is a FormData.
    const response = await fetch('/api/food', { method: 'POST', body: form });
    const data = await response.json();

    if (!response.ok) {
      showAddMessage(data.error || 'Could not add the item.', 'bad');
      return;
    }

    // Clear the form.
    document.getElementById('foodName').value = '';
    document.getElementById('foodPrice').value = '';
    document.getElementById('foodQuantity').value = '';
    document.getElementById('foodCategory').value = 'Meals';
    document.getElementById('foodDesc').value = '';
    imageInput.value = '';
    showAddMessage('Added "' + data.name + '" for ₹' + data.price + '.', 'good');
    loadMenu();
  } catch (e) {
    showAddMessage('Could not reach the server.', 'bad');
  }
}

function showAddMessage(text, type) {
  const box = document.getElementById('addMessage');
  box.textContent = text;
  box.className = 'message ' + (type === 'bad' ? 'bad' : 'good');
  box.style.display = 'block';
  setTimeout(() => { box.style.display = 'none'; }, 5000);
}

/* ==============================================================
   SHOW THE MENU
   ============================================================== */

async function loadMenu() {
  const menu = document.getElementById('menu');
  try {
    const response = await fetch('/api/food');
    if (response.status === 401) { redirectToLogin(); return; }
    const items = await response.json();
    menuItems = items;

    if (items.length === 0) {
      menu.innerHTML = '<p class="empty-note">No items yet. Add your first item above.</p>';
      return;
    }

    menu.innerHTML = '';
    items.forEach(item => {
      const card = document.createElement('div');
      card.className = 'rest-menu-card';

      // Thumbnail: real uploaded image if present, else a placeholder icon.
      let thumbHtml;
      if (item.imageUrl) {
        thumbHtml = '<img src="' + encodeURI(item.imageUrl) + '" alt="' + escapeHtml(item.name) + '">';
      } else {
        thumbHtml = '<div class="thumb-placeholder">🍽</div>';
      }

      // "Hidden" tag if the restaurant marked the item unavailable.
      const hiddenTag = item.available === false
        ? ' <span class="status rejected" style="margin-left:6px;">Hidden</span>'
        : '';
      const stockTag = item.quantity <= 0
        ? ' <span class="status rejected" style="margin-left:6px;">Out of stock</span>'
        : ' <span class="menu-stock">· ' + item.quantity + ' available</span>';

      card.innerHTML =
        '<div class="menu-thumb">' + thumbHtml + '</div>' +
        '<div class="menu-details">' +
          '<h4>' + escapeHtml(item.name) + hiddenTag + '</h4>' +
          '<span class="menu-price">₹' + item.price + '</span>' +
          ' <span class="menu-category">· ' + escapeHtml(item.category || 'Other') + '</span>' +
          '<div class="menu-stock-line">' + stockTag + '</div>' +
        '</div>' +
        '<div class="menu-actions">' +
          '<button class="btn btn-outline btn-xs edit-btn">Edit</button>' +
          '<button class="btn btn-danger-outline btn-xs delete-btn">Delete</button>' +
        '</div>';

      card.querySelector('.edit-btn').addEventListener('click', () => openEditModal(item.id));
      card.querySelector('.delete-btn').addEventListener('click', () => showDeleteConfirm(card, item));

      menu.appendChild(card);
    });
  } catch (e) {
    menu.innerHTML = '<p class="empty-note">Could not load the menu.</p>';
  }
}

// Inline confirmation before deleting, so there is no accidental loss.
function showDeleteConfirm(card, item) {
  if (card.querySelector('.confirm-bar')) return; // don't stack confirmations

  const bar = document.createElement('div');
  bar.className = 'confirm-bar';
  bar.innerHTML =
    '<span>Delete "' + escapeHtml(item.name) + '"?</span>' +
    '<button class="btn btn-danger btn-xs confirm-yes">Yes, Delete</button>' +
    '<button class="btn btn-outline btn-xs confirm-no">Cancel</button>';

  bar.querySelector('.confirm-yes').addEventListener('click', () => deleteItem(item.id));
  bar.querySelector('.confirm-no').addEventListener('click', () => bar.remove());

  card.appendChild(bar);
}

// Delete the item on the server, then refresh the menu.
async function deleteItem(id) {
  try {
    const response = await fetch('/api/food/' + id, { method: 'DELETE' });
    const data = await response.json();
    if (!response.ok) {
      alert(data.error || 'Could not delete the item.');
      return;
    }
    loadMenu();
  } catch (e) {
    alert('Could not reach the server.');
  }
}

/* ==============================================================
   EDIT FOOD MODAL
   ============================================================== */

function openEditModal(foodId) {
  const item = menuItems.find(i => i.id === foodId);
  if (!item) return;

  editingItemId = foodId;

  // Pre-fill the form with this item's current values.
  document.getElementById('editName').value = item.name;
  document.getElementById('editPrice').value = item.price;
  document.getElementById('editQuantity').value = item.quantity;
  document.getElementById('editDesc').value = item.description || '';
  document.getElementById('editCategory').value = item.category || 'Other';
  document.getElementById('editAvailable').checked = item.available !== false;
  document.getElementById('editImage').value = '';

  // Current image preview.
  const preview = document.getElementById('editImgPreview');
  if (item.imageUrl) {
    preview.innerHTML = '<img src="' + encodeURI(item.imageUrl) + '" alt="Current image">';
  } else {
    preview.innerHTML = '<div class="thumb-placeholder">🍽</div>';
  }

  document.getElementById('editMessage').style.display = 'none';

  document.getElementById('editModal').classList.add('open');
  document.body.style.overflow = 'hidden'; // stop background scroll
}

function closeEditModal() {
  document.getElementById('editModal').classList.remove('open');
  document.body.style.overflow = '';
  editingItemId = null;
}

// Save the edited item to the server.
async function saveEditItem() {
  if (editingItemId === null) return;

  const name = document.getElementById('editName').value.trim();
  const priceText = document.getElementById('editPrice').value.trim();
  const quantityText = document.getElementById('editQuantity').value.trim();
  const category = document.getElementById('editCategory').value;
  const desc = document.getElementById('editDesc').value.trim();
  const available = document.getElementById('editAvailable').checked;
  const imageInput = document.getElementById('editImage');

  // Client-side validation.
  if (name === '') {
    showEditMessage('Please enter a food item name.', 'bad');
    return;
  }
  const price = Number(priceText);
  if (priceText === '' || isNaN(price) || price <= 0) {
    showEditMessage('Please enter a valid price greater than 0.', 'bad');
    return;
  }
  const quantity = Number(quantityText);
  if (quantityText === '' || !Number.isInteger(quantity) || quantity < 0) {
    showEditMessage('Please enter a whole quantity of 0 or more.', 'bad');
    return;
  }

  // Build the multipart form. The image is included ONLY if a new file was
  // chosen; otherwise the server keeps the current image.
  const form = new FormData();
  form.append('name', name);
  form.append('price', price);
  form.append('quantity', quantity);
  form.append('category', category);
  form.append('description', desc);
  form.append('available', available ? 'true' : 'false');
  if (imageInput.files && imageInput.files.length > 0) {
    form.append('image', imageInput.files[0]);
  }

  try {
    const response = await fetch('/api/food/' + editingItemId, { method: 'POST', body: form });
    const data = await response.json();

    if (!response.ok) {
      showEditMessage(data.error || 'Could not update the item.', 'bad');
      return;
    }

    showEditMessage('Item updated.', 'good');
    // Short pause so the success message is visible, then close + refresh.
    setTimeout(() => {
      closeEditModal();
      loadMenu();
    }, 600);
  } catch (e) {
    showEditMessage('Could not reach the server.', 'bad');
  }
}

function showEditMessage(text, type) {
  const box = document.getElementById('editMessage');
  box.textContent = text;
  box.className = 'message ' + (type === 'bad' ? 'bad' : 'good');
  box.style.display = 'block';
}

// Wire up modal buttons.
document.getElementById('editModalClose').addEventListener('click', closeEditModal);
document.getElementById('editCancelBtn').addEventListener('click', closeEditModal);
document.getElementById('editSaveBtn').addEventListener('click', saveEditItem);

// Close on Escape key.
document.addEventListener('keydown', function (e) {
  if (e.key === 'Escape' && document.getElementById('editModal').classList.contains('open')) {
    closeEditModal();
  }
});

// Close when clicking the dim background (but not the modal body).
document.getElementById('editModal').addEventListener('click', function (e) {
  if (e.target === this) closeEditModal();
});

/* ==============================================================
   ORDERS – TABLE VIEW
   ============================================================== */

async function loadOrders() {
  const ordersDiv = document.getElementById('orders');
  try {
    const response = await fetch('/api/orders');
    if (response.status === 401) { redirectToLogin(); return; }
    const orders = await response.json();
    if (orders.length === 0) {
      ordersDiv.innerHTML = '<p class="empty-note">No orders yet.</p>';
      return;
    }

    orders.sort((a, b) => b.id - a.id); // newest first

    let html =
      '<table class="orders-table">' +
      '<thead><tr>' +
        '<th>Order ID</th>' +
        '<th>Items</th>' +
        '<th>Total Amount</th>' +
        '<th>Status</th>' +
        '<th>Actions</th>' +
      '</tr></thead><tbody>';

    orders.forEach(order => {
      let itemsStr = '';
      order.items.forEach(item => {
        itemsStr += escapeHtml(item.name) + ' × ' + item.quantity + '<br>';
      });

      const statusHtml = '<span class="status ' + order.status.toLowerCase() + '">' +
        prettyStatus(order.status) + '</span>';

      // Only valid actions for each state.
      let actionsHtml = '—';
      if (order.status === 'PENDING') {
        actionsHtml =
          '<button class="btn btn-outline btn-xs accept-btn" data-id="' + order.id + '">Accept</button>' +
          '<button class="btn btn-danger-outline btn-xs reject-btn" data-id="' + order.id + '">Reject</button>';
      } else if (order.status === 'ACCEPTED') {
        actionsHtml =
          '<button class="btn btn-outline btn-xs pack-btn" data-id="' + order.id + '">Mark as Packed</button>' +
          '<button class="btn btn-danger-outline btn-xs reject-btn" data-id="' + order.id + '">Reject</button>';
      }

      html +=
        '<tr>' +
          '<td data-label="Order ID">#' + order.id + '</td>' +
          '<td data-label="Items" class="order-items-cell">' + itemsStr + '</td>' +
          '<td data-label="Total">₹' + order.totalAmount + '</td>' +
          '<td data-label="Status">' + statusHtml + '</td>' +
          '<td data-label="Actions" class="order-actions-cell">' + actionsHtml + '</td>' +
        '</tr>';
    });

    html += '</tbody></table>';
    ordersDiv.innerHTML = html;

    // Attach click handlers to the action buttons.
    ordersDiv.querySelectorAll('.accept-btn').forEach(btn => {
      btn.addEventListener('click', () => updateStatus(Number(btn.dataset.id), 'accept'));
    });
    ordersDiv.querySelectorAll('.reject-btn').forEach(btn => {
      btn.addEventListener('click', () => updateStatus(Number(btn.dataset.id), 'reject'));
    });
    ordersDiv.querySelectorAll('.pack-btn').forEach(btn => {
      btn.addEventListener('click', () => updateStatus(Number(btn.dataset.id), 'pack'));
    });

  } catch (e) {
    ordersDiv.innerHTML = '<p class="empty-note">Could not load orders.</p>';
  }
}

async function updateStatus(orderId, action) {
  try {
    const response = await fetch('/api/orders/status', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ orderId: orderId, action: action })
    });
    const data = await response.json();
    if (!response.ok) {
      alert(data.error || 'Could not update the order.');
    }
    loadOrders();
  } catch (e) {
    alert('Could not reach the server.');
  }
}

/* ==============================================================
   VERIFY A PICKUP
   ============================================================== */

async function verifyCode(code) {
  const resultBox = document.getElementById('verifyResult');
  resultBox.style.display = 'block';

  if (!code || code.trim() === '') {
    resultBox.textContent = 'Please enter a pickup code.';
    resultBox.className = 'verify-result err';
    return;
  }

  try {
    const response = await fetch('/api/pickup/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code.trim() })
    });
    const data = await response.json();

    if (!response.ok) {
      resultBox.textContent = data.error;
      resultBox.className = 'verify-result err';
      return;
    }

    resultBox.textContent = 'Order Verified Successfully! Order #' + data.id + ' — Status: Picked Up.';
    resultBox.className = 'verify-result ok';
    loadOrders();
  } catch (e) {
    resultBox.textContent = 'Could not reach the server.';
    resultBox.className = 'verify-result err';
  }
}

/* ==============================================================
   QR CAMERA SCANNING
   ============================================================== */

let qrScanner = null;

function startScan() {
  if (!window.Html5Qrcode) {
    alert('QR scanner library did not load. You can still type the pickup code.');
    return;
  }
  if (qrScanner) {
    stopScan();
    return;
  }

  qrScanner = new Html5Qrcode('reader');
  document.getElementById('scanBtn').textContent = 'Stop camera';

  qrScanner.start(
    { facingMode: 'environment' },
    { fps: 10, qrbox: 220 },
    (decodedText) => {
      stopScan();
      document.getElementById('pickupCode').value = decodedText;
      verifyCode(decodedText);
    },
    () => { /* ignore frames with no QR */ }
  ).catch(() => {
    alert('Could not open the camera. Please allow camera access, or type the pickup code instead.');
    document.getElementById('scanBtn').textContent = 'Start camera scan';
    qrScanner = null;
  });
}

function stopScan() {
  if (qrScanner) {
    qrScanner.stop().then(() => qrScanner.clear()).catch(() => {});
    qrScanner = null;
    document.getElementById('scanBtn').textContent = 'Start camera scan';
  }
}

/* ==============================================================
   HELPERS
   ============================================================== */

function prettyStatus(status) {
  return status.toLowerCase().split('_')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

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

// Confirm we are logged in as a RESTAURANT before showing anything.
async function checkAuth() {
  try {
    const response = await fetch('/api/auth/me');
    if (!response.ok) { redirectToLogin(); return false; }
    currentUser = await response.json();
    if (currentUser.role !== 'RESTAURANT') {
      window.location.href = 'customer.html';
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

document.getElementById('addItemBtn').addEventListener('click', addFoodItem);
document.getElementById('verifyBtn').addEventListener('click', () => {
  verifyCode(document.getElementById('pickupCode').value);
});
document.getElementById('scanBtn').addEventListener('click', startScan);
document.getElementById('logoutBtn').addEventListener('click', function (e) {
  e.preventDefault();
  logout();
});

// Only start loading data once we know who is logged in.
checkAuth().then(ok => {
  if (!ok) return;
  loadMenu();
  loadOrders();

  // Refresh orders every 4 seconds so new customer orders appear automatically.
  setInterval(loadOrders, 4000);
});
