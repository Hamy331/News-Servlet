package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Thay cho routes/searchRoutes.js */
@WebServlet("/search")
public class SearchServlet extends BaseServlet {
    private static final int PAGE_SIZE = 10;

    // ---------------------------------------------------------------------------
    // Định tuyến và xử lý: GET /search
    // ---------------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String q = param(req, "q").trim();
        String query = q.length() > 100 ? q.substring(0, 100) : q;
        String sort = "views".equals(req.getParameter("sort")) ? "views" : "new";
        String catName = param(req, "cat").trim();
        int page;
        try {
            page = Math.max(1, Integer.parseInt(param(req, "page").trim()));
        } catch (NumberFormatException e) {
            page = 1;
        }
        final int requested = page;

        guard(() -> {
            List<Document> newest5Articles = ArticleController.getTop5NewestArticles();

            if (query.isEmpty()) {
                render(req, resp, "search", locals("title", "Tìm kiếm", "query", query,
                        "newest5Articles", newest5Articles, "articles", List.of(), "total", 0));
                return;
            }

            ArticleController.SearchResult r = ArticleController.search(query, catName, sort, requested, PAGE_SIZE);
            List<Document> facets = r.facets();
            int totalPages = r.totalPages();
            int current = r.currentPage();
            String activeName = r.activeCategory();
            for (Document f : facets) {
                f.put("url", withParam(query, activeName, sort, "cat", f.getString("name")));
                f.put("active", activeName != null && f.getString("name").equals(activeName));
            }

            List<Document> pages = new ArrayList<>();
            for (int i = 1; i <= totalPages; i++) {
                pages.add(new Document("n", i).append("url", link(query, activeName, sort, i)).append("active", i == current));
            }

            render(req, resp, "search", locals(
                    "title", "Tìm \"" + query + "\"",
                    "query", query,
                    "sort", sort,
                    "activeCat", activeName != null ? activeName : "",
                    "facets", facets,
                    "totalAll", r.totalAll(),
                    "total", r.total(),
                    "articles", r.articles(),
                    "newest5Articles", newest5Articles,
                    "pagination", totalPages > 1 ? locals("pages", pages,
                            "prev", current > 1 ? link(query, activeName, sort, current - 1) : null,
                            "next", current < totalPages ? link(query, activeName, sort, current + 1) : null) : null,
                    "urlNew", withParam(query, activeName, sort, "sort", null),
                    "urlViews", withParam(query, activeName, sort, "sort", "views"),
                    "urlAllCats", withParam(query, activeName, sort, "cat", null)));
        }, () -> sendText(resp, 500, "Error during search"));
    }

    // ---------------------------------------------------------------------------
    // Tạo link phân trang, lọc, sắp xếp
    // ---------------------------------------------------------------------------
    // Link phân trang giữ nguyên các tham số khác (giống URLSearchParams bên Node, giữ thứ tự tham số)
    private static LinkedHashMap<String, String> params(String query, String cat, String sort, int p) {
        LinkedHashMap<String, String> qs = new LinkedHashMap<>();
        qs.put("q", query);
        if (cat != null) qs.put("cat", cat);
        if ("views".equals(sort)) qs.put("sort", "views");
        if (p > 1) qs.put("page", String.valueOf(p));
        return qs;
    }

    private static String toUrl(Map<String, String> qs) {
        StringBuilder sb = new StringBuilder();
        for (var e : qs.entrySet()) {
            sb.append(sb.length() == 0 ? "?" : "&")
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return "/search" + sb;
    }

    private static String link(String query, String cat, String sort, int p) {
        return toUrl(params(query, cat, sort, p));
    }

    private static String withParam(String query, String cat, String sort, String key, String val) {
        LinkedHashMap<String, String> qs = params(query, cat, sort, 1);
        if (val == null) qs.remove(key);
        else qs.put(key, val); // set: giữ vị trí nếu đã có, thêm vào cuối nếu chưa
        return toUrl(qs);
    }
}
