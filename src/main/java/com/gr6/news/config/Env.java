package com.gr6.news.config;

import io.github.cdimascio.dotenv.Dotenv;

/** Đọc cấu hình: biến môi trường (Render) ưu tiên, sau đó tới file .env */
public final class Env {
    private static final Dotenv DOTENV = Dotenv.configure()
            .directory(System.getProperty("app.envDir", "."))
            .ignoreIfMissing()
            .load();

    public static String get(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) v = DOTENV.get(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    public static String get(String key, String fallback) {
        String v = get(key);
        return v != null ? v : fallback;
    }

    public static boolean isDev() {
        return !"production".equals(get("NODE_ENV", get("APP_ENV", "")));
    }

    private Env() {}
}
