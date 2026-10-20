package com.gr6.news.controller;

import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.gr6.news.model.User;
import com.gr6.news.util.Passwords;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

import static com.mongodb.client.model.Filters.eq;

public final class UserController {

    public static List<Document> getAllUsers() {
        return User.col().find().into(new ArrayList<>());
    }

    public static void addAvailableCategory(String userId, String categoryId) {
        ObjectId uid = Ids.oid(userId), cid = Ids.oid(categoryId);
        if (uid == null || cid == null) throw new IllegalArgumentException("User ID and Category ID are required");
        if (User.col().find(eq("_id", uid)).first() == null) throw new IllegalArgumentException("User not found");
        User.col().updateOne(eq("_id", uid), Updates.addToSet("avaiCategory", cid));
    }

    // Tất cả editor, kèm thông tin đầy đủ của các chuyên mục được giao
    public static List<Document> getEditorsOnly() {
        List<Document> list = User.col().find(eq("role", "editor")).into(new ArrayList<>());
        Populate.apply(list, "avaiCategory", "categories");
        return list;
    }

    public static void createUser(String username, String password, String email, String fullName, String role) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()
                || email == null || email.isBlank() || fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập đủ thông tin");
        }
        if (role != null && !role.isEmpty() && !List.of("guest", "subscriber", "writer", "editor", "administrator").contains(role)) {
            throw new IllegalArgumentException("Vai trò không hợp lệ");
        }
        if (User.col().find(eq("username", username.trim())).first() != null) {
            throw new IllegalArgumentException("Tên đăng nhập đã tồn tại");
        }
        if (User.col().find(eq("email", email.toLowerCase().trim())).first() != null) {
            throw new IllegalArgumentException("Email đã được sử dụng");
        }
        User.col().insertOne(User.create(username, Passwords.hash(password), email, fullName, role));
    }

    public static void deleteUser(String id) {
        ObjectId oid = Ids.oid(id);
        if (oid == null) throw new IllegalArgumentException("User not found");
        if (User.col().deleteOne(eq("_id", oid)).getDeletedCount() == 0) throw new IllegalArgumentException("User not found");
    }

    private UserController() {}
}
