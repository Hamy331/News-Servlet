package com.gr6.news.controller;

import com.gr6.news.model.Category;
import com.gr6.news.model.Ids;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

import static com.mongodb.client.model.Filters.eq;

public final class CategoryController {

    public static void createCategory(String name, String description) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Category name is required");
        if (Category.col().find(eq("name", name)).first() != null) {
            throw new IllegalArgumentException("Category already exists");
        }
        Date now = new Date();
        Document d = new Document("_id", new ObjectId()).append("name", name);
        if (description != null) d.append("description", description);
        Category.col().insertOne(d.append("createdAt", now).append("updatedAt", now).append("__v", 0));
    }

    public static List<Document> getAllCategories() {
        return Category.col().find().into(new ArrayList<>());
    }

    public static Document getCategoryByName(String name) {
        return Category.col().find(eq("name", name)).first();
    }

    public static void deleteCategory(String id) {
        ObjectId oid = Ids.oid(id);
        if (oid != null) Category.col().deleteOne(eq("_id", oid));
    }

    private CategoryController() {}
}
