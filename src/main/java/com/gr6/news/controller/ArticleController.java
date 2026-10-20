package com.gr6.news.controller;

import com.gr6.news.model.Article;
import com.gr6.news.model.Category;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.gr6.news.model.User;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.*;

public final class ArticleController {

    // ====== Các trường người viết được phép sửa ======
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

    private static Map<String, String> meta(String label, String icon, String tone) {
        return Map.of("label", label, "icon", icon, "tone", tone);
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

    // Xử lý thả tim: trả về bài sau khi cập nhật để lấy số lượt tim mới nhất
    public static Document incrementLike(Object id) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) return null;
        return Article.col().findOneAndUpdate(eq("_id", oid), Updates.inc("likesCount", 1),
                new com.mongodb.client.model.FindOneAndUpdateOptions()
                        .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
    }

    /** Bài đã xuất bản theo điều kiện, populate category (dùng cho các khối gợi ý ở trang chi tiết) */
    public static List<Document> publishedWhere(Bson extra, Bson sort, int limit) {
        FindIterable<Document> q = Article.col().find(and(eq("status", "published"), extra));
        if (sort != null) q = q.sort(sort);
        if (limit > 0) q = q.limit(limit);
        List<Document> list = q.into(new ArrayList<>());
        Populate.apply(list, "category", "categories");
        return list;
    }

    private ArticleController() {}
}
