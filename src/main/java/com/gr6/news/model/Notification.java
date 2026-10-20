package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

/** Collection "notifications"*/
public final class Notification {
    public static MongoCollection<Document> col() {
        return Db.col("notifications");
    }

    private Notification() {}
}
