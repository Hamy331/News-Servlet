package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

/** Collection "categories" */
public final class Category {
    public static MongoCollection<Document> col() {
        return Db.col("categories");
    }

    private Category() {}
}
