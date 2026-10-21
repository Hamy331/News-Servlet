package com.gr6.news.servlet;

import com.gr6.news.controller.NotificationController;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;

import java.io.IOException;

/** Thay cho routes/notificationRoutes.js */
@WebServlet("/notifications/*")
public class NotificationServlet extends BaseServlet {
    // Mở 1 thông báo: đánh dấu đã đọc rồi chuyển tới trang liên quan
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length != 1) {
            render404(req, resp, null);
            return;
        }
        if (!isAuth(req, resp)) return;
        Document n = null;
        try {
            n = NotificationController.markRead(p[0], myId(req));
        } catch (Exception ignored) {
            // id sai → coi như không có
        }
        String link = n == null ? null : n.getString("link");
        resp.sendRedirect(link != null && !link.isEmpty() ? link : "/");
    }

    // Đánh dấu tất cả đã đọc rồi quay lại trang trước
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (!(p.length == 1 && p[0].equals("read-all"))) {
            render404(req, resp, null);
            return;
        }
        if (!isAuth(req, resp)) return;
        NotificationController.markAllRead(myId(req));
        String back = req.getHeader("Referer");
        resp.sendRedirect(back != null && !back.isEmpty() ? back : "/");
    }
}
