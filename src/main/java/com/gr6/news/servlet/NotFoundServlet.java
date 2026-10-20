package com.gr6.news.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** utils/notFound.js: mọi đường dẫn không khớp route nào → trang 404 (web.xml chuyển lỗi 404 tới đây) */
@WebServlet("/__not-found")
public class NotFoundServlet extends BaseServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        render404(req, resp, null);
    }
}
