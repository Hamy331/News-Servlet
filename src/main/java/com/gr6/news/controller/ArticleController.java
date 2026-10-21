package com.gr6.news.controller;

import com.gr6.news.model.Article;
import com.gr6.news.model.Category;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.gr6.news.model.Tag;
import com.gr6.news.model.User;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.*;

public final class ArticleController {
    // ---------------------------------------------------------------------------
    // Hằng số và tiện ích dùng chung
    // ---------------------------------------------------------------------------
    public static final List<String> EDITABLE_FIELDS =
            List.of("title", "summary", "content", "image_url", "video_url", "category", "tags");

    public static final Map<String, String> FIELD_LABELS = Map.of(
            "title", "Tiêu đề", "summary", "Sapo", "content", "Nội dung", "image_url", "Ảnh bìa",
            "video_url", "Video", "category", "Chuyên mục", "tags", "Thẻ");

    // Nhãn hiển thị cho từng sự kiện trong lịch sử bài viết
    public static final Map<String, Map<String, String>> HISTORY_META = Map.of(
            "submitted", meta("Gửi bài lần đầu", "bi-send", "blue"),
            "edited", meta("Chỉnh sửa", "bi-pencil", "gray"),
            "resubmitted", meta("Sửa & gửi lại để duyệt", "bi-arrow-repeat", "blue"),
            "approved", meta("Được duyệt & xuất bản", "bi-check2-circle", "green"),
            "rejected", meta("Bị từ chối", "bi-x-circle", "red"),
            "revision_submitted", meta("Gửi bản sửa (bài đã đăng)", "bi-pencil-square", "amber"),
            "revision_approved", meta("Bản sửa được duyệt — thay bản cũ", "bi-check2-all", "green"),
            "revision_rejected", meta("Bản sửa bị từ chối — giữ bản cũ", "bi-x-octagon", "red"),
            "revision_discarded", meta("Huỷ bản sửa", "bi-trash3", "gray"));

    public static final Map<String, String> STATE_LABEL = Map.of(
            "draft", "Nháp",
            "pending", "Chờ duyệt",
            "rejected", "Bị từ chối",
            "published", "Đã đăng",
            "revision_pending", "Bản sửa chờ duyệt",
            "revision_rejected", "Bản sửa bị từ chối");

    private static Map<String, String> meta(String label, String icon, String tone) {
        return Map.of("label", label, "icon", icon, "tone", tone);
    }

    // Trạng thái hiển thị cho người viết (gộp cả trạng thái bản sửa)
    public static String displayState(Document a) {
        Document r = a.get("revision", Document.class);
        String status = a.getString("status");
        if ("published".equals(status) && r != null && "pending".equals(r.getString("status"))) return "revision_pending";
        if ("published".equals(status) && r != null && "rejected".equals(r.getString("status"))) return "revision_rejected";
        return status; // draft | pending | rejected | published
    }

    /** Lịch sử để hiển thị: mới nhất trước, kèm nhãn / icon / màu và tên trường đã đổi */
    public static List<Document> historyForView(Document article) {
        List<Document> list = new ArrayList<>(article.getList("history", Document.class, new ArrayList<>()));
        Collections.reverse(list);
        List<Document> out = new ArrayList<>();
        for (Document h : list) {
            Document v = new Document(h);
            Map<String, String> m = HISTORY_META.getOrDefault(h.getString("action"),
                    meta(h.getString("action"), "bi-dot", "gray"));
            v.putAll(m);
            v.put("changedLabels", labels(h.getList("changedFields", String.class, List.of())));
            out.add(v);
        }
        return out;
    }

    public static List<String> labels(List<String> fields) {
        return fields.stream().map(f -> FIELD_LABELS.getOrDefault(f, f)).collect(Collectors.toList());
    }

    // So sánh 2 bản, trả về danh sách trường đã thay đổi
    private static String norm(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(x -> Ids.idOf(x)).filter(s -> !s.isEmpty() && !"null".equals(s))
                    .sorted().collect(Collectors.joining("|"));
        }
        if (v instanceof Document d && d.containsKey("_id")) return String.valueOf(d.get("_id"));
        if (v instanceof ObjectId o) return o.toHexString();
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static List<String> diffFields(Document oldDoc, Map<String, Object> newData) {
        List<String> changed = new ArrayList<>();
        for (String f : EDITABLE_FIELDS) {
            if (newData.containsKey(f) && !norm(oldDoc.get(f)).equals(norm(newData.get(f)))) changed.add(f);
        }
        return changed;
    }

    // ---------------------------------------------------------------------------
    // 1. Viết và gửi bài (người viết)
    // ---------------------------------------------------------------------------
    /** Đủ tiêu đề, sapo, nội dung (bỏ thẻ HTML) và chuyên mục */
    public static boolean isValidInput(Map<String, Object> d) {
        return !((String) d.get("title")).isEmpty()
                && !((String) d.get("summary")).isEmpty()
                && !((String) d.get("content")).replaceAll("<[^>]*>", "").trim().isEmpty()
                && !((String) d.get("category")).isEmpty();
    }

    /** Chuyển dữ liệu form sang kiểu lưu trong Mongo (category, tags → ObjectId). Id sai → lỗi như CastError */
    @SuppressWarnings("unchecked")
    public static Document toDocument(Map<String, Object> d) {
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

    /** Lưu bài mới ở trạng thái chờ duyệt, trả về bài vừa tạo */
    public static Document submitArticle(Map<String, Object> data, ObjectId authorId) {
        Document fields = toDocument(data);
        fields.put("status", "pending");
        fields.put("author", authorId);
        fields.put("views", 0);
        fields.put("history", new ArrayList<>(List.of(
                Article.history("submitted", authorId, (String) data.get("title"), null, null))));
        Document article = Article.create(fields);
        Article.col().insertOne(article);
        return article;
    }

    // ---------------------------------------------------------------------------
    // 2. Bài của tôi, sửa bài, gửi lại (người viết)
    // ---------------------------------------------------------------------------
    /** Số bài của người viết theo trạng thái hiển thị (dùng ở trang Viết bài) */
    public static Map<String, Object> countByAuthor(ObjectId authorId) {
        List<Document> mine = Article.col().find(eq("author", authorId))
                .projection(Projections.include("status", "revision.status")).into(new ArrayList<>());
        Map<String, Object> m = new HashMap<>();
        m.put("total", mine.size());
        m.put("pending", countState(mine, "pending") + countState(mine, "revision_pending"));
        m.put("rejected", countState(mine, "rejected") + countState(mine, "revision_rejected"));
        m.put("published", mine.stream().filter(a -> "published".equals(a.getString("status"))).count());
        return m;
    }

    private static long countState(List<Document> list, String state) {
        return list.stream().filter(a -> state.equals(displayState(a))).count();
    }

    /** Toàn bộ bài của một tác giả, mới cập nhật trước, kèm tên chuyên mục */
    public static List<Document> getArticlesByAuthor(ObjectId authorId) {
        List<Document> articles = Article.col().find(eq("author", authorId))
                .sort(Sorts.descending("updatedAt")).into(new ArrayList<>());
        Populate.apply(articles, "category", "categories", "name");
        return articles;
    }

    /** Bài của chính người viết (null nếu id sai hoặc không phải tác giả) */
    public static Document findByAuthor(Object id, ObjectId authorId) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) return null;
        return Article.col().find(and(eq("_id", oid), eq("author", authorId))).first();
    }

    /** Như findByAuthor, kèm người thực hiện từng mục lịch sử (cho trang sửa bài) */
    public static Document findByAuthorForEdit(Object id, ObjectId authorId) {
        Document a = findByAuthor(id, authorId);
        if (a != null) Populate.apply(a, "history.by", "users", "username role");
        return a;
    }

    /**
     * Lưu form sửa bài của người viết. Bài đã đăng → lưu thành bản sửa chờ duyệt (bản đang đăng giữ nguyên);
     * bài chưa đăng → sửa trực tiếp, action "submit" thì gửi duyệt lại.
     * {@code a} được cập nhật theo dữ liệu mới để dùng tiếp khi gửi thông báo.
     * @return "discarded" | "nochange" | "revision" | "resubmitted" | "edited_pending" | "saved"
     */
    public static String saveByWriter(Document a, Map<String, Object> data, String action, ObjectId me) {
        Document fields = toDocument(data);
        Date now = new Date();
        List<String> changed = diffFields(a, data);

        // ----- Bài ĐÃ ĐĂNG -----
        if ("published".equals(a.getString("status"))) {
            if (changed.isEmpty()) {
                if (a.get("revision") != null) {
                    // Người viết đã sửa về đúng như bản đang đăng → bỏ bản sửa
                    Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                            Updates.push("history", Article.history("revision_discarded", me, a.getString("title"), null, null)),
                            Updates.unset("revision"),
                            Updates.set("updatedAt", now)));
                    return "discarded";
                }
                return "nochange";
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
            return "revision";
        }

        // ----- Bài CHƯA ĐĂNG (nháp / chờ duyệt / bị từ chối) -----
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
            return "resubmitted";
        }

        if (!changed.isEmpty()) {
            ups.add(Updates.push("history", Article.history("edited", me, (String) data.get("title"), null, changed)));
        }
        Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(ups));
        return wasWaiting && !changed.isEmpty() ? "edited_pending" : "saved";
    }

    /**
     * Gửi lại bài bị từ chối / nháp, hoặc bản sửa bị từ chối (không cần sửa).
     * @return "new" | "revision", hoặc null nếu trạng thái hiện tại không cho phép
     */
    public static String resubmit(Document a, ObjectId me) {
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
            return "new";
        }
        if ("published".equals(status) && r != null && "rejected".equals(r.getString("status"))) {
            Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                    Updates.set("revision.status", "pending"),
                    Updates.unset("revision.rejectionNote"),
                    Updates.set("revision.submittedAt", now),
                    Updates.set("updatedAt", now),
                    Updates.push("history", Article.history("revision_submitted", me, r.getString("title"), null,
                            r.getList("changedFields", String.class, new ArrayList<>())))));
            return "revision";
        }
        return null;
    }

    /** Huỷ bản sửa của bài đã đăng. Trả false nếu bài không có bản sửa */
    public static boolean discardRevision(Document a, ObjectId me) {
        Document r = a.get("revision", Document.class);
        if (!"published".equals(a.getString("status")) || r == null) return false;
        Article.col().updateOne(eq("_id", a.get("_id")), Updates.combine(
                Updates.push("history", Article.history("revision_discarded", me, r.getString("title"), null, null)),
                Updates.unset("revision"),
                Updates.set("updatedAt", new Date())));
        return true;
    }

    // ---------------------------------------------------------------------------
    // 3. Duyệt bài (editor, admin)
    // ---------------------------------------------------------------------------
    /** Kết quả kiểm tra quyền duyệt. denied = không phải editor/admin; error = "notfound" | "forbidden" */
    public record ReviewCheck(boolean ok, boolean denied, String error) {}

    // Editor chỉ được xử lý bài thuộc chuyên mục Admin đã phân công; Admin xử lý được mọi bài.
    public static ReviewCheck checkReviewPermission(Document user, String articleId) {
        if (user == null || !Arrays.asList("editor", "administrator").contains(user.getString("role"))) {
            return new ReviewCheck(false, true, null);
        }
        ObjectId id = Ids.oid(articleId);
        if (id == null) throw new IllegalArgumentException("Cast to ObjectId failed");
        Document article = Article.col().find(eq("_id", id)).first();
        Document r = article == null ? null : article.get("revision", Document.class);
        boolean isNew = article != null && "pending".equals(article.getString("status"));
        boolean isRevision = article != null && "published".equals(article.getString("status"))
                && r != null && "pending".equals(r.getString("status"));
        if (article == null || (!isNew && !isRevision)) return new ReviewCheck(false, false, "notfound");

        if ("editor".equals(user.getString("role"))) {
            // Bản sửa có thể đổi chuyên mục → kiểm tra theo chuyên mục của bản đang chờ duyệt
            Object cat = isRevision ? (r.get("category") != null ? r.get("category") : article.get("category")) : article.get("category");
            Document me = User.col().find(eq("_id", user.get("_id"))).first();
            List<Object> cats = me == null ? List.of() : me.getList("avaiCategory", Object.class, List.of());
            boolean allowed = cats.stream().anyMatch(c -> Ids.same(c, cat));
            if (!allowed) return new ReviewCheck(false, false, "forbidden");
        }
        return new ReviewCheck(true, false, null);
    }

    // Chuẩn hoá 1 bài trong hàng chờ: nếu là BẢN SỬA của bài đã đăng thì hiển thị nội dung bản sửa
    private static Document toReviewItem(Document a) {
        Document r = a.get("revision", Document.class);
        if ("published".equals(a.getString("status")) && r != null && "pending".equals(r.getString("status"))) {
            Document v = new Document(a);
            v.put("isRevision", true);
            v.put("liveTitle", a.get("title"));
            v.put("title", r.get("title"));
            v.put("summary", r.get("summary"));
            v.put("content", r.get("content"));
            v.put("image_url", r.get("image_url"));
            v.put("category", r.get("category") != null ? r.get("category") : a.get("category"));
            v.put("createdAt", r.get("submittedAt"));
            v.put("changedLabels", labels(r.getList("changedFields", String.class, List.of())));
            return v;
        }
        return a;
    }

    private static List<Document> populateQueue(List<Document> list) {
        Populate.apply(list, "category", "categories");
        Populate.apply(list, "revision.category", "categories");
        Populate.apply(list, "tags", "tags");
        Populate.apply(list, "author", "users", "username full_name");
        return list;
    }

    private static int byCreatedAsc(Document a, Document b) {
        Date x = a.getDate("createdAt"), y = b.getDate("createdAt");
        if (x == null || y == null) return 0;
        return x.compareTo(y);
    }

    // Hàng chờ của Editor: bài mới + bản sửa thuộc chuyên mục được phân công
    public static List<Document> getPendingArticlesByUser(ObjectId userId) {
        if (userId == null) return new ArrayList<>();
        Document user = User.col().find(eq("_id", userId)).first();
        List<Object> cats = user == null ? null : user.getList("avaiCategory", Object.class);
        if (cats == null || cats.isEmpty()) return new ArrayList<>();
        List<Document> list = Article.col().find(or(
                and(eq("status", "pending"), in("category", cats)),
                and(eq("status", "published"), eq("revision.status", "pending"), in("revision.category", cats))
        )).into(new ArrayList<>());
        populateQueue(list);
        return list.stream().map(ArticleController::toReviewItem).sorted(ArticleController::byCreatedAsc)
                .collect(Collectors.toList());
    }

    // Hàng chờ của Admin: mọi chuyên mục
    public static List<Document> getPendingArticles() {
        List<Document> list = Article.col().find(or(
                eq("status", "pending"),
                and(eq("status", "published"), eq("revision.status", "pending"))
        )).into(new ArrayList<>());
        populateQueue(list);
        return list.stream().map(ArticleController::toReviewItem).sorted(ArticleController::byCreatedAsc)
                .collect(Collectors.toList());
    }

    /**
     * Duyệt: bài mới (pending) → xuất bản; hoặc bản sửa của bài đã đăng → thay bản cũ.
     * Lượt xem / tim / bình luận / ngày đăng gốc được giữ nguyên.
     * @return Map { article, kind: "new" | "revision" } hoặc null
     */
    public static Map<String, Object> publishArticle(Object articleId, ObjectId approverId, String articleType) {
        ObjectId id = Ids.oid(articleId);
        if (id == null) return null;
        Document article = Article.col().find(eq("_id", id)).first();
        if (article == null) return null;
        Document r = article.get("revision", Document.class);
        Date now = new Date();

        if ("pending".equals(article.getString("status"))) {
            String type = articleType != null ? articleType : "normal";
            Article.col().updateOne(eq("_id", id), Updates.combine(
                    Updates.set("status", "published"),
                    Updates.set("rejectionNote", null),
                    Updates.set("approver", approverId),
                    Updates.set("publishedAt", now),
                    Updates.set("articleType", type),
                    Updates.set("updatedAt", now),
                    Updates.push("history", Article.history("approved", approverId, article.getString("title"), type, null))));
            return Map.of("article", Article.col().find(eq("_id", id)).first(), "kind", "new");
        }

        if ("published".equals(article.getString("status")) && r != null && "pending".equals(r.getString("status"))) {
            List<Bson> ups = new ArrayList<>();
            for (String f : EDITABLE_FIELDS) if (r.containsKey(f)) ups.add(Updates.set(f, r.get(f)));
            ups.add(Updates.set("approver", approverId));
            if (articleType != null) ups.add(Updates.set("articleType", articleType));
            ups.add(Updates.set("lastRevisedAt", now));
            ups.add(Updates.set("updatedAt", now));
            ups.add(Updates.push("history", Article.history("revision_approved", approverId, r.getString("title"), null,
                    r.getList("changedFields", String.class, new ArrayList<>()))));
            ups.add(Updates.unset("revision"));
            Article.col().updateOne(eq("_id", id), Updates.combine(ups));
            return Map.of("article", Article.col().find(eq("_id", id)).first(), "kind", "revision");
        }
        return null;
    }

    // Từ chối: bài mới → trạng thái rejected; bản sửa → giữ nguyên bản đang đăng, đánh dấu bản sửa bị từ chối
    public static Map<String, Object> rejectArticle(Object articleId, String note, ObjectId reviewerId) {
        ObjectId id = Ids.oid(articleId);
        if (id == null) return null;
        Document article = Article.col().find(eq("_id", id)).first();
        if (article == null) return null;
        Document r = article.get("revision", Document.class);
        Date now = new Date();

        if ("pending".equals(article.getString("status"))) {
            List<Bson> ups = new ArrayList<>(List.of(
                    Updates.set("status", "rejected"),
                    Updates.set("rejectionNote", note),
                    Updates.set("updatedAt", now),
                    Updates.push("history", Article.history("rejected", reviewerId, article.getString("title"), note, null))));
            if (reviewerId != null) ups.add(Updates.set("approver", reviewerId));
            Article.col().updateOne(eq("_id", id), Updates.combine(ups));
            return Map.of("article", Article.col().find(eq("_id", id)).first(), "kind", "new");
        }

        if ("published".equals(article.getString("status")) && r != null && "pending".equals(r.getString("status"))) {
            Article.col().updateOne(eq("_id", id), Updates.combine(
                    Updates.set("revision.status", "rejected"),
                    Updates.set("revision.rejectionNote", note),
                    Updates.set("revision.reviewer", reviewerId),
                    Updates.set("updatedAt", now),
                    Updates.push("history", Article.history("revision_rejected", reviewerId, r.getString("title"), note, null))));
            return Map.of("article", Article.col().find(eq("_id", id)).first(), "kind", "revision");
        }
        return null;
    }

    public static List<Document> getReviewHistory(ObjectId reviewerId, int limit) {
        Bson filter = and(in("status", "published", "rejected"),
                reviewerId != null ? eq("approver", reviewerId) : and(exists("approver", true), ne("approver", null)));
        List<Document> list = Article.col().find(filter).sort(Sorts.descending("updatedAt")).limit(limit)
                .into(new ArrayList<>());
        Populate.apply(list, "category", "categories", "name");
        Populate.apply(list, "author", "users", "username");
        Populate.apply(list, "approver", "users", "username role");
        return list;
    }

    // Đếm số bài theo trạng thái, có thể lọc theo người duyệt
    public static long countByStatus(String status, ObjectId reviewerId) {
        Bson filter = reviewerId != null ? and(eq("status", status), eq("approver", reviewerId)) : eq("status", status);
        return Article.col().countDocuments(filter);
    }

    /** Bài để xem lịch sử. error = "notfound" | "forbidden" */
    public record HistoryAccess(Document article, boolean isAuthor, String error) {}

    // Xem được: tác giả, Admin, Editor phụ trách chuyên mục của bài
    public static HistoryAccess getHistoryForViewer(Document user, String id) {
        ObjectId oid = Ids.oid(id);
        Document article = oid == null ? null : Article.col().find(eq("_id", oid)).first();
        if (article == null) return new HistoryAccess(null, false, "notfound");
        Populate.apply(article, "category", "categories", "name");
        Populate.apply(article, "author", "users", "username full_name");
        Populate.apply(article, "history.by", "users", "username role");
        Populate.apply(article, "revision.category", "categories", "name");

        Document author = article.get("author", Document.class);
        boolean isAuthor = author != null && Ids.same(author.get("_id"), user.get("_id"));
        boolean allowed = "administrator".equals(user.getString("role")) || isAuthor;
        if (!allowed && "editor".equals(user.getString("role"))) {
            Document me = User.col().find(eq("_id", user.get("_id"))).first();
            Set<String> cats = new HashSet<>();
            if (me != null) for (Object c : me.getList("avaiCategory", Object.class, List.of())) cats.add(Ids.idOf(c));
            Document cat = article.get("category", Document.class);
            Document r = article.get("revision", Document.class);
            Document rcat = r == null ? null : r.get("category", Document.class);
            allowed = (cat != null && cats.contains(Ids.idOf(cat))) || (rcat != null && cats.contains(Ids.idOf(rcat)));
        }
        if (!allowed) return new HistoryAccess(null, isAuthor, "forbidden");
        return new HistoryAccess(article, isAuthor, null);
    }

    // ---------------------------------------------------------------------------
    // 4. Bài đã đăng: trang chủ, chuyên mục, chi tiết (độc giả)
    // ---------------------------------------------------------------------------
    public static long countPublished() {
        return Article.col().countDocuments(eq("status", "published"));
    }

    /** find({status:"published"}) + populate category, tags, author */
    private static List<Document> publishedFull(Bson sort, int limit) {
        FindIterable<Document> q = Article.col().find(eq("status", "published")).sort(sort);
        if (limit > 0) q = q.limit(limit);
        List<Document> list = q.into(new ArrayList<>());
        populateFull(list);
        return list;
    }

    public static void populateFull(List<Document> list) {
        Populate.apply(list, "category", "categories");
        Populate.apply(list, "tags", "tags");
        Populate.apply(list, "author", "users");
    }

    public static List<Document> getTop10MostViewedArticles() {
        return publishedFull(Sorts.descending("views"), 10);
    }

    public static List<Document> getTop10NewestArticles() {
        return publishedFull(Sorts.descending("createdAt"), 10);
    }

    public static List<Document> getTop5NewestArticles() {
        return publishedFull(Sorts.descending("createdAt"), 5);
    }

    public static List<Document> getLatestArticleFromEachCategory() {
        List<Document> out = new ArrayList<>();
        for (Document category : Category.col().find()) {
            Document a = Article.col().find(and(eq("category", category.get("_id")), eq("status", "published")))
                    .sort(Sorts.descending("createdAt")).first();
            if (a != null) out.add(a);
        }
        populateFull(out);
        return out;
    }

    /** Bài đã xuất bản của một chuyên mục (thứ tự lưu trong DB) */
    public static List<Document> getPublishedByCategory(Object categoryId) {
        return Article.col().find(and(eq("category", categoryId), eq("status", "published"))).into(new ArrayList<>());
    }

    /** Bài xem nhiều nhất của một chuyên mục */
    public static List<Document> getTopViewedByCategory(Object categoryId, int limit) {
        return Article.col().find(and(eq("category", categoryId), eq("status", "published")))
                .sort(Sorts.descending("views")).limit(limit).into(new ArrayList<>());
    }

    // Get a single article by ID
    public static Document getArticleById(Object id) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) return null;
        Document a = Article.col().find(eq("_id", oid)).first();
        if (a == null) return null;
        Populate.apply(a, "author", "users", "username");
        Populate.apply(a, "category", "categories", "name");
        Populate.apply(a, "tags", "tags", "name");
        return a;
    }

    public static void incrementView(Object id) {
        ObjectId oid = Ids.oid(id);
        if (oid != null) Article.col().updateOne(eq("_id", oid), Updates.inc("views", 1));
    }

    /** Bài đã xuất bản theo điều kiện, populate category (dùng cho các khối gợi ý ở trang chi tiết) */
    private static List<Document> publishedWhere(Bson extra, Bson sort, int limit) {
        FindIterable<Document> q = Article.col().find(and(eq("status", "published"), extra));
        if (sort != null) q = q.sort(sort);
        if (limit > 0) q = q.limit(limit);
        List<Document> list = q.into(new ArrayList<>());
        Populate.apply(list, "category", "categories");
        return list;
    }

    // Các khối gợi ý ở trang chi tiết
    public static List<Document> getAdsArticles(int limit) {
        return publishedWhere(eq("articleType", "advertisement"), null, limit);
    }

    public static List<Document> getMoreByAuthor(Object authorId, ObjectId exceptId, int limit) {
        return publishedWhere(and(eq("author", authorId), ne("_id", exceptId)), Sorts.descending("createdAt"), limit);
    }

    public static List<Document> getHotArticles(ObjectId exceptId, int limit) {
        return publishedWhere(and(in("articleType", "hot", "must_know"), ne("_id", exceptId)), Sorts.descending("views"), limit);
    }

    public static List<Document> getRelatedByCategory(Object categoryId, ObjectId exceptId, int limit) {
        return publishedWhere(and(eq("category", categoryId), ne("_id", exceptId)), Sorts.descending("createdAt"), limit);
    }

    public static List<Document> getRecommended(ObjectId exceptId, int limit) {
        return publishedWhere(ne("_id", exceptId), Sorts.descending("publishedAt"), limit);
    }

    /** Kết quả thả tim. status = "ok" | "notfound" | "own" | "failed" */
    public record LikeResult(String status, Object likes) {}

    // Writer không được tự thả tim bài của chính mình. Id sai → lỗi (route trả "Lỗi server.")
    public static LikeResult likeArticle(String articleId, Document user) {
        ObjectId oid = Ids.oid(articleId);
        if (oid == null) throw new IllegalArgumentException("Cast to ObjectId failed");
        Document article = Article.col().find(eq("_id", oid)).first();
        if (article == null) return new LikeResult("notfound", null);
        if (user != null && "writer".equals(user.getString("role")) && Ids.same(user.get("_id"), article.get("author"))) {
            return new LikeResult("own", null);
        }
        Document updated = incrementLike(oid);
        if (updated == null) return new LikeResult("failed", null);
        return new LikeResult("ok", updated.get("likesCount"));
    }

    // Xử lý thả tim: trả về bài sau khi cập nhật để lấy số lượt tim mới nhất
    public static Document incrementLike(Object id) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) return null;
        return Article.col().findOneAndUpdate(eq("_id", oid), Updates.inc("likesCount", 1),
                new com.mongodb.client.model.FindOneAndUpdateOptions()
                        .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
    }

    // ---------------------------------------------------------------------------
    // 5. Tìm kiếm
    // ---------------------------------------------------------------------------
    public record SearchResult(List<Document> facets, int totalAll, long total, List<Document> articles,
                               int currentPage, int totalPages, String activeCategory) {}

    // Thoát ký tự đặc biệt để người dùng gõ "c++" hay "(abc" không làm lỗi RegExp
    private static String escapeRegex(String s) {
        return s.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
    }

    /** Tìm bài đã xuất bản theo tiêu đề, sapo, nội dung hoặc tên thẻ; đếm theo chuyên mục; phân trang */
    public static SearchResult search(String query, String categoryName, String sort, int page, int pageSize) {
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
        Document activeCat = cats.stream().filter(c -> categoryName.equals(c.getString("name"))).findFirst().orElse(null);
        Bson filter = activeCat != null ? and(baseFilter, eq("category", activeCat.get("_id"))) : baseFilter;

        long total = Article.col().countDocuments(filter);
        int totalPages = (int) Math.max(1, Math.ceil(total / (double) pageSize));
        int current = Math.min(page, totalPages);

        List<Document> articles = Article.col().find(filter)
                .sort("views".equals(sort) ? Sorts.descending("views") : Sorts.descending("publishedAt", "createdAt"))
                .skip((current - 1) * pageSize).limit(pageSize).into(new ArrayList<>());
        Populate.apply(articles, "category", "categories", "name");
        Populate.apply(articles, "author", "users", "username");

        return new SearchResult(facets, totalAll, total, articles, current, totalPages,
                activeCat != null ? activeCat.getString("name") : null);
    }

    private ArticleController() {}
}
