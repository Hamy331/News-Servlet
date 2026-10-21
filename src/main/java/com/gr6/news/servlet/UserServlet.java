package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.UserController;
import com.gr6.news.model.User;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;

@WebServlet(urlPatterns = {"/user", "/user/*"})
public class UserServlet extends BaseServlet {
    // ---------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------
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

    // ---------------------------------------------------------------------------
    // Định tuyến
    // ---------------------------------------------------------------------------
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

    // ---------------------------------------------------------------------------
    // Quản lý thành viên (admin): POST /user, /user/delete/{id}
    // ---------------------------------------------------------------------------
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

    // ---------------------------------------------------------------------------
    // Hồ sơ cá nhân: /user/profile, /user/password
    // ---------------------------------------------------------------------------
    private void profile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            ObjectId id = myId(req);
            Document user = UserController.getProfile(id);
            if (user == null) {
                resp.sendRedirect("/auth/login");
                return;
            }

            boolean isStaff = User.isStaff(user);
            Map<String, Object> data = locals("profile", user, "isStaff", isStaff);
            String role = user.getString("role");

            if ("writer".equals(role)) {
                // Phóng viên: toàn bộ bài viết + thống kê theo trạng thái
                List<Document> articles = ArticleController.getArticlesByAuthor(id);
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
                List<Document> saved = UserController.getSavedArticles(id, "title image_url category", false);
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

            String outcome = UserController.updateProfile(id, fullName, email);
            if (!outcome.equals("updated")) {
                resp.sendRedirect("/user/profile?msg=" + outcome + "#info");
                return;
            }

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
            String outcome = UserController.changePassword(myId(req), current, next, confirm);
            resp.sendRedirect("/user/profile?msg=" + outcome + (outcome.equals("password") ? "" : "#security"));
        }, () -> resp.sendRedirect("/user/profile?msg=error"));
    }

    // ---------------------------------------------------------------------------
    // Bài viết đã lưu: /user/saved
    // ---------------------------------------------------------------------------
    // [GET] /user/saved — danh sách bài đã lưu
    private void savedList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Document user = authUser(req);
        if (!User.isReader(user)) {
            resp.sendRedirect("/user/profile");
            return;
        }
        guard(() -> {
            List<Document> saved = UserController.getSavedArticles(user.getObjectId("_id"),
                    "title summary image_url category author publishedAt createdAt views", true);
            LinkedHashSet<String> categories = new LinkedHashSet<>();
            for (Document s : saved) {
                Document cat = s.get("article", Document.class).get("category", Document.class);
                if (cat != null && cat.getString("name") != null && !cat.getString("name").isEmpty()) categories.add(cat.getString("name"));
            }
            render(req, resp, "saved", locals("title", "Bài đã lưu", "saved", saved, "categories", new ArrayList<>(categories)));
        }, () -> sendText(resp, 500, "Error loading saved articles"));
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
            UserController.SaveResult r = UserController.toggleSavedArticle(user.get("_id"), articleId);
            if (!r.found()) {
                json(resp, 404, Map.of("success", false, "message", "Bài viết không tồn tại."));
                return;
            }
            json(resp, Map.of("success", true, "saved", r.saved(), "count", r.count()));
        }, () -> json(resp, 500, Map.of("success", false, "message", "Không thể lưu bài lúc này.")));
    }
}
