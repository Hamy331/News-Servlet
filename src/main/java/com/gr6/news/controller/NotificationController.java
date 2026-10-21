package com.gr6.news.controller;

import com.gr6.news.model.Ids;
import com.gr6.news.model.Notification;
import com.gr6.news.model.User;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

import static com.mongodb.client.model.Filters.*;

public final class NotificationController {
    // ---------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------
    // Đường dẫn mở khi bấm vào thông báo, tuỳ vai trò người nhận
    private static String reviewLink(String role) {
        return "administrator".equals(role) ? "/administrator#review" : "/editor#review";
    }

    private static Document newDoc(Object recipient, Object actor, Object article, String type, String message, String link) {
        Date now = new Date();
        Document d = new Document("_id", new ObjectId()).append("recipient", Ids.oid(recipient));
        if (actor != null) d.append("actor", Ids.oid(actor));
        if (article != null) d.append("article", Ids.oid(article));
        return d.append("type", type).append("message", message)
                .append("link", link != null ? link : "/").append("read", false)
                .append("createdAt", now).append("updatedAt", now).append("__v", 0);
    }

    // ---------------------------------------------------------------------------
    // Gửi thông báo khi bài được gửi, duyệt, từ chối
    // ---------------------------------------------------------------------------
    /** Báo cho người duyệt liên quan: tất cả Admin + Editor được phân công chuyên mục của bài */
    public static void notifyReviewers(Document article, Object categoryId, Object actorId, String type, String message) {
        try {
            List<Document> reviewers = User.col().find(and(
                    ne("_id", Ids.oid(actorId)),
                    or(eq("role", "administrator"), and(eq("role", "editor"), eq("avaiCategory", Ids.oid(categoryId))))
            )).into(new ArrayList<>());
            if (reviewers.isEmpty()) return;
            List<Document> docs = new ArrayList<>();
            for (Document r : reviewers) {
                docs.add(newDoc(r.get("_id"), actorId, article.get("_id"), type, message, reviewLink(r.getString("role"))));
            }
            Notification.col().insertMany(docs);
        } catch (Exception e) {
            System.err.println("Lỗi tạo thông báo cho người duyệt: " + e);
        }
    }

    /** Người viết vừa gửi / sửa bài → báo người duyệt, nội dung "<username> <verb> “<tiêu đề>”" */
    public static void notifyReviewersOfWriter(Document article, Object categoryId, Document writer, String type, String verb) {
        Document r = article.get("revision", Document.class);
        String title = r != null && r.getString("title") != null && !r.getString("title").isEmpty()
                ? r.getString("title") : article.getString("title");
        notifyReviewers(article, categoryId, writer.get("_id"), type,
                writer.getString("username") + " " + verb + " “" + title + "”");
    }

    /** Báo cho 1 người (vd. tác giả khi bài được duyệt / bị từ chối) */
    public static void notifyUser(Object recipient, Object actorId, Document article, String type, String message, String link) {
        try {
            if (recipient == null || Ids.same(recipient, actorId)) return;
            Notification.col().insertOne(newDoc(recipient, actorId, article != null ? article.get("_id") : null, type, message, link));
        } catch (Exception e) {
            System.err.println("Lỗi tạo thông báo: " + e);
        }
    }

    /** Báo tác giả: bài (hoặc bản sửa) đã được duyệt */
    public static void notifyAuthorApproved(Document article, Object reviewerId, boolean revision) {
        notifyUser(article.get("author"), reviewerId, article,
                revision ? "revision_approved" : "approved",
                revision ? "Bản sửa của bài “" + article.getString("title") + "” đã được duyệt và thay thế bản cũ."
                        : "Bài “" + article.getString("title") + "” đã được duyệt và xuất bản.",
                "/details?id=" + article.get("_id"));
    }

    /** Báo tác giả: bài (hoặc bản sửa) bị từ chối, kèm ghi chú */
    public static void notifyAuthorRejected(Document article, Object reviewerId, boolean revision, String note) {
        Document r = article.get("revision", Document.class);
        String title = revision && r != null && r.getString("title") != null && !r.getString("title").isEmpty()
                ? r.getString("title") : article.getString("title");
        notifyUser(article.get("author"), reviewerId, article,
                revision ? "revision_rejected" : "rejected",
                revision ? "Bản sửa của bài “" + title + "” chưa được duyệt — bài đang đăng giữ nguyên. Ghi chú: " + note
                        : "Bài “" + title + "” bị từ chối. Ghi chú: " + note,
                "/writer/edit/" + article.get("_id"));
    }

    // ---------------------------------------------------------------------------
    // Chuông thông báo của người nhận
    // ---------------------------------------------------------------------------
    public static List<Document> getLatest(Object userId, int limit) {
        return Notification.col().find(eq("recipient", Ids.oid(userId))).sort(Sorts.descending("createdAt"))
                .limit(limit).into(new ArrayList<>());
    }

    public static long countUnread(Object userId) {
        return Notification.col().countDocuments(and(eq("recipient", Ids.oid(userId)), eq("read", false)));
    }

    public static Document markRead(Object id, Object userId) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) return null;
        return Notification.col().findOneAndUpdate(and(eq("_id", oid), eq("recipient", Ids.oid(userId))),
                Updates.combine(Updates.set("read", true), Updates.set("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public static void markAllRead(Object userId) {
        Notification.col().updateMany(and(eq("recipient", Ids.oid(userId)), eq("read", false)),
                Updates.combine(Updates.set("read", true), Updates.set("updatedAt", new Date())));
    }

    private NotificationController() {}
}
