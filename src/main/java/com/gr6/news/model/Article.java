package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

/**
 * Bài viết (collection "articles"):
 * title, summary, content, image_url[], video_url[], articleType, status, author, approver,
 * category, tags[], views, likesCount, commentsCount, rejectionNote, publishedAt,
 * revision { title, summary, content, image_url, video_url, category, tags, status, changedFields,
 *            submittedAt, rejectionNote, reviewer },
 * lastRevisedAt, history[{ action, by, note, changedFields, title, at }], createdAt, updatedAt
 */
public final class Article {
    public static final List<String> TYPES = List.of("normal", "hot", "must_know", "advertisement");

    public static MongoCollection<Document> col() {
        return Db.col("articles");
    }

    /** Document mới với giá trị mặc định như schema mongoose */
    public static Document create(Document data) {
        Date now = new Date();
        Document d = new Document("_id", new ObjectId());
        d.put("image_url", new ArrayList<>());
        d.put("video_url", new ArrayList<>());
        d.put("articleType", "normal");
        d.put("status", "pending");
        d.put("tags", new ArrayList<>());
        d.put("views", 0);
        d.put("likesCount", 0);
        d.put("commentsCount", 0);
        d.put("history", new ArrayList<>());
        d.putAll(data);
        d.put("createdAt", now);
        d.put("updatedAt", now);
        d.put("__v", 0);
        return d;
    }

    /** Một dòng lịch sử (historySchema) */
    public static Document history(String action, Object by, String title, String note, List<String> changedFields) {
        Document h = new Document("action", action);
        if (by != null) h.put("by", Ids.oid(by));
        if (note != null) h.put("note", note);
        h.put("changedFields", changedFields != null ? changedFields : new ArrayList<>());
        if (title != null) h.put("title", title);
        h.put("at", new Date());
        return h;
    }

    private Article() {}
}
