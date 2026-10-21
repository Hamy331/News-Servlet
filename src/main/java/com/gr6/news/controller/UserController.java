package com.gr6.news.controller;

import com.gr6.news.model.Article;
import com.gr6.news.model.Ids;
import com.gr6.news.model.Populate;
import com.gr6.news.model.User;
import com.gr6.news.util.Passwords;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;

public final class UserController {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // ---------------------------------------------------------------------------
    // Quản lý thành viên (admin)
    // ---------------------------------------------------------------------------
    public static List<Document> getAllUsers() {
        return User.col().find().into(new ArrayList<>());
    }

    // Tất cả editor, kèm thông tin đầy đủ của các chuyên mục được giao
    public static List<Document> getEditorsOnly() {
        List<Document> list = User.col().find(eq("role", "editor")).into(new ArrayList<>());
        Populate.apply(list, "avaiCategory", "categories");
        return list;
    }

    public static long countByRoles(String... roles) {
        return User.col().countDocuments(in("role", Arrays.asList(roles)));
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

    public static void addAvailableCategory(String userId, String categoryId) {
        ObjectId uid = Ids.oid(userId), cid = Ids.oid(categoryId);
        if (uid == null || cid == null) throw new IllegalArgumentException("User ID and Category ID are required");
        if (User.col().find(eq("_id", uid)).first() == null) throw new IllegalArgumentException("User not found");
        User.col().updateOne(eq("_id", uid), Updates.addToSet("avaiCategory", cid));
    }

    // ---------------------------------------------------------------------------
    // Hồ sơ cá nhân
    // ---------------------------------------------------------------------------
    /** Hồ sơ của một user (không kèm mật khẩu), kèm tên các chuyên mục được giao */
    public static Document getProfile(ObjectId id) {
        Document user = User.col().find(eq("_id", id)).first();
        if (user == null) return null;
        user.remove("password");
        Populate.apply(user, "avaiCategory", "categories", "name");
        return user;
    }

    /**
     * Cập nhật họ tên, email.
     * @return "invalid" | "emailtaken" | "updated"
     */
    public static String updateProfile(ObjectId id, String fullName, String email) {
        if (fullName.isEmpty() || !EMAIL.matcher(email).matches()) return "invalid";
        if (User.col().countDocuments(and(eq("email", email), ne("_id", id))) > 0) return "emailtaken";
        User.col().updateOne(eq("_id", id), Updates.combine(Updates.set("full_name", fullName), Updates.set("email", email)));
        return "updated";
    }

    /**
     * Đổi mật khẩu.
     * @return "weakpass" | "mismatch" | "wrongpass" | "password" (thành công)
     */
    public static String changePassword(ObjectId id, String current, String next, String confirm) {
        if (next == null || next.length() < 6) return "weakpass";
        if (!next.equals(confirm)) return "mismatch";
        Document user = User.col().find(eq("_id", id)).first();
        if (user == null || !Passwords.matches(current != null ? current : "", user.getString("password"))) return "wrongpass";
        User.col().updateOne(eq("_id", user.get("_id")), Updates.set("password", Passwords.hash(next)));
        return "password";
    }

    // ---------------------------------------------------------------------------
    // Bài viết đã lưu (độc giả)
    // ---------------------------------------------------------------------------
    /** Kết quả lưu / bỏ lưu. found = false khi bài không tồn tại hoặc chưa xuất bản */
    public record SaveResult(boolean found, boolean saved, int count) {}

    /** savedArticles đã populate bài (chỉ bài published), bỏ các mục có bài đã bị gỡ */
    public static List<Document> getSavedArticles(ObjectId userId, String select, boolean withAuthor) {
        Document me = User.col().find(eq("_id", userId)).first();
        if (me == null) return new ArrayList<>();
        Populate.apply(List.of(me), "savedArticles.article", "articles", select, eq("status", "published"));
        List<Document> saved = new ArrayList<>(me.getList("savedArticles", Document.class, new ArrayList<>()));
        List<Document> out = new ArrayList<>();
        for (Document s : saved) if (s.get("article") instanceof Document) out.add(s);
        List<Document> articles = out.stream().map(s -> s.get("article", Document.class)).toList();
        Populate.apply(new ArrayList<>(articles), "category", "categories", "name");
        if (withAuthor) Populate.apply(new ArrayList<>(articles), "author", "users", "username");
        return out;
    }

    public static boolean hasSaved(Object userId, ObjectId articleId) {
        return User.col().countDocuments(and(eq("_id", userId), eq("savedArticles.article", articleId))) > 0;
    }

    // Id sai → lỗi. Bài mới lưu được thêm lên đầu danh sách để hiển thị trước
    public static SaveResult toggleSavedArticle(Object userId, String articleId) {
        ObjectId aid = Ids.oid(articleId);
        if (aid == null) throw new IllegalArgumentException("Cast to ObjectId failed");
        Document article = Article.col().find(and(eq("_id", aid), eq("status", "published"))).first();
        if (article == null) return new SaveResult(false, false, 0);
        boolean already = hasSaved(userId, aid);
        if (already) {
            User.col().updateOne(eq("_id", userId), Updates.pull("savedArticles", new Document("article", aid)));
        } else {
            User.col().updateOne(eq("_id", userId), Updates.pushEach("savedArticles",
                    List.of(new Document("article", aid).append("savedAt", new Date())), new PushOptions().position(0)));
        }
        Document me = User.col().find(eq("_id", userId)).first();
        int count = me == null ? 0 : me.getList("savedArticles", Object.class, List.of()).size();
        return new SaveResult(true, !already, count);
    }

    private UserController() {}
}
