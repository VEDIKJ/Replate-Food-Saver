package com.replate;

import com.replate.config.DatabaseConnection;
import com.replate.filter.AuthenticationFilter;
import com.replate.filter.StaticCacheFilter;
import com.replate.servlet.AuthServlet;
import com.replate.servlet.FoodItemServlet;
import com.replate.servlet.OrderServlet;
import com.replate.servlet.OrderStatusServlet;
import com.replate.servlet.PickupServlet;
import jakarta.servlet.MultipartConfigElement;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;

/*
 * Main is the program that STARTS the whole website.
 *
 * It launches an embedded Apache Tomcat web server. "Embedded" means Tomcat
 * runs from inside our own program, so you do NOT need to install Tomcat
 * separately - you only need Java. This keeps running the project simple.
 *
 * What this class sets up:
 *   1. A web server listening on http://localhost:8080
 *   2. Static files (index.html, style.css, images, the dashboards...) served from the project folder
 *   3. Our four API servlets that the frontend talks to
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // Default port is 8080. You can optionally run on another port with
        //   java -Dreplate.port=8099 ... com.replate.Main
        // (handy when 8080 is already in use). Normal use needs no change.
        int port = Integer.getInteger("replate.port", 8080);

        // Give a clear message when another program is already using this port.
        // Without this check Tomcat logs a long stack trace and then the program
        // can still print that Replate started, which is confusing for beginners.
        try (ServerSocket ignored = new ServerSocket(port)) {
            // The port is free. Tomcat will use it a moment later.
        } catch (IOException e) {
            System.err.println("Port " + port + " is already in use.");
            System.err.println("A previous Replate server may still be running. Run stop.bat to stop it,");
            System.err.println("then run run.bat again. (Or start on another port with -Dreplate.port=8099.)");
            return;
        }

        // --- Check the database BEFORE starting the web server ---
        // Replate stores everything in MySQL. If the database is unreachable we
        // stop here with a clear message instead of starting up and failing later
        // (the app must NEVER silently fall back to in-memory storage).
        try {
            DatabaseConnection.verifyConnectionOrThrow();
            System.out.println("  Connected to MySQL database 'replate_db'.");
        } catch (RuntimeException dbError) {
            System.err.println("Could not start Replate - database connection failed.\n");
            System.err.println(dbError.getMessage());
            return;
        }

        // The project folder is the folder you run this program from.
        // It contains index.html, so Tomcat can serve the website from here.
        String projectDir = System.getProperty("user.dir");

        // Make sure the folder for uploaded food images exists. Because it lives
        // inside the project folder (which Tomcat serves as static files), every
        // saved image is reachable in the browser at /uploads/food/<name>.
        File uploadsDir = new File(projectDir, "uploads/food");
        if (!uploadsDir.exists()) {
            uploadsDir.mkdirs();
        }

        // --- Create the Tomcat server ---
        Tomcat tomcat = new Tomcat();
        // Tomcat needs a scratch folder for temporary files.
        tomcat.setBaseDir(new File(System.getProperty("java.io.tmpdir"), "replate-tomcat").getAbsolutePath());
        tomcat.setPort(port);
        tomcat.getConnector(); // create the default HTTP connector on the port above

        // The web app lives at the root path "". Its files come from projectDir.
        Context ctx = tomcat.addContext("", projectDir);
        // We register our servlets manually (below), so we tell Tomcat not to scan
        // the classpath for annotations. This also avoids needing extra library jars.
        ctx.setIgnoreAnnotations(true);

        // Tell Tomcat the correct type for common files so the browser reads them properly.
        ctx.addMimeMapping("html", "text/html");
        ctx.addMimeMapping("css", "text/css");
        ctx.addMimeMapping("js", "application/javascript");
        ctx.addMimeMapping("json", "application/json");
        ctx.addMimeMapping("png", "image/png");
        ctx.addMimeMapping("jpg", "image/jpeg");
        ctx.addMimeMapping("jpeg", "image/jpeg");
        ctx.addMimeMapping("svg", "image/svg+xml");
        ctx.addMimeMapping("ico", "image/x-icon");

        // --- Static files (the DefaultServlet serves index.html, css, images, etc.) ---
        Wrapper defaultServlet = Tomcat.addServlet(ctx, "default",
                "org.apache.catalina.servlets.DefaultServlet");
        defaultServlet.addInitParameter("listings", "false");
        defaultServlet.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/", "default");
        ctx.addWelcomeFile("index.html"); // open index.html when visiting "/"

        // --- Our API servlets (the backend the frontend calls with fetch) ---
        // Each servlet is given a name and connected to a URL path.

        // The food servlet accepts image uploads, so it needs a "multipart"
        // configuration (where big uploads are buffered and the size limits).
        // We set it here in code because this project turns OFF annotation
        // scanning, so a @MultipartConfig annotation would be ignored.
        Wrapper foodServlet = Tomcat.addServlet(ctx, "foodServlet", new FoodItemServlet());
        // Temp folder Tomcat uses while receiving an upload. It MUST exist, or
        // Tomcat silently skips multipart parsing (form fields come back empty).
        File uploadTemp = new File(System.getProperty("java.io.tmpdir"), "replate-uploads");
        if (!uploadTemp.exists()) {
            uploadTemp.mkdirs();
        }
        String uploadTempDir = uploadTemp.getAbsolutePath();
        foodServlet.setMultipartConfigElement(new MultipartConfigElement(
                uploadTempDir,       // temp location while a file is being received
                2L * 1024 * 1024,    // max single file size: 2 MB
                3L * 1024 * 1024,    // max whole request size: 3 MB
                0));                 // write straight to disk (no in-memory threshold)
        // /api/food handles add + list; /api/food/{id} handles update + delete.
        ctx.addServletMappingDecoded("/api/food", "foodServlet");
        ctx.addServletMappingDecoded("/api/food/*", "foodServlet");

        Tomcat.addServlet(ctx, "orderServlet", new OrderServlet());
        ctx.addServletMappingDecoded("/api/orders", "orderServlet");

        Tomcat.addServlet(ctx, "orderStatusServlet", new OrderStatusServlet());
        ctx.addServletMappingDecoded("/api/orders/status", "orderStatusServlet");

        Tomcat.addServlet(ctx, "pickupServlet", new PickupServlet());
        ctx.addServletMappingDecoded("/api/pickup/verify", "pickupServlet");

        // The auth servlet handles registration, login, "who am I" and logout.
        Tomcat.addServlet(ctx, "authServlet", new AuthServlet());
        ctx.addServletMappingDecoded("/api/auth/*", "authServlet");

        // --- Security gate (runs before the servlets above) ---
        // The filter checks the session and role for protected URLs, and keeps
        // people from opening a dashboard by typing its URL without logging in.
        FilterDef authFilterDef = new FilterDef();
        authFilterDef.setFilterName("authFilter");
        authFilterDef.setFilter(new AuthenticationFilter());
        ctx.addFilterDef(authFilterDef);

        FilterMap authFilterMap = new FilterMap();
        authFilterMap.setFilterName("authFilter");
        authFilterMap.addURLPattern("/api/*");
        authFilterMap.addURLPattern("/customer.html");
        authFilterMap.addURLPattern("/restaurant.html");
        ctx.addFilterMap(authFilterMap);

        // --- Stop stale HTML/CSS/JS (force the browser to revalidate) ---
        // Without this the browser can keep running an OLD cached restaurant.js /
        // customer.js and an edited page looks "broken" until a hard refresh.
        FilterDef cacheFilterDef = new FilterDef();
        cacheFilterDef.setFilterName("staticCacheFilter");
        cacheFilterDef.setFilter(new StaticCacheFilter());
        ctx.addFilterDef(cacheFilterDef);

        FilterMap cacheFilterMap = new FilterMap();
        cacheFilterMap.setFilterName("staticCacheFilter");
        cacheFilterMap.addURLPattern("*.html");
        cacheFilterMap.addURLPattern("*.js");
        cacheFilterMap.addURLPattern("*.css");
        ctx.addFilterMap(cacheFilterMap);

        // --- Start the server ---
        try {
            tomcat.start();
        } catch (Exception startError) {
            System.err.println("Could not start the web server on port " + port + ".");
            System.err.println("The port may be in use. Close the other Replate window, or run with "
                    + "-Dreplate.port=8099.");
            System.err.println("  Underlying error: " + startError.getMessage());
            return;
        }

        System.out.println("  Replate (Food Saver) is running!");
        System.out.println("  Open your browser at:  http://localhost:" + port);
        System.out.println("  Press Ctrl + C in this window to stop the server.");
   
        tomcat.getServer().await(); // keep the server running until you stop it
    }
}
