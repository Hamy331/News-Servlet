package com.gr6.news.filter;

import com.gr6.news.controller.NotificationController;
import com.gr6.news.model.Article;
import com.gr6.news.model.Category;
import com.gr6.news.model.Populate;
import com.gr6.news.model.User;
import com.gr6.news.view.GlobalLocals;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;

import java.io.IOException;
import java.time.Year;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;

/**
 * Gộp 3 middleware của bản Node:
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
        // Khi Tomcat chuyển sang trang lỗi 404, lấy lại đường dẫn gốc người dùng gõ
        Object errUri = req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (errUri != null) path = String.valueOf(errUri).substring(req.getContextPath().length());

        Map<String, Object> locals = GlobalLocals.of(req);
        populateNavbar(req, path, locals);
        sessionLocals(req, locals);
        loadNotifications(req, locals);
        chain.doFilter(request, response);
    }

    // ===== navMiddleware.js =====
    private void populateNavbar(HttpServletRequest req, String path, Map<String, Object> locals) {
        String[] segs = path.split("/", -1);
        String first = segs.length > 1 ? segs[1] : "";
        locals.put("navSection", first.isEmpty() ? "home" : first);
        String name = path.startsWith("/category") ? req.getParameter("name") : null;
        locals.put("currentCategory", name != null ? name : "");
        locals.put("currentYear", Year.now().getValue());
        locals.put("writerTab", path.matches("^/writer/(articles|edit).*") ? "articles" : path.startsWith("/writer") ? "compose" : "");

        try {
            List<Document> categories = Category.col().find().into(new ArrayList<>());
            // 1. Lấy tất cả bài báo đã xuất bản để móc nối Category và Tag
            List<Document> articles = Article.col().find(eq("status", "published")).into(new ArrayList<>());
            Populate.apply(articles, "category", "categories");
            Populate.apply(articles, "tags", "tags");

            // 2. Gom các Tag vào đúng Category của nó (lọc trùng, giữ thứ tự xuất hiện)
            Map<String, LinkedHashMap<String, Document>> catTagMap = new HashMap<>();
            for (Document a : articles) {
                Document cat = a.get("category", Document.class);
                List<?> tags = a.getList("tags", Object.class);
                if (cat == null || tags == null) continue;
                var map = catTagMap.computeIfAbsent(String.valueOf(cat.get("_id")), k -> new LinkedHashMap<>());
                for (Object t : tags) {
                    if (t instanceof Document tag) map.put(String.valueOf(tag.get("_id")), tag);
                }
            }

            // 3. Gắn mảng Tags (tối đa 5) vào từng Category
            List<Document> withTags = new ArrayList<>();
            for (Document cat : categories) {
                Document c = new Document(cat);
                var tagsMap = catTagMap.get(String.valueOf(cat.get("_id")));
                List<Document> tags = tagsMap == null ? new ArrayList<>() : new ArrayList<>(tagsMap.values());
                c.put("tags", tags.subList(0, Math.min(5, tags.size())));
                withTags.add(c);
            }

            // 4. Trả dữ liệu ra giao diện
            locals.put("navCategoriesLimited", withTags.subList(0, Math.min(6, withTags.size())));
            locals.put("navCategoriesAll", withTags);
        } catch (Exception e) {
            System.err.println("Lỗi khi load Navbar: " + e);
            locals.put("navCategoriesLimited", List.of());
            locals.put("navCategoriesAll", List.of());
        }
    }

    // ===== index.js: biến đăng nhập dùng ở mọi trang =====
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
        // Độc giả đã đăng nhập (được dùng tính năng lưu bài)
        locals.put("isReader", User.READERS.contains(role));
    }

    // ===== notifyMiddleware.js =====
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
