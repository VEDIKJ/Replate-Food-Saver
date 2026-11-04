package com.replate.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/*
 * StaticCacheFilter stops the browser from serving a STALE copy of our HTML, CSS
 * and JavaScript.
 *
 * Why this exists:
 *   Tomcat's default static-file handler sends an ETag and Last-Modified header
 *   but NO Cache-Control header. Without Cache-Control, browsers are allowed to
 *   guess how long a file stays "fresh" (heuristic caching) and may keep using an
 *   old cached copy for hours without re-checking the server. During development
 *   that means an edited restaurant.js / customer.js can appear "not to work"
 *   because the browser is still running the previous version.
 *
 *   Setting "Cache-Control: no-cache" does NOT disable caching; it tells the
 *   browser it may keep the file but MUST revalidate with the server (a cheap
 *   304 Not Modified when unchanged) before using it. So the user always runs the
 *   current code, and unchanged files are still not re-downloaded.
 *
 * Mapped in Main to *.html, *.js and *.css. Uploaded images are intentionally
 * left cacheable (they never change once saved).
 */
public class StaticCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse) {
            HttpServletResponse resp = (HttpServletResponse) response;
            // "no-cache" = may store, but must revalidate before every use.
            resp.setHeader("Cache-Control", "no-cache, must-revalidate");
        }
        chain.doFilter(request, response);
    }
}
