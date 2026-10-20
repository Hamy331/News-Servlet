package com.gr6.news.servlet;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.gr6.news.config.Env;
import com.gr6.news.model.Ids;
import com.gr6.news.model.User;
import com.gr6.news.util.Mailer;
import com.gr6.news.util.Passwords;
import com.mongodb.client.model.Updates;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;

/** Thay cho routes/authRoutes.js + Controllers/auth.js */
@WebServlet("/auth/*")
public class AuthServlet extends BaseServlet {
    private static final String EXPIRED = "Đường dẫn đã hết hạn, đã được sử dụng hoặc không hợp lệ. Hãy gửi yêu cầu mới.";

    private static String jwtSecret() {
        return Env.get("JWT_SECRET", "SECRET_KEY");
    }

    // Khoá ký token đặt lại mật khẩu gắn với mật khẩu hiện tại:
    // đổi mật khẩu xong thì link cũ tự vô hiệu (link chỉ dùng được 1 lần).
    private static Algorithm resetSecret(Document user) {
        return Algorithm.HMAC256(jwtSecret() + user.getString("password"));
    }

    private static Document verifyResetToken(String token) {
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

    /** Mọi trang /auth/* dùng layout riêng (thẻ chia đôi, không có thanh điều hướng) */
    private static Map<String, Object> page(Object... kv) {
        Map<String, Object> m = locals(kv);
        m.put("layout", "auth");
        return m;
    }

    private static String action(HttpServletRequest req) {
        String[] p = parts(req);
        return p.length > 0 ? p[0] : "";
    }

    // Nếu đã login thì không vào login/register/forgot/reset
    private boolean redirectIfAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (isAuthenticated(req)) {
            resp.sendRedirect("/");
            return true;
        }
        return false;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        String action = action(req);
        if (p.length == 1 && List.of("login", "register", "forgot").contains(action)) {
            if (redirectIfAuth(req, resp)) return;
            switch (action) {
                case "login" -> renderLogin(req, resp);
                case "register" -> render(req, resp, "register", page("title", "Đăng ký"));
                default -> render(req, resp, "forgot", page("title", "Quên mật khẩu"));
            }
        } else if (p.length == 2 && action.equals("reset")) {
            if (redirectIfAuth(req, resp)) return;
            renderReset(req, resp, p[1]);
        } else {
            render404(req, resp, null);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        String action = action(req);
        if (p.length == 1 && action.equals("logout")) {
            logout(req, resp);
        } else if (p.length == 1 && List.of("login", "register", "forgot").contains(action)) {
            if (redirectIfAuth(req, resp)) return;
            switch (action) {
                case "login" -> login(req, resp);
                case "register" -> register(req, resp);
                default -> forgot(req, resp);
            }
        } else if (p.length == 2 && action.equals("reset")) {
            if (redirectIfAuth(req, resp)) return;
            resetPassword(req, resp, p[1]);
        } else {
            render404(req, resp, null);
        }
    }

    // [GET] /auth/login
    private void renderLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // ?next=/duong-dan → sau khi đăng nhập quay lại đúng trang đó (chỉ chấp nhận đường dẫn nội bộ)
        String next = req.getParameter("next");
        if (next != null && next.startsWith("/") && !next.startsWith("//")) {
            req.getSession().setAttribute("retUrl", next);
        }
        render(req, resp, "login", page(
                "auth", isAuthenticated(req),
                "title", "Đăng nhập",
                "message", "save".equals(req.getParameter("reason"))
                        ? "Đăng nhập để lưu bài viết và đọc lại bất cứ lúc nào." : null));
    }

    // [POST] /auth/login
    private void login(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            String username = req.getParameter("username");
            String password = req.getParameter("password");
            Document user = username == null ? null : User.col().find(eq("username", username)).first();
            // Không tiết lộ là sai tên hay sai mật khẩu
            if (user == null || !Passwords.matches(password != null ? password : "", user.getString("password"))) {
                render(req, resp, 401, "login", page(
                        "has_errors", "Tên đăng nhập hoặc mật khẩu không đúng.",
                        "formData", locals("username", username),
                        "title", "Đăng nhập"));
                return;
            }

            // Lưu session (đổi session id sau khi đăng nhập để chống session fixation)
            HttpSession old = req.getSession();
            Object retUrl = old.getAttribute("retUrl");
            req.changeSessionId();
            HttpSession session = req.getSession();
            user.remove("password");
            session.setAttribute("auth", true);
            session.setAttribute("authUser", user);

            // Quay lại trang người dùng đang xem trước khi bị yêu cầu đăng nhập
            session.removeAttribute("retUrl");
            resp.sendRedirect(retUrl != null ? String.valueOf(retUrl) : "/");
        }, () -> render(req, resp, 500, "login", page("has_errors", "Có lỗi xảy ra, vui lòng thử lại.", "title", "Đăng nhập")));
    }

    // [POST] /auth/register
    private void register(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");
        String confirm = req.getParameter("confirm_password");
        String email = req.getParameter("email");
        String fullName = req.getParameter("full_name");
        Map<String, Object> formData = locals("username", username, "email", email, "full_name", fullName);

        guard(() -> {
            String err = null;
            if (empty(username) || empty(email) || empty(fullName) || empty(password)) err = "Vui lòng điền đầy đủ thông tin.";
            else if (password.length() < 6) err = "Mật khẩu phải có ít nhất 6 ký tự.";
            else if (!password.equals(confirm)) err = "Mật khẩu xác nhận không khớp.";
            else if (User.col().countDocuments(eq("username", username)) > 0) err = "Tên đăng nhập đã tồn tại, hãy chọn tên khác.";
            else if (User.col().countDocuments(eq("email", email.toLowerCase().trim())) > 0) err = "Email này đã được đăng ký.";
            if (err != null) {
                render(req, resp, 400, "register", page("has_errors", err, "formData", formData, "title", "Đăng ký"));
                return;
            }
            User.col().insertOne(User.create(username, Passwords.hash(password), email, fullName, "guest"));
            render(req, resp, "login", page("message", "Đăng ký thành công! Mời bạn đăng nhập.",
                    "formData", locals("username", username), "title", "Đăng nhập"));
        }, () -> render(req, resp, 400, "register", page("has_errors", "Không thể tạo tài khoản lúc này, vui lòng thử lại.",
                "formData", formData, "title", "Đăng ký")));
    }

    // [POST] /auth/logout
    private void logout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession();
        session.setAttribute("auth", false);
        session.removeAttribute("authUser");
        session.removeAttribute("retUrl");
        session.removeAttribute("role");
        resp.sendRedirect("/");
    }

    // [POST] /auth/forgot
    private void forgot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guard(() -> {
            String email = param(req, "email").trim().toLowerCase();
            Document user = User.col().find(eq("email", email)).first();
            if (user == null) {
                render(req, resp, "forgot", page("title", "Quên mật khẩu", "has_errors", "Không tìm thấy tài khoản nào với email này."));
                return;
            }

            String token = JWT.create()
                    .withClaim("id", user.getObjectId("_id").toHexString())
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(System.currentTimeMillis() + 15 * 60 * 1000))
                    .sign(resetSecret(user));
            String resetLink = baseUrl(req) + "/auth/reset/" + token;

            String name = user.getString("full_name") != null && !user.getString("full_name").isEmpty()
                    ? user.getString("full_name") : "bạn";
            Mailer.Result result = Mailer.send(email, "Đặt lại mật khẩu GR6 News",
                    """
                    <div style="font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#222">
                      <h2 style="font-family:Georgia,serif">GR6 <span style="color:#d93025">NEWS</span></h2>
                      <p>Chào %s,</p>
                      <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản <b>%s</b>.</p>
                      <p><a href="%s" style="display:inline-block;background:#111;color:#fff;padding:12px 22px;border-radius:8px;text-decoration:none">Đặt lại mật khẩu</a></p>
                      <p style="color:#777;font-size:13px">Đường dẫn có hiệu lực trong 15 phút và chỉ dùng được một lần. Nếu bạn không yêu cầu, hãy bỏ qua email này.</p>
                    </div>""".formatted(name, user.getString("username"), resetLink),
                    "Chào " + name + ",\nMở đường dẫn sau để đặt lại mật khẩu (hiệu lực 15 phút):\n" + resetLink);

            if (result.ok()) {
                render(req, resp, "forgot", page("title", "Quên mật khẩu",
                        "message", "Đã gửi đường dẫn đặt lại mật khẩu tới email của bạn. Nếu không thấy, hãy kiểm tra thư mục Spam."));
                return;
            }

            System.err.println("❌ Không gửi được email đặt lại mật khẩu:\n   " + result.error());
            if (Env.isDev()) {
                // Môi trường phát triển: vẫn cho đặt lại mật khẩu bằng link hiển thị trực tiếp
                System.out.println("🔗 Link đặt lại mật khẩu (chỉ hiện khi dev): " + resetLink);
                render(req, resp, "forgot", page("title", "Quên mật khẩu",
                        "has_errors", "Máy chủ chưa gửi được email (xem chi tiết ở terminal).", "devLink", resetLink));
                return;
            }
            render(req, resp, "forgot", page("title", "Quên mật khẩu", "has_errors", "Không gửi được email lúc này, vui lòng thử lại sau."));
        }, () -> render(req, resp, 500, "forgot", page("has_errors", "Có lỗi xảy ra, vui lòng thử lại.", "title", "Quên mật khẩu")));
    }

    // [GET] /auth/reset/:token
    private void renderReset(HttpServletRequest req, HttpServletResponse resp, String token) throws IOException {
        Document user = verifyResetToken(token); // kiểm tra trước để báo hết hạn ngay khi mở link
        if (user == null) {
            render(req, resp, "reset", page("has_errors", EXPIRED, "title", "Đặt lại mật khẩu"));
            return;
        }
        render(req, resp, "reset", page("token", token, "title", "Đặt lại mật khẩu"));
    }

    // [POST] /auth/reset/:token
    private void resetPassword(HttpServletRequest req, HttpServletResponse resp, String token) throws IOException {
        guard(() -> {
            String password = req.getParameter("password");
            String confirm = req.getParameter("confirm_password");
            if (password == null || password.length() < 6) {
                render(req, resp, "reset", page("has_errors", "Mật khẩu phải có ít nhất 6 ký tự.", "token", token, "title", "Đặt lại mật khẩu"));
                return;
            }
            if (!password.equals(confirm)) {
                render(req, resp, "reset", page("has_errors", "Mật khẩu xác nhận không khớp.", "token", token, "title", "Đặt lại mật khẩu"));
                return;
            }
            Document user = verifyResetToken(token);
            if (user == null) {
                render(req, resp, "reset", page("has_errors", EXPIRED, "title", "Đặt lại mật khẩu"));
                return;
            }
            User.col().updateOne(eq("_id", user.get("_id")), Updates.set("password", Passwords.hash(password)));
            render(req, resp, "login", page("message", "Đặt lại mật khẩu thành công! Mời bạn đăng nhập.", "title", "Đăng nhập"));
        }, () -> render(req, resp, 500, "reset", page("has_errors", "Có lỗi xảy ra, vui lòng thử lại.", "title", "Đặt lại mật khẩu")));
    }

    /** `${req.protocol}://${req.get("host")}` — đứng sau proxy của Render thì đọc X-Forwarded-Proto */
    private static String baseUrl(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isEmpty()) proto = req.getScheme();
        String host = req.getHeader("Host");
        if (host == null || host.isEmpty()) host = req.getServerName() + ":" + req.getServerPort();
        return proto.split(",")[0].trim() + "://" + host;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
