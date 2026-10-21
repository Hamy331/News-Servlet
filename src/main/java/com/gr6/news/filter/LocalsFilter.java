package com.gr6.news.filter;

import com.gr6.news.controller.CategoryController;
import com.gr6.news.controller.NotificationController;
import com.gr6.news.model.User;
import com.gr6.news.view.GlobalLocals;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;

import java.io.IOException;
import java.time.Year;
import java.util.*;

/**
 * 3 middleware:
 *  - navMiddleware.js   (populateNavbar: mục đang xem + chuyên mục kèm tag trên thanh điều hướng)
 *  - index.js           (res.locals.auth / authUser / isAdmin / isWriter / isEditor / isReader)
 *  - notifyMiddleware.js (loadNotifications: chuông thông báo cho nhân sự toà soạn)
 */
public class LocalsFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")) {
            chain.doFilter(request, response);
            return;
        }
        Object errUri = req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (errUri != null) path = String.valueOf(errUri).substring(req.getContextPath().length());

        Map<String, Object> locals = GlobalLocals.of(req);
        populateNavbar(req, path, locals);
        sessionLocals(req, locals);
        loadNotifications(req, locals);
        chain.doFilter(request, response);
    }

    private void populateNavbar(HttpServletRequest req, String path, Map<String, Object> locals) {
        String[] segs = path.split("/", -1);
        String first = segs.length > 1 ? segs[1] : "";
        locals.put("navSection", first.isEmpty() ? "home" : first);
        String name = path.startsWith("/category") ? req.getParameter("name") : null;
        locals.put("currentCategory", name != null ? name : "");
        locals.put("currentYear", Year.now().getValue());
        locals.put("writerTab", path.matches("^/writer/(articles|edit).*") ? "articles" : path.startsWith("/writer") ? "compose" : "");

        try {
            List<Document> withTags = CategoryController.getNavCategoriesWithTags();9
            locals.put("navCategoriesLimited", withTags.subList(0, Math.min(6, withTags.size())));
            locals.put("navCategoriesAll", withTags);
        } catch (Exception e) {
            System.err.println("Lỗi khi load Navbar: " + e);
            locals.put("navCategoriesLimited", List.of());
            locals.put("navCategoriesAll", List.of());
        }
    }

    private void sessionLocals(HttpServletRequest req, Map<String, Object> locals) {
        HttpSession session = req.getSession(false);
        Object auth = session == null ? null : session.getAttribute("auth");
        Document authUser = session == null ? null : (Document) session.getAttribute("authUser");
        String role = authUser == null ? null : authUser.getString("role");

        locals.put("auth", Boolean.TRUE.equals(auth));
        locals.put("authUser", authUser);
        locals.put("isAdmin", "administrator".equals(role));
        locals.put("isWriter", "writer".equals(role));
        locals.put("isEditor", "editor".equals(role));
        locals.put("isReader", User.READERS.contains(role));
    }

    private void loadNotifications(HttpServletRequest req, Map<String, Object> locals) {
        locals.put("notifications", List.of());
        locals.put("notifUnread", 0);
        HttpSession session = req.getSession(false);
        Document user = session == null ? null : (Document) session.getAttribute("authUser");
        // Bỏ qua yêu cầu không phải trang (POST, file tĩnh, API)
        if (user == null || !User.isStaff(user) || !"GET".equals(req.getMethod())) return;
        try {
            locals.put("notifications", NotificationController.getLatest(user.get("_id"), 8));
            locals.put("notifUnread", NotificationController.countUnread(user.get("_id")));
        } catch (Exception e) {
            System.err.println("Lỗi tải thông báo: " + e);
        }
    }
}
