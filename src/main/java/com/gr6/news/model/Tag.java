package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

/** Collection "tags"*/
public final class Tag {
    public static MongoCollection<Document> col() {
        return Db.col("tags");
    }

    private Tag() {}
}
