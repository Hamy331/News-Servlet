package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

/** Collection "comments" */
public final class Comment {
    public static MongoCollection<Document> col() {
        return Db.col("comments");
    }

    private Comment() {}
}
