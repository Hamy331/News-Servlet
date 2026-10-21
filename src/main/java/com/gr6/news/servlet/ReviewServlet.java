package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.NotificationController;
import com.gr6.news.model.Article;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;

/** Thay cho routes/articleRoutes.js: duyệt / từ chối bài (Editor & Admin) và lịch sử bài viết */
@WebServlet("/article/*")
public class ReviewServlet extends BaseServlet {
    // ---------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------
    private static String backTo(HttpServletRequest req, String msg) {
        Document u = authUser(req);
        String base = u != null && "administrator".equals(u.getString("role")) ? "/administrator" : "/editor";
        return base + "?msg=" + msg + "#review";
    }

    /** Kiểm tra quyền duyệt; không được phép thì tự trả 403 hoặc redirect và trả false */
    private boolean allowed(HttpServletRequest req, HttpServletResponse resp, String articleId) throws IOException {
        ArticleController.ReviewCheck check = ArticleController.checkReviewPermission(authUser(req), articleId);
        if (check.denied()) {
            sendText(resp, 403, "Forbidden");
            return false;
        }
        if (!check.ok()) {
            resp.sendRedirect(backTo(req, check.error()));
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    // Định tuyến
    // ---------------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 2 && p[0].equals("history")) history(req, resp, p[1]);
        else render404(req, resp, null);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 1 && p[0].equals("approve")) approve(req, resp);
        else if (p.length == 1 && p[0].equals("reject")) reject(req, resp);
        else render404(req, resp, null);
    }

    // ---------------------------------------------------------------------------
    // Duyệt / từ chối: POST /article/approve, /article/reject
    // ---------------------------------------------------------------------------
    private void approve(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            String articleId = req.getParameter("articleId");
            if (!allowed(req, resp, articleId)) return;
            ObjectId me = myId(req);
            String type = Article.TYPES.contains(req.getParameter("articleType")) ? req.getParameter("articleType") : "normal";
            Map<String, Object> result = ArticleController.publishArticle(articleId, me, type);
            if (result == null) {
                resp.sendRedirect(backTo(req, "notfound"));
                return;
            }
            boolean revision = "revision".equals(result.get("kind"));
            NotificationController.notifyAuthorApproved((Document) result.get("article"), me, revision);
            resp.sendRedirect(backTo(req, revision ? "revapproved" : "approved"));
        }, () -> resp.sendRedirect(backTo(req, "notfound")));
    }

    private void reject(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            String articleId = req.getParameter("articleIdReject");
            if (!allowed(req, resp, articleId)) return;
            ObjectId me = myId(req);
            String typed = param(req, "rejectNote").trim();
            String note = typed.isEmpty() ? "Nội dung chưa đạt yêu cầu." : typed;
            Map<String, Object> result = ArticleController.rejectArticle(articleId, note, me);
            if (result == null) {
                resp.sendRedirect(backTo(req, "notfound"));
                return;
            }
            boolean revision = "revision".equals(result.get("kind"));
            NotificationController.notifyAuthorRejected((Document) result.get("article"), me, revision, note);
            resp.sendRedirect(backTo(req, revision ? "revrejected" : "rejected"));
        }, () -> resp.sendRedirect(backTo(req, "notfound")));
    }

    // ---------------------------------------------------------------------------
    // Lịch sử bài viết: GET /article/history/{id}
    // ---------------------------------------------------------------------------
    private void history(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document user = authUser(req);
        if (user == null) {
            resp.sendRedirect("/auth/login?next=" + enc(originalUrl(req)));
            return;
        }
        ArticleController.HistoryAccess access = ArticleController.getHistoryForViewer(user, id);
        if ("notfound".equals(access.error())) {
            render404(req, resp, "Không tìm thấy bài viết.");
            return;
        }
        if ("forbidden".equals(access.error())) {
            render404(req, resp, "Bạn không có quyền xem lịch sử của bài viết này.");
            return;
        }
        render(req, resp, "articleHistory", locals(
                "layout", "admin_layout",
                "title", "Lịch sử bài viết",
                "article", access.article(),
                "history", ArticleController.historyForView(access.article()),
                "isAuthor", access.isAuthor()));
    }
}
