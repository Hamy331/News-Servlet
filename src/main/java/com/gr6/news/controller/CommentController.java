package com.gr6.news.controller;

import com.gr6.news.model.Article;
import com.gr6.news.model.Comment;
import com.gr6.news.model.Ids;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

import static com.mongodb.client.model.Filters.eq;

public final class CommentController {

    public static Document addComment(Object articleId, String name, String content) {
        try {
            ObjectId aid = Ids.oid(articleId);
            if (aid == null) return null;
            Date now = new Date();
            Document c = new Document("_id", new ObjectId()).append("articleId", aid).append("name", name)
                    .append("content", content).append("createdAt", now).append("updatedAt", now).append("__v", 0);
            Comment.col().insertOne(c);
            Article.col().updateOne(eq("_id", aid), Updates.inc("commentsCount", 1));
            return c;
        } catch (Exception e) {
            System.err.println("Lỗi thêm bình luận: " + e);
            return null;
        }
    }

    public static List<Document> getCommentsByArticle(Object articleId) {
        ObjectId aid = Ids.oid(articleId);
        if (aid == null) return new ArrayList<>();
        return Comment.col().find(eq("articleId", aid)).sort(Sorts.descending("createdAt")).into(new ArrayList<>());
    }

    private CommentController() {}
}
