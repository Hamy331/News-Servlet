package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.model.Article;
import com.gr6.news.model.Category;
import com.gr6.news.model.Populate;
import com.gr6.news.model.Tag;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.mongodb.client.model.Filters.*;

/** Thay cho routes/searchRoutes.js */
@WebServlet("/search")
public class SearchServlet extends BaseServlet {
    private static final int PAGE_SIZE = 10;

    // Thoát ký tự đặc biệt để người dùng gõ "c++" hay "(abc" không làm lỗi RegExp
    private static String escapeRegex(String s) {
        return s.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
    }

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

            // Khớp tiêu đề, sapo, nội dung hoặc tên thẻ
            String rx = escapeRegex(query);
            List<Object> tagIds = new ArrayList<>();
            for (Document t : Tag.col().find(regex("name", rx, "i")).projection(Projections.include("_id"))) {
                tagIds.add(t.get("_id"));
            }
            List<Bson> ors = new ArrayList<>(List.of(regex("title", rx, "i"), regex("summary", rx, "i"), regex("content", rx, "i")));
            if (!tagIds.isEmpty()) ors.add(in("tags", tagIds));
            Bson baseFilter = and(eq("status", "published"), or(ors));

            // Đếm kết quả theo chuyên mục (để lọc nhanh)
            List<Document> facetRows = Article.col().aggregate(List.of(
                    Aggregates.match(baseFilter),
                    Aggregates.group("$category", Accumulators.sum("count", 1)))).into(new ArrayList<>());
            List<Object> facetIds = facetRows.stream().map(f -> f.get("_id")).toList();
            List<Document> cats = Category.col().find(in("_id", facetIds)).projection(Projections.include("name"))
                    .into(new ArrayList<>());
            List<Document> facets = new ArrayList<>();
            for (Document f : facetRows) {
                Document c = cats.stream().filter(x -> x.get("_id").equals(f.get("_id"))).findFirst().orElse(null);
                if (c == null || c.getString("name") == null || c.getString("name").isEmpty()) continue;
                facets.add(new Document("name", c.getString("name")).append("count", f.get("count")));
            }
            facets.sort((a, b) -> Integer.compare(((Number) b.get("count")).intValue(), ((Number) a.get("count")).intValue()));
            int totalAll = facetRows.stream().mapToInt(f -> ((Number) f.get("count")).intValue()).sum();

            // Áp bộ lọc chuyên mục nếu có
            Document activeCat = cats.stream().filter(c -> catName.equals(c.getString("name"))).findFirst().orElse(null);
            Bson filter = activeCat != null ? and(baseFilter, eq("category", activeCat.get("_id"))) : baseFilter;

            long total = Article.col().countDocuments(filter);
            int totalPages = (int) Math.max(1, Math.ceil(total / (double) PAGE_SIZE));
            int current = Math.min(requested, totalPages);

            List<Document> articles = Article.col().find(filter)
                    .sort("views".equals(sort) ? Sorts.descending("views") : Sorts.descending("publishedAt", "createdAt"))
                    .skip((current - 1) * PAGE_SIZE).limit(PAGE_SIZE).into(new ArrayList<>());
            Populate.apply(articles, "category", "categories", "name");
            Populate.apply(articles, "author", "users", "username");

            String activeName = activeCat != null ? activeCat.getString("name") : null;
            for (Document f : facets) {
                f.put("url", withParam(query, activeName, sort, "cat", f.getString("name")));
                f.put("active", activeCat != null && f.getString("name").equals(activeName));
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
                    "totalAll", totalAll,
                    "total", total,
                    "articles", articles,
                    "newest5Articles", newest5Articles,
                    "pagination", totalPages > 1 ? locals("pages", pages,
                            "prev", current > 1 ? link(query, activeName, sort, current - 1) : null,
                            "next", current < totalPages ? link(query, activeName, sort, current + 1) : null) : null,
                    "urlNew", withParam(query, activeName, sort, "sort", null),
                    "urlViews", withParam(query, activeName, sort, "sort", "views"),
                    "urlAllCats", withParam(query, activeName, sort, "cat", null)));
        }, () -> sendText(resp, 500, "Error during search"));
    }

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
