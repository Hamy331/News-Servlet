package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.CategoryController;
import com.gr6.news.controller.TagController;
import com.gr6.news.controller.UserController;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;

import java.io.IOException;
import java.util.*;

@WebServlet(urlPatterns = {"/administrator", "/administrator/*"})
public class AdminServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (parts(req).length > 0) {
            render404(req, resp, null);
            return;
        }
        if (!isAuth(req, resp) || !verifyRole(req, resp, "administrator")) return;
        guard(() -> {
            List<Document> categories = CategoryController.getAllCategories();
            List<Document> tags = TagController.getAllTags();
            List<Document> pendingArticles = ArticleController.getPendingArticles();
            List<Document> users = UserController.getAllUsers();
            List<Document> editors = UserController.getEditorsOnly();
            List<Document> history = ArticleController.getReviewHistory(null, 30);
            long publishedCount = ArticleController.countByStatus("published", null);

            render(req, resp, "administrator", locals(
                    "layout", "admin_layout",
                    "title", "Admin Dashboard",
                    "allCategory", categories,
                    "allTags", tags,
                    "getPendingArticles", pendingArticles,
                    "getAllUsers", users,
                    "getEditorsOnly", editors,
                    "getAllCategories", categories,
                    "history", history,
                    "stats", locals(
                            "pending", pendingArticles.size(),
                            "published", publishedCount,
                            "users", users.size(),
                            "editors", editors.size(),
                            "categories", categories.size(),
                            "tags", tags.size()),
                    "flash", EditorServlet.reviewFlash(req.getParameter("msg"), false)));
        }, () -> sendText(resp, 500, "Internal Server Error"));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (!(p.length == 2 && p[0].equals("editor") && p[1].equals("addcategory"))) {
            render404(req, resp, null);
            return;
        }
        if (!isAuth(req, resp) || !verifyRole(req, resp, "administrator")) return;
        try {
            UserController.addAvailableCategory(req.getParameter("userid"), req.getParameter("catagoryid"));
            resp.sendRedirect("/administrator#editors");
        } catch (Exception e) {
            System.err.println("Lỗi khi gán danh mục cho editor: " + e);
            sendAlert(resp, "Lỗi: " + e.getMessage(), "/administrator");
        }
    }
}
