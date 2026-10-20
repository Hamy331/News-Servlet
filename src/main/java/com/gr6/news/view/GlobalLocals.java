package com.gr6.news.view;

import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;

/** Tương đương res.locals: biến dùng chung cho mọi view trong một request */
public final class GlobalLocals {
    public static final String ATTR = "res.locals";

    @SuppressWarnings("unchecked")
    public static Map<String, Object> of(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Map<String, Object> m = new HashMap<>();
        req.setAttribute(ATTR, m);
        return m;
    }

    private GlobalLocals() {}
}
