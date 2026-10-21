package com.gr6.news.servlet;

import com.gr6.news.controller.TagController;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet(urlPatterns = {"/tag", "/tag/*"})
public class TagServlet extends BaseServlet {
    // Trang danh sách theo tag của bản Node render view "listTag" không có trong views/ → luôn lỗi.
    // FE đang tìm theo tag qua /search?q=..., nên ở đây trả 404.
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        render404(req, resp, null);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (p.length == 0) {
            if (!isAuth(req, resp) || !verifyRole(req, resp, "administrator")) return;
            guard(() -> {
                TagController.createTag(req.getParameter("tagName"));
                resp.sendRedirect("/administrator");
            }, () -> sendText(resp, 500, "Error creating tag"));
        } else if (p.length == 2 && p[0].equals("delete")) {
            if (!isAuth(req, resp) || !verifyRole(req, resp, "administrator")) return;
            guard(() -> {
                TagController.deleteTag(p[1]);
                resp.sendRedirect("/administrator");
            }, () -> sendText(resp, 500, "Error deleting tag"));
        } else {
            render404(req, resp, null);
        }
    }
}
