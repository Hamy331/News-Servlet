package com.gr6.news.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gr6.news.controller.ArticleController;
import com.gr6.news.view.View;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class BaseServlet extends HttpServlet {
    protected static final Gson GSON = new Gson();

    protected void render(HttpServletRequest req, HttpServletResponse resp, String view, Map<String, Object> locals)
            throws IOException {
        View.render(req, resp, view, locals);
    }

    protected void render(HttpServletRequest req, HttpServletResponse resp, int status, String view,
                          Map<String, Object> locals) throws IOException {
        resp.setStatus(status);
        View.render(req, resp, view, locals);
    }

    /** utils/notFound.js → render404(res, message) */
    protected void render404(HttpServletRequest req, HttpServletResponse resp, String message) throws IOException {
        List<Document> suggestions = new ArrayList<>();
        try {
            List<Document> newest = ArticleController.getTop5NewestArticles();
            suggestions = newest.subList(0, Math.min(3, newest.size()));
        } catch (Exception ignored) {
            // không chặn trang 404 nếu DB lỗi
        }
        render(req, resp, 404, "404_article", locals(
                "title", "Không tìm thấy trang",
                "message", message != null ? message : "Trang bạn tìm có thể đã bị xoá, đổi địa chỉ hoặc chưa từng tồn tại.",
                "suggestions", suggestions));
    }

    protected void json(HttpServletResponse resp, int status, Map<String, ?> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=utf-8");
        resp.getWriter().write(GSON.toJson(body));
    }

    protected void json(HttpServletResponse resp, Map<String, ?> body) throws IOException {
        json(resp, 200, body);
    }

    /** res.send("<script>alert(...)</script>") ở bản Node */
    protected void sendAlert(HttpServletResponse resp, String message, String backUrl) throws IOException {
        resp.setContentType("text/html; charset=utf-8");
        String js = GSON.toJson(message);
        resp.getWriter().write("<script>alert(" + js + "); window.location.href=\"" + backUrl + "\";</script>");
    }

    protected void sendText(HttpServletResponse resp, int status, String text) throws IOException {
        resp.setStatus(status);
        resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().write(text);
    }

    // ---------- Đọc request ----------
    /** Chuỗi trong body/query, null → "" */
    protected static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v;
    }

    /** Body JSON của fetch(..., { body: JSON.stringify({...}) }) */
    protected static JsonObject jsonBody(HttpServletRequest req) {
        try {
            JsonElement e = JsonParser.parseReader(req.getReader());
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    protected static String jsonString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /** Phần đường dẫn sau tiền tố servlet: /writer/edit/12 → ["edit", "12"] */
    protected static String[] parts(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/") || p.isEmpty()) return new String[0];
        return p.substring(1).split("/");
    }

    /** req.originalUrl */
    protected static String originalUrl(HttpServletRequest req) {
        String q = req.getQueryString();
        return req.getRequestURI() + (q != null ? "?" + q : "");
    }

    protected static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------- Session ----------
    protected static Document authUser(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s == null ? null : (Document) s.getAttribute("authUser");
    }

    protected static boolean isAuthenticated(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && Boolean.TRUE.equals(s.getAttribute("auth"));
    }

    protected static ObjectId myId(HttpServletRequest req) {
        Document u = authUser(req);
        return u == null ? null : u.getObjectId("_id");
    }

    // ---------- middlewares/auth.js ----------
    /** isAuth: chưa đăng nhập → nhớ trang đang mở rồi chuyển tới /auth/login */
    protected static boolean isAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isAuthenticated(req)) {
            req.getSession().setAttribute("retUrl", originalUrl(req));
            resp.sendRedirect("/auth/login");
            return false;
        }
        return true;
    }

    /** verifyRole(roles): sai vai trò → về trang chủ */
    protected static boolean verifyRole(HttpServletRequest req, HttpServletResponse resp, String... roles) throws IOException {
        Document u = authUser(req);
        if (u == null || !Arrays.asList(roles).contains(u.getString("role"))) {
            resp.sendRedirect("/");
            return false;
        }
        return true;
    }

    // ---------- Dựng dữ liệu cho view ----------
    /** Map cho phép giá trị null (Map.of thì không) */
    protected static Map<String, Object> locals(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    protected static Map<String, String> flash(String type, String text) {
        return Map.of("type", type, "text", text);
    }

    @FunctionalInterface
    protected interface Action {
        void run() throws Exception;
    }

    /** Bắt lỗi giống khối try/catch trong từng route của bản Node */
    protected static void guard(Action action, Action onError) throws IOException {
        try {
            action.run();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                onError.run();
            } catch (IOException io) {
                throw io;
            } catch (Exception ignored) {
                // bỏ qua
            }
        }
    }
}
