package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.CategoryController;
import com.gr6.news.controller.NotificationController;
import com.gr6.news.controller.TagController;
import com.gr6.news.model.Ids;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;

import java.io.IOException;
import java.util.*;

@WebServlet(urlPatterns = {"/writer", "/writer/*"})
public class WriterServlet extends BaseServlet {
    // ---------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------
    private static List<String> toArr(String[] values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String s : values) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Đọc dữ liệu từ form viết / sửa bài (giữ dạng chuỗi như req.body) */
    private static Map<String, Object> readForm(HttpServletRequest req) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", param(req, "title").trim());
        d.put("summary", param(req, "summary").trim());
        d.put("content", param(req, "content"));
        d.put("image_url", toArr(req.getParameterValues("image_url")));
        d.put("video_url", toArr(req.getParameterValues("video_url")));
        d.put("category", param(req, "category"));
        // form gửi "tags[]"; gửi kiểu "tags" cũng nhận
        String[] tags = req.getParameterValues("tags[]");
        d.put("tags", toArr(tags != null ? tags : req.getParameterValues("tags")));
        return d;
    }

    private static Map<String, String> flashFor(String msg) {
        if (msg == null) return null;
        return switch (msg) {
            case "saved" -> flash("success", "Đã lưu thay đổi.");
            case "resubmitted" -> flash("success", "Đã gửi lại bài để biên tập viên duyệt.");
            case "revision" -> flash("success", "Đã gửi bản sửa. Bài đang đăng vẫn giữ bản cũ cho tới khi bản sửa được duyệt.");
            case "discarded" -> flash("warning", "Đã huỷ bản sửa. Bài đang đăng giữ nguyên.");
            case "nochange" -> flash("warning", "Bạn chưa thay đổi nội dung nào so với bản đang đăng.");
            case "notallowed" -> flash("danger", "Không thể thực hiện thao tác này với bài viết ở trạng thái hiện tại.");
            default -> null;
        };
    }

    // Báo cho người duyệt liên quan (Admin + Editor phụ trách chuyên mục)
    private static void notifyReviewers(HttpServletRequest req, Document article, Object categoryId, String type, String verb) {
        NotificationController.notifyReviewersOfWriter(article, categoryId, authUser(req), type, verb);
    }

    // ---------------------------------------------------------------------------
    // Định tuyến
    // ---------------------------------------------------------------------------
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 0) {
            if (verifyRole(req, resp, "writer")) compose(req, resp);
        } else if (p.length == 1 && p[0].equals("articles")) {
            if (verifyRole(req, resp, "writer")) myArticles(req, resp);
        } else if (p.length == 2 && p[0].equals("edit")) {
            if (verifyRole(req, resp, "writer")) showEdit(req, resp, p[1]);
        } else {
            render404(req, resp, null);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 1 && p[0].equals("submit_article")) {
            if (verifyRole(req, resp, "writer", "administrator")) submit(req, resp);
        } else if (p.length == 3 && p[0].equals("articles") && p[2].equals("resubmit")) {
            if (verifyRole(req, resp, "writer")) resubmit(req, resp, p[1]);
        } else if (p.length == 3 && p[0].equals("articles") && p[2].equals("discard")) {
            if (verifyRole(req, resp, "writer")) discard(req, resp, p[1]);
        } else if (p.length == 2 && p[0].equals("edit")) {
            if (verifyRole(req, resp, "writer")) saveEdit(req, resp, p[1]);
        } else {
            render404(req, resp, null);
        }
    }

    // ---------------------------------------------------------------------------
    // 1. Viết bài mới: GET /writer, POST /writer/submit_article
    // ---------------------------------------------------------------------------
    private void compose(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        render(req, resp, "writer", locals(
                "layout", "admin_layout",
                "title", "Viết bài",
                "category", CategoryController.getAllCategories(),
                "tag", TagController.getAllTags(),
                "counts", ArticleController.countByAuthor(myId(req))));
    }

    private void submit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            Map<String, Object> data = readForm(req);
            if (!ArticleController.isValidInput(data)) {
                json(resp, 400, Map.of("success", false, "message", "Vui lòng nhập đủ tiêu đề, sapo, nội dung và chuyên mục."));
                return;
            }
            Document article = ArticleController.submitArticle(data, myId(req));
            notifyReviewers(req, article, article.get("category"), "submitted", "gửi bài mới");

            json(resp, 200, Map.of("success", true, "message", "Bài viết đã được gửi!"));
        }, () -> json(resp, 500, Map.of("success", false, "message", "Không lưu được bài viết.")));
    }

    // ---------------------------------------------------------------------------
    // 2. Bài viết của tôi: GET /writer/articles, gửi lại, huỷ bản sửa
    // ---------------------------------------------------------------------------
    private void myArticles(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Document> articles = ArticleController.getArticlesByAuthor(myId(req));

        List<Document> list = new ArrayList<>();
        for (Document a : articles) {
            String state = ArticleController.displayState(a);
            Document r = a.get("revision", Document.class);
            Document v = new Document(a);
            v.put("state", state);
            v.put("stateLabel", ArticleController.STATE_LABEL.get(state));
            v.put("canResubmit", state.equals("rejected") || state.equals("draft") || state.equals("revision_rejected"));
            v.put("canDiscard", state.equals("revision_pending") || state.equals("revision_rejected"));
            v.put("note", state.equals("revision_rejected") ? r.get("rejectionNote")
                    : state.equals("rejected") ? a.get("rejectionNote") : null);
            v.put("revisionTitle", r != null ? r.get("title") : null);
            list.add(v);
        }

        long totalViews = list.stream().mapToLong(a -> a.get("views") instanceof Number n ? n.longValue() : 0).sum();
        render(req, resp, "writerArticles", locals(
                "layout", "admin_layout",
                "title", "Bài viết của tôi",
                "articles", list,
                "counts", locals(
                        "all", list.size(),
                        "pending", countState(list, "pending", "revision_pending"),
                        "rejected", countState(list, "rejected", "revision_rejected"),
                        "published", countState(list, "published", "revision_pending", "revision_rejected"),
                        "draft", countState(list, "draft")),
                "totalViews", totalViews,
                "flash", flashFor(req.getParameter("msg"))));
    }

    private static long countState(List<Document> list, String... states) {
        List<String> s = Arrays.asList(states);
        return list.stream().filter(a -> s.contains(a.getString("state"))).count();
    }

    // Gửi lại bài bị từ chối (không cần sửa)
    private void resubmit(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document a = ArticleController.findByAuthor(id, myId(req));
        String kind = a == null ? null : ArticleController.resubmit(a, myId(req));
        if (kind == null) {
            resp.sendRedirect("/writer/articles?msg=notallowed");
            return;
        }
        if (kind.equals("new")) {
            notifyReviewers(req, a, a.get("category"), "resubmitted", "gửi lại bài");
        } else {
            notifyReviewers(req, a, a.get("revision", Document.class).get("category"), "revision_submitted",
                    "gửi lại bản sửa cho bài đã đăng");
        }
        resp.sendRedirect("/writer/articles?msg=resubmitted");
    }

    // Huỷ bản sửa của bài đã đăng
    private void discard(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document a = ArticleController.findByAuthor(id, myId(req));
        if (a == null || !ArticleController.discardRevision(a, myId(req))) {
            resp.sendRedirect("/writer/articles?msg=notallowed");
            return;
        }
        resp.sendRedirect("/writer/articles?msg=discarded");
    }

    // ---------------------------------------------------------------------------
    // 3. Sửa bài: GET / POST /writer/edit/{id}
    // ---------------------------------------------------------------------------
    private void showEdit(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document article = loadForEdit(req, id);
        if (article == null) {
            render404(req, resp, "Không tìm thấy bài viết, hoặc bạn không phải tác giả của bài này.");
            return;
        }
        renderEdit(req, resp, article, null, null, flashFor(req.getParameter("msg")));
    }

    private void saveEdit(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document a = ArticleController.findByAuthor(id, myId(req));
        if (a == null) {
            render404(req, resp, "Không tìm thấy bài viết, hoặc bạn không phải tác giả của bài này.");
            return;
        }

        Map<String, Object> data = readForm(req);
        String action = "submit".equals(req.getParameter("action")) ? "submit" : "save";

        if (!ArticleController.isValidInput(data)) {
            Document lean = loadForEdit(req, id);
            renderEdit(req, resp, lean, data, "Vui lòng nhập đủ tiêu đề, sapo, nội dung và chuyên mục.", null);
            return;
        }

        String outcome = ArticleController.saveByWriter(a, data, action, myId(req));
        switch (outcome) {
            case "discarded" -> resp.sendRedirect("/writer/articles?msg=discarded");
            case "nochange" -> resp.sendRedirect("/writer/edit/" + a.get("_id") + "?msg=nochange");
            case "revision" -> {
                notifyReviewers(req, a, a.get("revision", Document.class).get("category"), "revision_submitted",
                        "gửi bản sửa cho bài đã đăng");
                resp.sendRedirect("/writer/articles?msg=revision");
            }
            case "resubmitted" -> {
                notifyReviewers(req, a, a.get("category"), "resubmitted", "đã sửa và gửi lại bài");
                resp.sendRedirect("/writer/articles?msg=resubmitted");
            }
            case "edited_pending" -> {
                notifyReviewers(req, a, a.get("category"), "edited", "vừa chỉnh sửa bài đang chờ duyệt");
                resp.sendRedirect("/writer/articles?msg=saved");
            }
            default -> resp.sendRedirect("/writer/articles?msg=saved");
        }
    }

    private Document loadForEdit(HttpServletRequest req, String id) {
        return ArticleController.findByAuthorForEdit(id, myId(req));
    }

    @SuppressWarnings("unchecked")
    private void renderEdit(HttpServletRequest req, HttpServletResponse resp, Document article,
                            Map<String, Object> form, String error, Map<String, String> flash) throws IOException {
        List<Document> categories = CategoryController.getAllCategories();
        List<Document> tags = TagController.getAllTags();
        String state = ArticleController.displayState(article);
        Document revision = article.get("revision", Document.class);
        // Bài đã đăng: form hiển thị bản sửa (nếu có), không thì bản đang đăng
        Map<String, Object> src = form != null ? form
                : ("published".equals(article.getString("status")) && revision != null ? revision : article);
        String catId = Ids.idOf(src.get("category"));
        if ("null".equals(catId)) catId = "";
        Set<String> tagIds = new HashSet<>();
        List<Object> srcTags = (List<Object>) src.get("tags");
        if (srcTags != null) for (Object t : srcTags) tagIds.add(Ids.idOf(t));

        List<Document> cats = new ArrayList<>();
        for (Document c : categories) cats.add(new Document(c).append("selected", String.valueOf(c.get("_id")).equals(catId)));
        List<Document> tagList = new ArrayList<>();
        for (Document t : tags) tagList.add(new Document(t).append("selected", tagIds.contains(String.valueOf(t.get("_id")))));

        render(req, resp, "writerEdit", locals(
                "layout", "admin_layout",
                "title", "Sửa bài viết",
                "article", article,
                "form", locals(
                        "title", src.get("title"),
                        "summary", src.get("summary"),
                        "content", src.get("content"),
                        "image_url", first(src.get("image_url")),
                        "video_url", first(src.get("video_url"))),
                "categories", cats,
                "tags", tagList,
                "state", state,
                "stateLabel", ArticleController.STATE_LABEL.get(state),
                "isPublished", "published".equals(article.getString("status")),
                "note", "revision_rejected".equals(state) ? revision.get("rejectionNote")
                        : "rejected".equals(state) ? article.get("rejectionNote") : null,
                "history", ArticleController.historyForView(article),
                "error", error,
                "flash", flash));
    }

    private static Object first(Object v) {
        if (v instanceof List<?> l && !l.isEmpty() && l.get(0) != null && !String.valueOf(l.get(0)).isEmpty()) return l.get(0);
        return "";
    }
}
