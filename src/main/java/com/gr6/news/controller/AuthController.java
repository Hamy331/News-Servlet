package com.gr6.news.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.gr6.news.config.Env;
import com.gr6.news.model.Ids;
import com.gr6.news.model.User;
import com.gr6.news.util.Mailer;
import com.gr6.news.util.Passwords;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;

import static com.mongodb.client.model.Filters.eq;

/** Đăng nhập, đăng ký, quên / đặt lại mật khẩu */
public final class AuthController {
    // ---------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------
    private static String jwtSecret() {
        return Env.get("JWT_SECRET", "SECRET_KEY");
    }

    // Khoá ký token đặt lại mật khẩu gắn với mật khẩu hiện tại:
    // đổi mật khẩu xong thì link cũ tự vô hiệu (link chỉ dùng được 1 lần).
    private static Algorithm resetSecret(Document user) {
        return Algorithm.HMAC256(jwtSecret() + user.getString("password"));
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Đăng nhập, đăng ký
    // ---------------------------------------------------------------------------
    /** User khớp tên đăng nhập + mật khẩu (đã bỏ mật khẩu), hoặc null */
    public static Document authenticate(String username, String password) {
        Document user = username == null ? null : User.col().find(eq("username", username)).first();
        if (user == null || !Passwords.matches(password != null ? password : "", user.getString("password"))) return null;
        user.remove("password");
        return user;
    }

    /** Lỗi của form đăng ký, hoặc null nếu hợp lệ */
    public static String validateRegistration(String username, String email, String fullName, String password, String confirm) {
        if (empty(username) || empty(email) || empty(fullName) || empty(password)) return "Vui lòng điền đầy đủ thông tin.";
        if (password.length() < 6) return "Mật khẩu phải có ít nhất 6 ký tự.";
        if (!password.equals(confirm)) return "Mật khẩu xác nhận không khớp.";
        if (User.col().countDocuments(eq("username", username)) > 0) return "Tên đăng nhập đã tồn tại, hãy chọn tên khác.";
        if (User.col().countDocuments(eq("email", email.toLowerCase().trim())) > 0) return "Email này đã được đăng ký.";
        return null;
    }

    /** Tạo tài khoản độc giả (role guest) */
    public static void register(String username, String email, String fullName, String password) {
        User.col().insertOne(User.create(username, Passwords.hash(password), email, fullName, "guest"));
    }

    // ---------------------------------------------------------------------------
    // Quên và đặt lại mật khẩu
    // ---------------------------------------------------------------------------
    public static Document findByEmail(String email) {
        return User.col().find(eq("email", email)).first();
    }

    /** Token đặt lại mật khẩu, hiệu lực 15 phút */
    public static String createResetToken(Document user) {
        return JWT.create()
                .withClaim("id", user.getObjectId("_id").toHexString())
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + 15 * 60 * 1000))
                .sign(resetSecret(user));
    }

    public static Mailer.Result sendResetEmail(Document user, String email, String resetLink) {
        String name = user.getString("full_name") != null && !user.getString("full_name").isEmpty()
                ? user.getString("full_name") : "bạn";
        return Mailer.send(email, "Đặt lại mật khẩu GR6 News",
                """
                <div style="font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#222">
                  <h2 style="font-family:Georgia,serif">GR6 <span style="color:#d93025">NEWS</span></h2>
                  <p>Chào %s,</p>
                  <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản <b>%s</b>.</p>
                  <p><a href="%s" style="display:inline-block;background:#111;color:#fff;padding:12px 22px;border-radius:8px;text-decoration:none">Đặt lại mật khẩu</a></p>
                  <p style="color:#777;font-size:13px">Đường dẫn có hiệu lực trong 15 phút và chỉ dùng được một lần. Nếu bạn không yêu cầu, hãy bỏ qua email này.</p>
                </div>""".formatted(name, user.getString("username"), resetLink),
                "Chào " + name + ",\nMở đường dẫn sau để đặt lại mật khẩu (hiệu lực 15 phút):\n" + resetLink);
    }

    /** User của token nếu token còn hạn và chưa dùng, ngược lại null */
    public static Document verifyResetToken(String token) {
        try {
            DecodedJWT decoded = JWT.decode(token);
            ObjectId id = Ids.oid(decoded.getClaim("id").asString());
            if (id == null) return null;
            Document user = User.col().find(eq("_id", id)).first();
            if (user == null) return null;
            JWT.require(resetSecret(user)).build().verify(token);
            return user;
        } catch (Exception e) {
            return null;
        }
    }

    /** Lỗi của mật khẩu mới, hoặc null nếu hợp lệ */
    public static String validateNewPassword(String password, String confirm) {
        if (password == null || password.length() < 6) return "Mật khẩu phải có ít nhất 6 ký tự.";
        if (!password.equals(confirm)) return "Mật khẩu xác nhận không khớp.";
        return null;
    }

    public static void resetPassword(Document user, String password) {
        User.col().updateOne(eq("_id", user.get("_id")), Updates.set("password", Passwords.hash(password)));
    }

    private AuthController() {}
}
