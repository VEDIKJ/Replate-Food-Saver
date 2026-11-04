package com.replate.servlet;

import com.replate.dao.FoodItemDAO;
import com.replate.model.FoodItem;
import com.replate.model.User;
import com.replate.util.Json;
import com.replate.util.SessionUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/*
 * FoodItemServlet handles the MENU, now backed by MySQL (FoodItemDAO). Mapped to:
 *   /api/food        (list for the caller + add)
 *   /api/food/{id}   (update + delete a single item)
 *
 *   GET    /api/food        -> customers get AVAILABLE items (all restaurants);
 *                              a restaurant gets ITS OWN items (available or hidden)
 *   POST   /api/food        -> add a new food item   (restaurant only, multipart)
 *   POST   /api/food/{id}   -> update an existing item (restaurant only, multipart)
 *   DELETE /api/food/{id}   -> delete an item + its image (restaurant only)
 *
 * The owning restaurant id is ALWAYS taken from the logged-in session - never
 * from the browser - so one restaurant can never add to or edit another's menu.
 * (The AuthenticationFilter has already confirmed the role before we get here.)
 */
public class FoodItemServlet extends HttpServlet {

    private final FoodItemDAO foodDAO = new FoodItemDAO();

    private static final Map<String, String> VALID_CATEGORIES = new HashMap<>();
    static {
        VALID_CATEGORIES.put("meals", "Meals");
        VALID_CATEGORIES.put("snacks", "Snacks");
        VALID_CATEGORIES.put("desserts", "Desserts");
        VALID_CATEGORIES.put("beverages", "Beverages");
        VALID_CATEGORIES.put("other", "Other");
    }

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = new HashMap<>();
    static {
        ALLOWED_IMAGE_TYPES.put("image/jpeg", "jpg");
        ALLOWED_IMAGE_TYPES.put("image/png", "png");
        ALLOWED_IMAGE_TYPES.put("image/gif", "gif");
        ALLOWED_IMAGE_TYPES.put("image/webp", "webp");
    }

    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024; // 2 MB
    private static final String IMAGE_URL_PREFIX = "/uploads/food/";

    private static File uploadDir() {
        return new File(System.getProperty("user.dir"), "uploads/food");
    }

    /* ---------------------------------------------------------------
       GET /api/food
       --------------------------------------------------------------- */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (SessionUtil.isRole(req, User.RESTAURANT)) {
                Integer restaurantId = SessionUtil.restaurantId(req);
                Json.send(resp, 200, foodDAO.findByRestaurant(restaurantId));
            } else {
                // Customers (and anyone else allowed through) see only available items.
                Json.send(resp, 200, foodDAO.findAvailable());
            }
        } catch (SQLException e) {
            Json.error(resp, 500, "Could not load food items.");
        }
    }

    /* ---------------------------------------------------------------
       POST /api/food        -> add
       POST /api/food/{id}   -> update
       --------------------------------------------------------------- */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int id = parseId(req.getPathInfo());
        if (id > 0) {
            updateItem(req, resp, id);
        } else {
            addItem(req, resp);
        }
    }

    private void addItem(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer restaurantId = SessionUtil.restaurantId(req);
        if (restaurantId == null) {
            Json.error(resp, 403, "Only restaurants can add food items.");
            return;
        }

        String name = trim(req.getParameter("name"));
        if (name.isEmpty()) {
            Json.error(resp, 400, "Please enter a food item name.");
            return;
        }
        Double price = parsePrice(req.getParameter("price"));
        if (price == null) {
            Json.error(resp, 400, "Please enter a valid price greater than 0.");
            return;
        }
        Integer quantity = parseQuantity(req.getParameter("quantity"));
        if (quantity == null) {
            Json.error(resp, 400, "Please enter a valid quantity (0 or more).");
            return;
        }
        String category = normalizeCategory(req.getParameter("category"));
        if (category == null) {
            Json.error(resp, 400, "Please choose a valid category.");
            return;
        }
        String description = trim(req.getParameter("description"));

        String imageUrl;
        try {
            imageUrl = saveUploadedImage(req);
        } catch (IllegalArgumentException bad) {
            Json.error(resp, 400, bad.getMessage());
            return;
        }
        if (imageUrl == null) {
            imageUrl = "";
        }

        FoodItem item = new FoodItem(0, name, price, category, description,
                imageUrl, quantity, true, restaurantId);
        try {
            foodDAO.insert(item);
            Json.send(resp, 201, item);
        } catch (SQLException e) {
            // Roll back the orphaned image if the DB insert failed.
            deleteImageFile(imageUrl);
            Json.error(resp, 500, "Could not save the food item.");
        }
    }

    private void updateItem(HttpServletRequest req, HttpServletResponse resp, int id) throws IOException {
        Integer restaurantId = SessionUtil.restaurantId(req);
        if (restaurantId == null) {
            Json.error(resp, 403, "Only restaurants can edit food items.");
            return;
        }

        FoodItem existing;
        try {
            existing = foodDAO.findById(id);
        } catch (SQLException e) {
            Json.error(resp, 500, "Could not load the food item.");
            return;
        }
        if (existing == null) {
            Json.error(resp, 404, "That food item does not exist.");
            return;
        }
        // Ownership check: a restaurant may only edit ITS OWN items.
        if (existing.restaurantId != restaurantId) {
            Json.error(resp, 403, "You are not allowed to edit this item.");
            return;
        }

        String name = trim(req.getParameter("name"));
        if (name.isEmpty()) {
            Json.error(resp, 400, "Please enter a food item name.");
            return;
        }
        Double price = parsePrice(req.getParameter("price"));
        if (price == null) {
            Json.error(resp, 400, "Please enter a valid price greater than 0.");
            return;
        }
        Integer quantity = parseQuantity(req.getParameter("quantity"));
        if (quantity == null) {
            Json.error(resp, 400, "Please enter a valid quantity (0 or more).");
            return;
        }
        String category = normalizeCategory(req.getParameter("category"));
        if (category == null) {
            Json.error(resp, 400, "Please choose a valid category.");
            return;
        }
        String description = trim(req.getParameter("description"));

        String availableRaw = req.getParameter("available");
        boolean available = availableRaw == null || availableRaw.equalsIgnoreCase("true")
                || availableRaw.equals("1") || availableRaw.equalsIgnoreCase("on");

        String oldImageUrl = existing.imageUrl;

        String newImageUrl;
        try {
            newImageUrl = saveUploadedImage(req); // null = no new file chosen
        } catch (IllegalArgumentException bad) {
            Json.error(resp, 400, bad.getMessage());
            return;
        }

        // Apply the changes to the existing item, then save.
        existing.name = name;
        existing.price = price;
        existing.category = category;
        existing.description = description;
        existing.quantity = Math.max(0, quantity);
        existing.available = available;
        if (newImageUrl != null) {
            existing.imageUrl = newImageUrl;
        }

        try {
            foodDAO.update(existing);
        } catch (SQLException e) {
            if (newImageUrl != null) {
                deleteImageFile(newImageUrl); // undo the new upload on failure
            }
            Json.error(resp, 500, "Could not update the food item.");
            return;
        }

        // Only delete the old image after the DB update succeeded.
        if (newImageUrl != null && oldImageUrl != null && !oldImageUrl.isEmpty()) {
            deleteImageFile(oldImageUrl);
        }

        Json.send(resp, 200, existing);
    }

    /* ---------------------------------------------------------------
       DELETE /api/food/{id}
       --------------------------------------------------------------- */
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer restaurantId = SessionUtil.restaurantId(req);
        if (restaurantId == null) {
            Json.error(resp, 403, "Only restaurants can delete food items.");
            return;
        }

        int id = parseId(req.getPathInfo());
        if (id <= 0) {
            Json.error(resp, 400, "Please specify which item to delete.");
            return;
        }

        try {
            FoodItem existing = foodDAO.findById(id);
            if (existing == null) {
                Json.error(resp, 404, "That food item does not exist.");
                return;
            }
            if (existing.restaurantId != restaurantId) {
                Json.error(resp, 403, "You are not allowed to delete this item.");
                return;
            }

            foodDAO.delete(id);

            // Remove the image file after the DB row is gone.
            if (existing.imageUrl != null && !existing.imageUrl.isEmpty()) {
                deleteImageFile(existing.imageUrl);
            }

            Map<String, Object> ok = new HashMap<>();
            ok.put("deleted", true);
            ok.put("id", id);
            Json.send(resp, 200, ok);
        } catch (SQLException e) {
            Json.error(resp, 500, "Could not delete the food item.");
        }
    }

    /* ===============================================================
       IMAGE HELPERS  (unchanged behaviour from the original)
       =============================================================== */

    private String saveUploadedImage(HttpServletRequest req) throws IOException {
        String contentType = req.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
            return null;
        }

        Part part;
        try {
            part = req.getPart("image");
        } catch (ServletException e) {
            return null;
        }
        if (part == null || part.getSize() == 0) {
            return null;
        }

        if (part.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image is too large. Maximum size is 2 MB.");
        }

        String mime = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
        String extension = ALLOWED_IMAGE_TYPES.get(mime);
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported image type. Please upload a JPG, PNG, GIF or WEBP image.");
        }

        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;

        File dir = uploadDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File dest = new File(dir, filename);
        try (InputStream in = part.getInputStream()) {
            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return IMAGE_URL_PREFIX + filename;
    }

    private void deleteImageFile(String imageUrl) {
        try {
            if (imageUrl == null || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
                return;
            }
            String name = new File(imageUrl).getName();
            File file = new File(uploadDir(), name);
            if (file.getCanonicalPath().startsWith(uploadDir().getCanonicalPath()) && file.exists()) {
                file.delete();
            }
        } catch (IOException ignored) {
        }
    }

    /* ===============================================================
       SMALL HELPERS  (unchanged from the original)
       =============================================================== */

    private int parseId(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(pathInfo.replace("/", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Double parsePrice(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            double price = Double.parseDouble(raw.trim());
            return price > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseQuantity(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int quantity = Integer.parseInt(raw.trim());
            return quantity >= 0 ? quantity : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeCategory(String raw) {
        if (raw == null) {
            return null;
        }
        return VALID_CATEGORIES.get(raw.trim().toLowerCase());
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
