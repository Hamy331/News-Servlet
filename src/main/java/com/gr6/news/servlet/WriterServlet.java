package com.gr6.news.servlet;

import com.gr6.news.controller.ArticleController;
import com.gr6.news.controller.CategoryController;
import com.gr6.news.controller.NotificationController;
import com.gr6.news.controller.TagController;
import com.gr6.news.model.Article;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

@WebServlet(urlPatterns = {"/writer", "/writer/*"})
public class WriterServlet extends BaseServlet {

    // ---------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------

    // Trạng thái hiển thị cho người viết (gộp cả trạng thái bản sửa)
    public static String displayState(Document a) {
        Document r = a.get("revision", Document.class);
        String status = a.getString("status");
        if ("published".equals(status) && r != null && "pending".equals(r.getString("status"))) return "revision_pending";
        if ("published".equals(status) && r != null && "rejected".equals(r.getString("status"))) return "revision_rejected";
        return status; // draft | pending | rejected | published
    }

    private static final Map<String, String> STATE_LABEL = Map.of(
            "draft", "Nháp",
            "pending", "Chờ duyệt",
            "rejected", "Bị từ chối",
            "published", "Đã đăng",
            "revision_pending", "Bản sửa chờ duyệt",
            "revision_rejected", "Bản sửa bị từ chối");

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

    private static boolean isValid(Map<String, Object> d) {
        return !((String) d.get("title")).isEmpty()
                && !((String) d.get("summary")).isEmpty()
                && !((String) d.get("content")).replaceAll("<[^>]*>", "").trim().isEmpty()
                && !((String) d.get("category")).isEmpty();
    }

    /** Chuyển dữ liệu form sang kiểu lưu trong Mongo (category, tags → ObjectId). Id sai → lỗi như CastError */
    @SuppressWarnings("unchecked")
    private static Document toDb(Map<String, Object> d) {
        Document out = new Document();
        out.put("title", d.get("title"));
        out.put("summary", d.get("summary"));
        out.put("content", d.get("content"));
        out.put("image_url", d.get("image_url"));
        out.put("video_url", d.get("video_url"));
        out.put("category", castId(d.get("category")));
        List<ObjectId> tags = new ArrayList<>();
        for (String t : (List<String>) d.get("tags")) tags.add(castId(t));
        out.put("tags", tags);
        return out;
    }

    private static ObjectId castId(Object v) {
        ObjectId o = Ids.oid(v);
        if (o == null) throw new IllegalArgumentException("Cast to ObjectId failed for value \"" + v + "\"");
        return o;
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
        Document me = authUser(req);
        Document r = article.get("revision", Document.class);
        String title = r != null && r.getString("title") != null && !r.getString("title").isEmpty()
                ? r.getString("title") : article.getString("title");
        NotificationController.notifyReviewers(article, categoryId, me.get("_id"), type,
                me.getString("username") + " " + verb + " “" + title + "”");
    }

    private static Document findMine(HttpServletRequest req, String id) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) return null;
        return Article.col().find(and(eq("_id", oid), eq("author", myId(req)))).first();
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
    // Viết bài mới
    // ---------------------------------------------------------------------------
    private void compose(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Document> category = CategoryController.getAllCategories();
        List<Document> tag = TagController.getAllTags();
        List<Document> mine = Article.col().find(eq("author", myId(req)))
                .projection(Projections.include("status", "revision.status")).into(new ArrayList<>());

        render(req, resp, "writer", locals(
                "layout", "admin_layout",
                "title", "Viết bài",
                "category", category,
                "tag", tag,
                "counts", locals(
                        "total", mine.size(),
                        "pending", count(mine, "pending") + count(mine, "revision_pending"),
                        "rejected", count(mine, "rejected") + count(mine, "revision_rejected"),
                        "published", mine.stream().filter(a -> "published".equals(a.getString("status"))).count())));
    }

    private static long count(List<Document> list, String state) {
        return list.stream().filter(a -> state.equals(displayState(a))).count();
    }

    private void submit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            Map<String, Object> data = readForm(req);
            if (!isValid(data)) {
                json(resp, 400, Map.of("success", false, "message", "Vui lòng nhập đủ tiêu đề, sapo, nội dung và chuyên mục."));
                return;
            }
            Document fields = toDb(data);
            fields.put("status", "pending");
            fields.put("author", myId(req));
            fields.put("views", 0);
            fields.put("history", new ArrayList<>(List.of(
                    Article.history("submitted", myId(req), (String) data.get("title"), null, null))));
            Document article = Article.create(fields);
            Article.col().insertOne(article);
            notifyReviewers(req, article, article.get("category"), "submitted", "gửi bài mới");

            json(resp, 200, Map.of("success", true, "message", "Bài viết đã được gửi!"));
        }, () -> json(resp, 500, Map.of("success", false, "message", "Không lưu được bài viết.")));
    }

    // ---------------------------------------------------------------------------
    // Bài viết của tôi
    // ---------------------------------------------------------------------------
    private void myArticles(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Document> articles = Article.col().find(eq("author", myId(req)))
                .sort(Sorts.descending("updatedAt")).into(new ArrayList<>());
        Populate.apply(articles, "category", "categories", "name");

        List<Document> list = new ArrayList<>();
        for (Document a : articles) {
            String state = displayState(a);
            Document r = a.get("revision", Document.class);
            Document v = new Document(a);
            v.put("state", state);
            v.put("stateLabel", STATE_LABEL.get(state));
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
        Document a = findMine(req, id);
        if (a == null) {
            resp.sendRedirect("/writer/articles?msg=notallowed");
            return;
        }
        ObjectId me = myId(req);
        Document r = a.get("revision", Document.class);
        Date now = new Date();
        String status = a.getString("status");

        if ("rejected".equals(status) || "draft".equals(status)) {
            Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                    Updates.set("status", "pending"),
                    Updates.set("rejectionNote", null),
                    Updates.set("updatedAt", now),
                    Updates.push("history", Article.history("resubmitted", me, a.getString("title"), null, null))));
            a.put("status", "pending");
            notifyReviewers(req, a, a.get("category"), "resubmitted", "gửi lại bài");
        } else if ("published".equals(status) && r != null && "rejected".equals(r.getString("status"))) {
            Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                    Updates.set("revision.status", "pending"),
                    Updates.unset("revision.rejectionNote"),
                    Updates.set("revision.submittedAt", now),
                    Updates.set("updatedAt", now),
                    Updates.push("history", Article.history("revision_submitted", me, r.getString("title"), null,
                            r.getList("changedFields", String.class, new ArrayList<>())))));
            notifyReviewers(req, a, r.get("category"), "revision_submitted", "gửi lại bản sửa cho bài đã đăng");
        } else {
            resp.sendRedirect("/writer/articles?msg=notallowed");
            return;
        }
        resp.sendRedirect("/writer/articles?msg=resubmitted");
    }

    // Huỷ bản sửa của bài đã đăng
    private void discard(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document a = findMine(req, id);
        Document r = a == null ? null : a.get("revision", Document.class);
        if (a == null || !"published".equals(a.getString("status")) || r == null) {
            resp.sendRedirect("/writer/articles?msg=notallowed");
            return;
        }
        Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                Updates.push("history", Article.history("revision_discarded", myId(req), r.getString("title"), null, null)),
                Updates.unset("revision"),
                Updates.set("updatedAt", new Date())));
        resp.sendRedirect("/writer/articles?msg=discarded");
    }

    // ---------------------------------------------------------------------------
    // Sửa bài
    // ---------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void renderEdit(HttpServletRequest req, HttpServletResponse resp, Document article,
                            Map<String, Object> form, String error, Map<String, String> flash) throws IOException {
        List<Document> categories = CategoryController.getAllCategories();
        List<Document> tags = TagController.getAllTags();
        String state = displayState(article);
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
                "stateLabel", STATE_LABEL.get(state),
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

    private Document loadForEdit(HttpServletRequest req, String id) {
        Document a = findMine(req, id);
        if (a != null) Populate.apply(a, "history.by", "users", "username role");
        return a;
    }

    private void showEdit(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        Document article = loadForEdit(req, id);
        if (article == null) {
            render404(req, resp, "Không tìm thấy bài viết, hoặc bạn không phải tác giả của bài này.");
            return;
        }
        renderEdit(req, resp, article, null, null, flashFor(req.getParameter("msg")));
    }

    private void saveEdit(HttpServletRequest req, HttpServletResponse resp, String id) throws IOException {
        ObjectId me = myId(req);
        Document a = findMine(req, id);
        if (a == null) {
            render404(req, resp, "Không tìm thấy bài viết, hoặc bạn không phải tác giả của bài này.");
            return;
        }

        Map<String, Object> data = readForm(req);
        String action = "submit".equals(req.getParameter("action")) ? "submit" : "save";

        if (!isValid(data)) {
            Document lean = loadForEdit(req, id);
            renderEdit(req, resp, lean, data, "Vui lòng nhập đủ tiêu đề, sapo, nội dung và chuyên mục.", null);
            return;
        }
        Document fields = toDb(data);
        Date now = new Date();

        // ----- Bài ĐÃ ĐĂNG: lưu thành bản sửa chờ duyệt, bản đang đăng giữ nguyên -----
        if ("published".equals(a.getString("status"))) {
            List<String> changed = ArticleController.diffFields(a, data);
            if (changed.isEmpty()) {
                if (a.get("revision") != null) {
                    // Người viết đã sửa về đúng như bản đang đăng → bỏ bản sửa
                    Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                            Updates.push("history", Article.history("revision_discarded", me, a.getString("title"), null, null)),
                            Updates.unset("revision"),
                            Updates.set("updatedAt", now)));
                    resp.sendRedirect("/writer/articles?msg=discarded");
                    return;
                }
                resp.sendRedirect("/writer/edit/" + a.get("_id") + "?msg=nochange");
                return;
            }
            Document revision = new Document(fields)
                    .append("status", "pending")
                    .append("changedFields", changed)
                    .append("submittedAt", now);
            Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                    Updates.set("revision", revision),
                    Updates.push("history", Article.history("revision_submitted", me, (String) data.get("title"), null, changed)),
                    Updates.set("updatedAt", now)));
            a.put("revision", revision);
            notifyReviewers(req, a, fields.get("category"), "revision_submitted", "gửi bản sửa cho bài đã đăng");
            resp.sendRedirect("/writer/articles?msg=revision");
            return;
        }

        // ----- Bài CHƯA ĐĂNG (nháp / chờ duyệt / bị từ chối): sửa trực tiếp -----
        List<String> changed = ArticleController.diffFields(a, data);
        boolean wasWaiting = "pending".equals(a.getString("status"));
        List<Bson> ups = new ArrayList<>();
        for (var e : fields.entrySet()) ups.add(Updates.set(e.getKey(), e.getValue()));
        // mongoose chỉ đổi updatedAt khi có thay đổi thật
        if (!changed.isEmpty() || ("submit".equals(action) && !wasWaiting)) ups.add(Updates.set("updatedAt", now));
        a.putAll(fields);

        if ("submit".equals(action) && !wasWaiting) {
            ups.add(Updates.set("status", "pending"));
            ups.add(Updates.set("rejectionNote", null));
            ups.add(Updates.push("history", Article.history("resubmitted", me, (String) data.get("title"), null, changed)));
            Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(ups));
            notifyReviewers(req, a, a.get("category"), "resubmitted", "đã sửa và gửi lại bài");
            resp.sendRedirect("/writer/articles?msg=resubmitted");
            return;
        }

        if (!changed.isEmpty()) {
            ups.add(Updates.push("history", Article.history("edited", me, (String) data.get("title"), null, changed)));
        }
        Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(ups));
        if (wasWaiting && !changed.isEmpty()) {
            notifyReviewers(req, a, a.get("category"), "edited", "vừa chỉnh sửa bài đang chờ duyệt");
        }
        resp.sendRedirect("/writer/articles?msg=saved");
    }
}
