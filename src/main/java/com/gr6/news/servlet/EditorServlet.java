package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
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

@WebServlet(urlPatterns = {"/editor", "/editor/*"})
public class EditorServlet extends BaseServlet {

    // Thông báo hiển thị sau khi duyệt / từ chối (đọc từ ?msg=...)
    static Map<String, String> reviewFlash(String msg, boolean editor) {
        if (msg == null) return null;
        return switch (msg) {
            case "approved" -> flash("success", "Đã duyệt và xuất bản bài viết.");
            case "rejected" -> flash("warning", "Đã từ chối bài viết và gửi ghi chú cho tác giả.");
            case "revapproved" -> flash("success", "Đã duyệt bản sửa — nội dung mới đã thay thế bản đang đăng.");
            case "revrejected" -> flash("warning", "Đã từ chối bản sửa — bài đang đăng giữ nguyên bản cũ.");
            case "forbidden" -> editor ? flash("danger", "Bạn không phụ trách chuyên mục của bài viết này.") : null;
            case "notfound" -> flash("danger", "Không tìm thấy bài viết hoặc bài đã được xử lý.");
            default -> null;
        };
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (parts(req).length > 0) {
            render404(req, resp, null);
            return;
        }
        if (!isAuth(req, resp) || !verifyRole(req, resp, "editor")) return;
        guard(() -> {
            ObjectId editorId = myId(req);

            // Editor chỉ thấy bài chờ duyệt thuộc chuyên mục được Admin phân công
            Document me = User.col().find(eq("_id", editorId)).first();
            if (me != null) Populate.apply(me, "avaiCategory", "categories", "name");
            List<Document> pending = ArticleController.getPendingArticlesByUser(editorId);
            List<Document> history = ArticleController.getReviewHistory(editorId, 20);
            long approvedCount = ArticleController.countByStatus("published", editorId);
            long rejectedCount = ArticleController.countByStatus("rejected", editorId);

            List<Object> myCategories = me == null ? new ArrayList<>() : me.getList("avaiCategory", Object.class, new ArrayList<>());

            render(req, resp, "editor", locals(
                    "layout", "admin_layout",
                    "title", "Editor Desk",
                    "pending", pending,
                    "history", history,
                    "myCategories", myCategories,
                    "stats", locals(
                            "pending", pending.size(),
                            "categories", myCategories.size(),
                            "approved", approvedCount,
                            "rejected", rejectedCount),
                    "flash", reviewFlash(req.getParameter("msg"), true)));
        }, () -> sendText(resp, 500, "Internal Server Error"));
    }
}
