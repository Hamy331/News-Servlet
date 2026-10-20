package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.CategoryController;
import com.gr6.news.model.Article;
import com.mongodb.client.model.Sorts;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;

import java.io.IOException;
import java.util.*;

import static com.mongodb.client.model.Filters.*;

@WebServlet(urlPatterns = {"/category", "/category/*"})
public class CategoryServlet extends BaseServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 0) {
            // Tạo category mới (chỉ admin)
            if (!isAuth(req, resp) || !verifyRole(req, resp, "administrator")) return;
            guard(() -> {
                CategoryController.createCategory(req.getParameter("catagoryName"), req.getParameter("catagoryDes"));
                resp.sendRedirect("/administrator");
            }, () -> sendText(resp, 500, "Error creating category"));
        } else if (p.length == 2 && p[0].equals("delete")) {
            // Xoá category
            if (!isAuth(req, resp) || !verifyRole(req, resp, "administrator")) return;
            guard(() -> {
                CategoryController.deleteCategory(p[1]);
                resp.sendRedirect("/administrator");
            }, () -> sendText(resp, 500, "Error deleting category"));
        } else {
            render404(req, resp, null);
        }
    }

    // Xem bài viết theo category
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (parts(req).length > 0) {
            render404(req, resp, null);
            return;
        }
        guard(() -> {
            String name = param(req, "name");
            Document category = CategoryController.getCategoryByName(name);

            // 1. Người dùng gõ bậy tên Category trên URL
            if (category == null) {
                render(req, resp, "404_article", locals("message",
                        "Rất tiếc, chuyên mục \"" + name + "\" không tồn tại trên hệ thống."));
                return;
            }

            // 2. Danh sách bài báo của Category đó
            List<Document> articles = Article.col()
                    .find(and(eq("category", category.get("_id")), eq("status", "published"))).into(new ArrayList<>());

            // 3. Kiểm tra rỗng trước khi render list
            if (articles.isEmpty()) {
                render(req, resp, "404_article", locals("message", "Rất tiếc, chuyên mục \"" + category.getString("name")
                        + "\" hiện tại chưa có bài báo nào được xuất bản. Vui lòng quay lại sau!"));
                return;
            }

            // 4. Các phần phụ
            List<Document> top4Articles = Article.col()
                    .find(and(eq("category", category.get("_id")), eq("status", "published")))
                    .sort(Sorts.descending("views")).limit(4).into(new ArrayList<>());
            List<Document> newest5Articles = ArticleController.getTop5NewestArticles();

            render(req, resp, "list", locals(
                    "article1", top4Articles.size() > 0 ? top4Articles.get(0) : null,
                    "article2", top4Articles.size() > 1 ? top4Articles.get(1) : null,
                    "article3", top4Articles.size() > 2 ? top4Articles.get(2) : null,
                    "article4", top4Articles.size() > 3 ? top4Articles.get(3) : null,
                    "CategoryName", category.get("name"),
                    "des", category.get("description"),
                    "article", articles,
                    "newest5Articles", newest5Articles));
        }, () -> sendText(resp, 500, "Error loading category"));
    }
}
