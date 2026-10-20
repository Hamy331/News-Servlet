package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

/**
 * Người dùng (collection "users"):
 * username, password, email, full_name, role, subscription_expiration,
 * avaiCategory[] (chuyên mục Editor phụ trách), savedArticles[{ article, savedAt }]
 */
public final class User {
    public static final List<String> STAFF = Arrays.asList("writer", "editor", "administrator");
    public static final List<String> READERS = Arrays.asList("guest", "subscriber");

    public static MongoCollection<Document> col() {
        return Db.col("users");
    }

    public static Document create(String username, String hashedPassword, String email, String fullName, String role) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, 1);
        return new Document("_id", new ObjectId())
                .append("username", username.trim())
                .append("password", hashedPassword)
                .append("email", email.toLowerCase().trim())
                .append("full_name", fullName.trim())
                .append("role", role == null || role.isEmpty() ? "guest" : role)
                .append("subscription_expiration", c.getTime())
                .append("avaiCategory", new ArrayList<>())
                .append("savedArticles", new ArrayList<>())
                .append("__v", 0);
    }

    public static boolean isStaff(Document u) {
        return u != null && STAFF.contains(u.getString("role"));
    }

    public static boolean isReader(Document u) {
        return u != null && READERS.contains(u.getString("role"));
    }

    private User() {}
}
