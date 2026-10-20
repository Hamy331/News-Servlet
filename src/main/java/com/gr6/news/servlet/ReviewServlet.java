package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.NotificationController;
import com.gr6.news.model.Article;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.gr6.news.model.User;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;

/** Thay cho routes/articleRoutes.js: duyệt / từ chối bài (Editor & Admin) và lịch sử bài viết */
@WebServlet("/article/*")
public class ReviewServlet extends BaseServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 1 && p[0].equals("approve")) approve(req, resp);
        else if (p.length == 1 && p[0].equals("reject")) reject(req, resp);
        else render404(req, resp, null);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 2 && p[0].equals("history")) history(req, resp, p[1]);
        else render404(req, resp, null);
    }

    private record Check(boolean ok, int code, String msg, Document user) {}

    // Editor chỉ được xử lý bài thuộc chuyên mục Admin đã phân công; Admin xử lý được mọi bài.
    private static Check checkReviewPermission(HttpServletRequest req, String articleId) {
        Document user = authUser(req);
        if (user == null || !java.util.Arrays.asList("editor", "administrator").contains(user.getString("role"))) {
            return new Check(false, 403, null, null);
        }
        ObjectId id = Ids.oid(articleId);
        if (id == null) throw new IllegalArgumentException("Cast to ObjectId failed");
        Document article = Article.col().find(eq("_id", id)).first();
        Document r = article == null ? null : article.get("revision", Document.class);
        boolean isNew = article != null && "pending".equals(article.getString("status"));
        boolean isRevision = article != null && "published".equals(article.getString("status"))
                && r != null && "pending".equals(r.getString("status"));
        if (article == null || (!isNew && !isRevision)) return new Check(false, 0, "notfound", null);

        if ("editor".equals(user.getString("role"))) {
            // Bản sửa có thể đổi chuyên mục → kiểm tra theo chuyên mục của bản đang chờ duyệt
            Object cat = isRevision ? (r.get("category") != null ? r.get("category") : article.get("category")) : article.get("category");
            Document me = User.col().find(eq("_id", user.get("_id"))).first();
            List<Object> cats = me == null ? List.of() : me.getList("avaiCategory", Object.class, List.of());
            boolean allowed = cats.stream().anyMatch(c -> Ids.same(c, cat));
            if (!allowed) return new Check(false, 0, "forbidden", null);
        }
        return new Check(true, 0, null, user);
    }

    private static String backTo(HttpServletRequest req, String msg) {
        Document u = authUser(req);
        String base = u != null && "administrator".equals(u.getString("role")) ? "/administrator" : "/editor";
        return base + "?msg=" + msg + "#review";
    }

    private void approve(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            Check check = checkReviewPermission(req, req.getParameter("articleId"));
            if (check.code() == 403) {
                sendText(resp, 403, "Forbidden");
                return;
            }
            if (!check.ok()) {
                resp.sendRedirect(backTo(req, check.msg()));
                return;
            }
            String type = Article.TYPES.contains(req.getParameter("articleType")) ? req.getParameter("articleType") : "normal";
            Map<String, Object> result = ArticleController.publishArticle(req.getParameter("articleId"),
                    check.user().getObjectId("_id"), type);
            if (result == null) {
                resp.sendRedirect(backTo(req, "notfound"));
                return;
            }
            Document a = (Document) result.get("article");
            boolean revision = "revision".equals(result.get("kind"));
            NotificationController.notifyUser(a.get("author"), check.user().get("_id"), a,
                    revision ? "revision_approved" : "approved",
                    revision ? "Bản sửa của bài “" + a.getString("title") + "” đã được duyệt và thay thế bản cũ."
                            : "Bài “" + a.getString("title") + "” đã được duyệt và xuất bản.",
                    "/details?id=" + a.get("_id"));
            resp.sendRedirect(backTo(req, revision ? "revapproved" : "approved"));
        }, () -> resp.sendRedirect(backTo(req, "notfound")));
    }

    private void reject(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            Check check = checkReviewPermission(req, req.getParameter("articleIdReject"));
            if (check.code() == 403) {
                sendText(resp, 403, "Forbidden");
                return;
            }
            if (!check.ok()) {
                resp.sendRedirect(backTo(req, check.msg()));
                return;
            }
            String note = param(req, "rejectNote").trim();
            if (note.isEmpty()) note = "Nội dung chưa đạt yêu cầu.";
            Map<String, Object> result = ArticleController.rejectArticle(req.getParameter("articleIdReject"), note,
                    check.user().getObjectId("_id"));
            if (result == null) {
                resp.sendRedirect(backTo(req, "notfound"));
                return;
            }
            Document a = (Document) result.get("article");
            boolean revision = "revision".equals(result.get("kind"));
            Document r = a.get("revision", Document.class);
            String title = revision && r != null && r.getString("title") != null && !r.getString("title").isEmpty()
                    ? r.getString("title") : a.getString("title");
            NotificationController.notifyUser(a.get("author"), check.user().get("_id"), a,
                    revision ? "revision_rejected" : "rejected",
                    revision ? "Bản sửa của bài “" + title + "” chưa được duyệt — bài đang đăng giữ nguyên. Ghi chú: " + note
                            : "Bài “" + title + "” bị từ chối. Ghi chú: " + note,
                    "/writer/edit/" + a.get("_id"));
            resp.sendRedirect(backTo(req, revision ? "revrejected" : "rejected"));
        }, () -> resp.sendRedirect(backTo(req, "notfound")));
    }

    // ===== LỊCH SỬ CHỈNH SỬA / DUYỆT CỦA MỘT BÀI =====
    // Xem được: tác giả, Admin, Editor phụ trách chuyên mục của bài
    private void history(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document user = authUser(req);
        if (user == null) {
            resp.sendRedirect("/auth/login?next=" + enc(originalUrl(req)));
            return;
        }
        ObjectId oid = Ids.oid(id);
        Document article = oid == null ? null : Article.col().find(eq("_id", oid)).first();
        if (article == null) {
            render404(req, resp, "Không tìm thấy bài viết.");
            return;
        }
        Populate.apply(article, "category", "categories", "name");
        Populate.apply(article, "author", "users", "username full_name");
        Populate.apply(article, "history.by", "users", "username role");
        Populate.apply(article, "revision.category", "categories", "name");

        Document author = article.get("author", Document.class);
        boolean isAuthor = author != null && Ids.same(author.get("_id"), user.get("_id"));
        boolean allowed = "administrator".equals(user.getString("role")) || isAuthor;
        if (!allowed && "editor".equals(user.getString("role"))) {
            Document me = User.col().find(eq("_id", user.get("_id"))).first();
            Set<String> cats = new HashSet<>();
            if (me != null) for (Object c : me.getList("avaiCategory", Object.class, List.of())) cats.add(Ids.idOf(c));
            Document cat = article.get("category", Document.class);
            Document r = article.get("revision", Document.class);
            Document rcat = r == null ? null : r.get("category", Document.class);
            allowed = (cat != null && cats.contains(Ids.idOf(cat))) || (rcat != null && cats.contains(Ids.idOf(rcat)));
        }
        if (!allowed) {
            render404(req, resp, "Bạn không có quyền xem lịch sử của bài viết này.");
            return;
        }

        render(req, resp, "articleHistory", locals(
                "layout", "admin_layout",
                "title", "Lịch sử bài viết",
                "article", article,
                "history", ArticleController.historyForView(article),
                "isAuthor", isAuthor));
    }
}
