package com.gr6.news.servlet;

import com.google.gson.JsonObject;
import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.CategoryController;
import com.gr6.news.controller.CommentController;
import com.gr6.news.controller.UserController;
import com.gr6.news.model.Ids;
import com.gr6.news.model.User;
import com.gr6.news.util.Youtube;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;

/** Thay cho routes/homeRoutes.js: trang chủ, giới thiệu, chi tiết bài, thả tim, bình luận */
@WebServlet(urlPatterns = {"", "/about", "/details", "/details/*"})
public class HomeServlet extends BaseServlet {
    // ---------------------------------------------------------------------------
    // Định tuyến
    // ---------------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        switch (req.getServletPath()) {
            case "", "/" -> home(req, resp);
            case "/about" -> about(req, resp);
            case "/details" -> {
                if (req.getPathInfo() == null || req.getPathInfo().equals("/")) details(req, resp);
                else render404(req, resp, null);
            }
            default -> render404(req, resp, null);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = "/details".equals(req.getServletPath()) ? String.valueOf(req.getPathInfo()) : "";
        switch (sub) {
            case "/like" -> like(req, resp);
            case "/comment" -> comment(req, resp);
            default -> render404(req, resp, null);
        }
    }

    // ---------------------------------------------------------------------------
    // Trang chủ, giới thiệu, chi tiết bài
    // ---------------------------------------------------------------------------
    // Trang chủ
    private void home(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            List<Document> newestArticles = ArticleController.getTop10NewestArticles();
            List<Document> latestArticlesFromCategories = ArticleController.getLatestArticleFromEachCategory();
            List<Document> newest5Articles = ArticleController.getTop5NewestArticles();
            List<Document> topViewedArticles = ArticleController.getTop10MostViewedArticles();
            List<Document> articles = topViewedArticles.subList(0, Math.min(3, topViewedArticles.size()));

            render(req, resp, "home", locals(
                    "article1", articles.size() > 0 ? articles.get(0) : null,
                    "article2", articles.size() > 1 ? articles.get(1) : null,
                    "article3", articles.size() > 2 ? articles.get(2) : null,
                    "topViewedArticles", topViewedArticles,
                    "newestArticles", newestArticles,
                    "latestArticlesFromCategories", latestArticlesFromCategories,
                    "newest5Articles", newest5Articles));
        }, () -> sendText(resp, 500, "Internal Server Error"));
    }

    private void about(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            // Số liệu thật của toà soạn hiển thị trên trang Về chúng tôi
            long articles = ArticleController.countPublished();
            long categories = CategoryController.countCategories();
            long writers = UserController.countByRoles("writer");
            long editors = UserController.countByRoles("editor", "administrator");
            render(req, resp, "about", locals("title", "Về chúng tôi",
                    "stats", locals("articles", articles, "categories", categories, "writers", writers, "editors", editors)));
        }, () -> render(req, resp, "about", locals("title", "Về chúng tôi")));
    }

    private void details(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            String id = req.getParameter("id");
            if (id == null || id.isEmpty()) {
                resp.sendRedirect("/");
                return;
            }
            Document data = ArticleController.getArticleById(id);
            if (data == null) {
                render404(req, resp, "Bài viết này không tồn tại hoặc đã bị gỡ khỏi trang.");
                return;
            }
            ObjectId oid = data.getObjectId("_id");
            ArticleController.incrementView(oid);

            List<Document> comments = CommentController.getCommentsByArticle(oid);
            Object authorId = Ids.oid(Ids.idOf(data.get("author")));
            Object categoryId = Ids.oid(Ids.idOf(data.get("category")));
            List<Document> adsArticles = ArticleController.getAdsArticles(2);
            List<Document> authorArticles = ArticleController.getMoreByAuthor(authorId, oid, 4);
            List<Document> hotArticles = ArticleController.getHotArticles(oid, 4);
            List<Document> relatedCategoryArticles = ArticleController.getRelatedByCategory(categoryId, oid, 4);
            List<Document> recommendedArticles = ArticleController.getRecommended(oid, 6);

            // Độc giả đã đăng nhập: bài này đã nằm trong danh sách lưu chưa?
            Document authUser = authUser(req);
            boolean isSaved = User.isReader(authUser) && UserController.hasSaved(authUser.get("_id"), oid);

            render(req, resp, "details", locals(
                    "article", data,
                    "video_url", Youtube.toEmbedUrls(data.getList("video_url", Object.class)),
                    "comments", comments,
                    "adsArticles", adsArticles,
                    "authorArticles", authorArticles,
                    "hotArticles", hotArticles,
                    "relatedCategoryArticles", relatedCategoryArticles,
                    "recommendedArticles", recommendedArticles,
                    "isSaved", isSaved));
        }, () -> sendText(resp, 500, "Lỗi hệ thống"));
    }

    // ---------------------------------------------------------------------------
    // Tương tác của độc giả: thả tim, bình luận
    // ---------------------------------------------------------------------------
    // API: XỬ LÝ THẢ TIM (AJAX)
    private void like(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            // Id sai → controller báo lỗi, guard trả "Lỗi server."
            ArticleController.LikeResult r = ArticleController.likeArticle(jsonString(jsonBody(req), "id"), authUser(req));
            switch (r.status()) {
                case "ok" -> json(resp, Map.of("success", true, "newLikes", r.likes()));
                case "notfound" -> json(resp, Map.of("success", false, "message", "Không tìm thấy bài viết"));
                case "own" -> json(resp, Map.of("success", false, "message", "Tác giả không được phép tự thả tim cho bài viết của mình."));
                default -> json(resp, Map.of("success", false, "message", "Lỗi cập nhật lượt tim."));
            }
        }, () -> json(resp, Map.of("success", false, "message", "Lỗi server.")));
    }

    // API: XỬ LÝ GỬI BÌNH LUẬN (AJAX)
    private void comment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            JsonObject body = jsonBody(req);
            String articleId = jsonString(body, "articleId");
            String name = jsonString(body, "name");
            String content = jsonString(body, "content");
            if (articleId == null || articleId.isEmpty() || name == null || name.isEmpty()
                    || content == null || content.isEmpty()) {
                json(resp, Map.of("success", false, "message", "Vui lòng nhập đủ thông tin."));
                return;
            }
            Document c = CommentController.addComment(articleId, name, content);
            if (c != null) json(resp, Map.of("success", true));
            else json(resp, Map.of("success", false, "message", "Không thể lưu bình luận vào Database."));
        }, () -> json(resp, Map.of("success", false, "message", "Lỗi server.")));
    }
}
