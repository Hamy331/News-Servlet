package com.gr6.news.controller;

import com.gr6.news.model.Ids;
import com.gr6.news.model.Tag;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

import static com.mongodb.client.model.Filters.eq;

public final class TagController {

    public static void createTag(String name) {
        if (name == null || name.isEmpty()) return; // mongoose báo lỗi validate, controller nuốt lỗi
        if (Tag.col().find(eq("name", name)).first() != null) return; // "Tag already exists"
        Date now = new Date();
        Tag.col().insertOne(new Document("_id", new ObjectId()).append("name", name)
                .append("createdAt", now).append("updatedAt", now).append("__v", 0));
    }

    public static List<Document> getAllTags() {
        return Tag.col().find().into(new ArrayList<>());
    }

    public static void deleteTag(String id) {
        ObjectId oid = Ids.oid(id);
        if (oid != null) Tag.col().deleteOne(eq("_id", oid));
    }

    private TagController() {}
}
