package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.UserController;
import com.gr6.news.model.Article;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.gr6.news.model.User;
import com.gr6.news.util.Passwords;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;

@WebServlet(urlPatterns = {"/user", "/user/*"})
public class UserServlet extends BaseServlet {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static Map<String, String> profileFlash(String msg) {
        if (msg == null) return null;
        return switch (msg) {
            case "updated" -> flash("success", "Đã cập nhật thông tin cá nhân.");
            case "password" -> flash("success", "Đã đổi mật khẩu thành công.");
            case "emailtaken" -> flash("danger", "Email này đã được tài khoản khác sử dụng.");
            case "invalid" -> flash("danger", "Vui lòng nhập đầy đủ họ tên và email hợp lệ.");
            case "wrongpass" -> flash("danger", "Mật khẩu hiện tại không đúng.");
            case "mismatch" -> flash("danger", "Mật khẩu xác nhận không khớp.");
            case "weakpass" -> flash("danger", "Mật khẩu mới phải có ít nhất 6 ký tự.");
            case "error" -> flash("danger", "Có lỗi xảy ra, vui lòng thử lại.");
            default -> null;
        };
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 1 && p[0].equals("profile")) {
            if (isAuth(req, resp)) profile(req, resp);
        } else if (p.length == 1 && p[0].equals("saved")) {
            if (isAuth(req, resp)) savedList(req, resp);
        } else {
            render404(req, resp, null);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 0) {
            if (isAuth(req, resp) && verifyRole(req, resp, "administrator")) createUser(req, resp);
        } else if (p.length == 2 && p[0].equals("delete")) {
            if (isAuth(req, resp) && verifyRole(req, resp, "administrator")) deleteUser(req, resp, p[1]);
        } else if (p.length == 1 && p[0].equals("profile")) {
            if (isAuth(req, resp)) updateProfile(req, resp);
        } else if (p.length == 1 && p[0].equals("password")) {
            if (isAuth(req, resp)) changePassword(req, resp);
        } else if (p.length == 2 && p[0].equals("saved")) {
            toggleSaved(req, resp, p[1]);
        } else {
            render404(req, resp, null);
        }
    }

    // Thêm mới User từ trang Administrator (khớp name="" trong form của administrator.hbs)
    private void createUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            UserController.createUser(req.getParameter("username"), req.getParameter("userPass"),
                    req.getParameter("userEmail"), req.getParameter("userFullName"), req.getParameter("userRole"));
            resp.sendRedirect("/administrator");
        } catch (Exception e) {
            System.err.println(e);
            sendAlert(resp, "Lỗi tạo user: " + e.getMessage(), "/administrator");
        }
    }

    // Xoá user (admin)
    private void deleteUser(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        try {
            UserController.deleteUser(id);
            resp.sendRedirect("/administrator");
        } catch (Exception e) {
            System.err.println(e);
            sendText(resp, 500, "Error deleting user");
        }
    }

    // ===================== HỒ SƠ CÁ NHÂN =====================
    private void profile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            ObjectId id = myId(req);
            Document user = User.col().find(eq("_id", id)).first();
            if (user == null) {
                resp.sendRedirect("/auth/login");
                return;
            }
            user.remove("password");
            Populate.apply(user, "avaiCategory", "categories", "name");

            boolean isStaff = User.isStaff(user);
            Map<String, Object> data = locals("profile", user, "isStaff", isStaff);
            String role = user.getString("role");

            if ("writer".equals(role)) {
                // Phóng viên: toàn bộ bài viết + thống kê theo trạng thái
                List<Document> articles = Article.col().find(eq("author", id)).sort(Sorts.descending("updatedAt"))
                        .into(new ArrayList<>());
                Populate.apply(articles, "category", "categories", "name");
                data.put("myArticles", articles);
                data.put("stats", locals(
                        "total", articles.size(),
                        "published", countStatus(articles, "published"),
                        "pending", countStatus(articles, "pending"),
                        "rejected", countStatus(articles, "rejected"),
                        "views", articles.stream().mapToLong(a -> a.get("views") instanceof Number n ? n.longValue() : 0).sum()));
            } else if ("editor".equals(role) || "administrator".equals(role)) {
                // Biên tập / Quản trị: thống kê duyệt bài + hoạt động gần đây
                long approved = ArticleController.countByStatus("published", id);
                long rejected = ArticleController.countByStatus("rejected", id);
                List<Document> recent = ArticleController.getReviewHistory(id, 6);
                data.put("stats", locals("approved", approved, "rejected", rejected,
                        "categories", user.getList("avaiCategory", Object.class, List.of()).size()));
                data.put("recentReviews", recent);
            } else {
                // Độc giả: số bài đã lưu và 3 bài lưu gần nhất
                List<Document> saved = savedArticles(id, "title image_url category", false);
                data.put("savedCount", saved.size());
                data.put("recentSaved", saved.subList(0, Math.min(3, saved.size())));
            }

            data.put("title", "Hồ sơ cá nhân");
            // Nhân sự toà soạn dùng giao diện Workspace, độc giả dùng giao diện trang báo
            data.put("layout", isStaff ? "admin_layout" : null);
            data.put("flash", profileFlash(req.getParameter("msg")));
            render(req, resp, "profile", data);
        }, () -> sendText(resp, 500, "Error loading profile"));
    }

    private static long countStatus(List<Document> list, String st) {
        return list.stream().filter(a -> st.equals(a.getString("status"))).count();
    }

    // [POST] /user/profile — cập nhật họ tên, email
    private void updateProfile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            ObjectId id = myId(req);
            String fullName = param(req, "full_name").trim();
            String email = param(req, "email").trim().toLowerCase();

            if (fullName.isEmpty() || !EMAIL.matcher(email).matches()) {
                resp.sendRedirect("/user/profile?msg=invalid#info");
                return;
            }
            if (User.col().countDocuments(and(eq("email", email), ne("_id", id))) > 0) {
                resp.sendRedirect("/user/profile?msg=emailtaken#info");
                return;
            }
            User.col().updateOne(eq("_id", id), Updates.combine(Updates.set("full_name", fullName), Updates.set("email", email)));

            // Đồng bộ lại session để thanh điều hướng hiển thị thông tin mới
            HttpSession session = req.getSession();
            Document u = new Document(authUser(req));
            u.put("full_name", fullName);
            u.put("email", email);
            session.setAttribute("authUser", u);
            resp.sendRedirect("/user/profile?msg=updated");
        }, () -> resp.sendRedirect("/user/profile?msg=error"));
    }

    // [POST] /user/password — đổi mật khẩu
    private void changePassword(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            String current = req.getParameter("current_password");
            String next = req.getParameter("new_password");
            String confirm = req.getParameter("confirm_password");
            if (next == null || next.length() < 6) {
                resp.sendRedirect("/user/profile?msg=weakpass#security");
                return;
            }
            if (!next.equals(confirm)) {
                resp.sendRedirect("/user/profile?msg=mismatch#security");
                return;
            }
            Document user = User.col().find(eq("_id", myId(req))).first();
            if (user == null || !Passwords.matches(current != null ? current : "", user.getString("password"))) {
                resp.sendRedirect("/user/profile?msg=wrongpass#security");
                return;
            }
            User.col().updateOne(eq("_id", user.get("_id")), Updates.set("password", Passwords.hash(next)));
            resp.sendRedirect("/user/profile?msg=password");
        }, () -> resp.sendRedirect("/user/profile?msg=error"));
    }

    // ===================== BÀI VIẾT ĐÃ LƯU (chỉ dành cho độc giả) =====================

    /** savedArticles đã populate bài (chỉ bài published), bỏ các mục có bài đã bị gỡ */
    private static List<Document> savedArticles(ObjectId userId, String select, boolean withAuthor) {
        Document me = User.col().find(eq("_id", userId)).first();
        if (me == null) return new ArrayList<>();
        List<Document> saved = new ArrayList<>(me.getList("savedArticles", Document.class, new ArrayList<>()));
        Populate.apply(List.of(me), "savedArticles.article", "articles", select, eq("status", "published"));
        saved = new ArrayList<>(me.getList("savedArticles", Document.class, new ArrayList<>()));
        List<Document> out = new ArrayList<>();
        for (Document s : saved) if (s.get("article") instanceof Document) out.add(s);
        List<Document> articles = out.stream().map(s -> s.get("article", Document.class)).toList();
        Populate.apply(new ArrayList<>(articles), "category", "categories", "name");
        if (withAuthor) Populate.apply(new ArrayList<>(articles), "author", "users", "username");
        return out;
    }

    // [POST] /user/saved/:id — lưu / bỏ lưu (gọi bằng fetch, trả JSON)
    private void toggleSaved(HttpServletRequest req, HttpServletResponse resp, String articleId) throws IOException {
        Document user = authUser(req);

        // Chưa đăng nhập → trình duyệt chuyển tới trang đăng nhập, sau đó quay lại bài viết
        if (!isAuthenticated(req) || user == null) {
            String next = enc("/details?id=" + articleId);
            json(resp, 401, Map.of("success", false, "redirect", "/auth/login?reason=save&next=" + next));
            return;
        }
        if (!User.isReader(user)) {
            json(resp, 403, Map.of("success", false, "message", "Tính năng lưu bài chỉ dành cho độc giả."));
            return;
        }

        guard(() -> {
            ObjectId aid = Ids.oid(articleId);
            if (aid == null) throw new IllegalArgumentException("Cast to ObjectId failed");
            Document article = Article.col().find(and(eq("_id", aid), eq("status", "published"))).first();
            if (article == null) {
                json(resp, 404, Map.of("success", false, "message", "Bài viết không tồn tại."));
                return;
            }
            Object uid = user.get("_id");
            boolean already = User.col().countDocuments(and(eq("_id", uid), eq("savedArticles.article", aid))) > 0;
            if (already) {
                User.col().updateOne(eq("_id", uid), Updates.pull("savedArticles", new Document("article", aid)));
            } else {
                // Thêm lên đầu danh sách để bài mới lưu hiển thị trước
                User.col().updateOne(eq("_id", uid), Updates.pushEach("savedArticles",
                        List.of(new Document("article", aid).append("savedAt", new Date())), new PushOptions().position(0)));
            }
            Document me = User.col().find(eq("_id", uid)).first();
            int count = me == null ? 0 : me.getList("savedArticles", Object.class, List.of()).size();
            json(resp, Map.of("success", true, "saved", !already, "count", count));
        }, () -> json(resp, 500, Map.of("success", false, "message", "Không thể lưu bài lúc này.")));
    }

    // [GET] /user/saved — danh sách bài đã lưu
    private void savedList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Document user = authUser(req);
        if (!User.isReader(user)) {
            resp.sendRedirect("/user/profile");
            return;
        }
        guard(() -> {
            List<Document> saved = savedArticles(user.getObjectId("_id"),
                    "title summary image_url category author publishedAt createdAt views", true);
            LinkedHashSet<String> categories = new LinkedHashSet<>();
            for (Document s : saved) {
                Document cat = s.get("article", Document.class).get("category", Document.class);
                if (cat != null && cat.getString("name") != null && !cat.getString("name").isEmpty()) categories.add(cat.getString("name"));
            }
            render(req, resp, "saved", locals("title", "Bài đã lưu", "saved", saved, "categories", new ArrayList<>(categories)));
        }, () -> sendText(resp, 500, "Error loading saved articles"));
    }
}
