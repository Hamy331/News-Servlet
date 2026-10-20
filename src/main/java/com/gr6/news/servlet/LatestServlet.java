package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;

import java.io.IOException;
import java.util.*;

/** Thay cho routes/latestRoutes.js: danh sách các bài viết mới nhất */
@WebServlet("/latest")
public class LatestServlet extends BaseServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            List<Document> newest5Articles = ArticleController.getTop5NewestArticles();
            List<Document> newestArticles = ArticleController.getTop10NewestArticles();
            List<Document> top4 = newestArticles.subList(0, Math.min(4, newestArticles.size()));
            List<Document> remaining = newestArticles.subList(Math.min(4, newestArticles.size()), newestArticles.size());

            render(req, resp, "list", locals(
                    "article1", top4.size() > 0 ? top4.get(0) : null,
                    "article2", top4.size() > 1 ? top4.get(1) : null,
                    "article3", top4.size() > 2 ? top4.get(2) : null,
                    "article4", top4.size() > 3 ? top4.get(3) : null,
                    "CategoryName", "Latest Articles",
                    "des", "Browse the latest published articles",
                    "article", remaining,
                    "newest5Articles", newest5Articles));
        }, () -> sendText(resp, 500, "Error loading latest articles"));
    }
}
