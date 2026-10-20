package com.gr6.news.filter;

import jakarta.servlet.*;

import java.io.IOException;

/** Đọc form / JSON theo UTF-8 (thiếu bước này tiếng Việt gửi lên sẽ lỗi font) */
public class EncodingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        if (req.getCharacterEncoding() == null) req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        chain.doFilter(req, resp);
    }
}
