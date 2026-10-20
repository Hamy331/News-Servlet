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

    /** Báo cho 1 người (vd. tác giả khi bài được duyệt / bị từ chối) */
    public static void notifyUser(Object recipient, Object actorId, Document article, String type, String message, String link) {
        try {
            if (recipient == null || Ids.same(recipient, actorId)) return;
            Notification.col().insertOne(newDoc(recipient, actorId, article != null ? article.get("_id") : null, type, message, link));
        } catch (Exception e) {
            System.err.println("Lỗi tạo thông báo: " + e);
        }
    }

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
