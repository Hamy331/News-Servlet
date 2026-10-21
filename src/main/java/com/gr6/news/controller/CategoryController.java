package com.gr6.news.controller;

import com.gr6.news.model.Article;
import com.gr6.news.model.Category;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

import static com.mongodb.client.model.Filters.eq;

public final class CategoryController {
    // ---------------------------------------------------------------------------
    // Đọc chuyên mục
    // ---------------------------------------------------------------------------
    public static List<Document> getAllCategories() {
        return Category.col().find().into(new ArrayList<>());
    }

    public static Document getCategoryByName(String name) {
        return Category.col().find(eq("name", name)).first();
    }

    public static long countCategories() {
        return Category.col().countDocuments();
    }

    /**
     * Chuyên mục cho thanh điều hướng, mỗi chuyên mục kèm tối đa 5 thẻ lấy từ các bài đã xuất bản
     * (giữ thứ tự xuất hiện, bỏ trùng).
     */
    public static List<Document> getNavCategoriesWithTags() {
        List<Document> categories = Category.col().find().into(new ArrayList<>());
        // 1. Lấy tất cả bài báo đã xuất bản để móc nối Category và Tag
        List<Document> articles = Article.col().find(eq("status", "published")).into(new ArrayList<>());
        Populate.apply(articles, "category", "categories");
        Populate.apply(articles, "tags", "tags");

        // 2. Gom các Tag vào đúng Category của nó
        Map<String, LinkedHashMap<String, Document>> catTagMap = new HashMap<>();
        for (Document a : articles) {
            Document cat = a.get("category", Document.class);
            List<?> tags = a.getList("tags", Object.class);
            if (cat == null || tags == null) continue;
            var map = catTagMap.computeIfAbsent(String.valueOf(cat.get("_id")), k -> new LinkedHashMap<>());
            for (Object t : tags) {
                if (t instanceof Document tag) map.put(String.valueOf(tag.get("_id")), tag);
            }
        }

        // 3. Gắn mảng Tags (tối đa 5) vào từng Category
        List<Document> withTags = new ArrayList<>();
        for (Document cat : categories) {
            Document c = new Document(cat);
            var tagsMap = catTagMap.get(String.valueOf(cat.get("_id")));
            List<Document> tags = tagsMap == null ? new ArrayList<>() : new ArrayList<>(tagsMap.values());
            c.put("tags", tags.subList(0, Math.min(5, tags.size())));
            withTags.add(c);
        }
        return withTags;
    }

    // ---------------------------------------------------------------------------
    // Quản lý chuyên mục (admin)
    // ---------------------------------------------------------------------------
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

    public static void deleteCategory(String id) {
        ObjectId oid = Ids.oid(id);
        if (oid != null) Category.col().deleteOne(eq("_id", oid));
    }

    private CategoryController() {}
}
