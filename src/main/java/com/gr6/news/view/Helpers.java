package com.gr6.news.view;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Options;

import java.text.SimpleDateFormat;
import java.util.*;

/** Các helper */
final class Helpers {

    static void register(Handlebars hb) {
        // "5 phút trước", "2 giờ trước"... dùng cho thông báo & lịch sử
        hb.registerHelper("timeAgo", (Object date, Options o) -> {
            Date d = toDate(date);
            if (d == null) return "";
            long sec = (System.currentTimeMillis() - d.getTime()) / 1000;
            if (sec < 60) return "vừa xong";
            long min = sec / 60;
            if (min < 60) return min + " phút trước";
            long hour = min / 60;
            if (hour < 24) return hour + " giờ trước";
            long day = hour / 24;
            if (day < 30) return day + " ngày trước";
            long month = day / 30;
            if (month < 12) return month + " tháng trước";
            return (month / 12) + " năm trước";
        });

        hb.registerHelper("formatDateTime", (Object date, Options o) -> {
            Date d = toDate(date);
            return d == null ? "" : fmt("HH:mm · dd/MM/yyyy", d);
        });

        hb.registerHelper("eq", (Object v1, Options o) -> jsStrictEquals(v1, o.param(0, null)));

        hb.registerHelper("or", (Object v1, Options o) -> truthy(v1) ? v1 : o.param(0, null));

        hb.registerHelper("limit", (Object arr, Options o) -> {
            if (!(arr instanceof List<?> list)) return List.of();
            Object n = o.param(0, null);
            int limit = n instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(n));
            return list.subList(0, Math.max(0, Math.min(limit, list.size())));
        });

        hb.registerHelper("getFirstLetter", (Object str, Options o) -> {
            if (!truthy(str)) return "U";
            return String.valueOf(str).substring(0, 1).toUpperCase();
        });

        // toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: '2-digit' }) → "Oct 20, 2026"
        hb.registerHelper("formatDate", (Object date, Options o) -> {
            Date d = toDate(date);
            return d == null ? "Unknown Date" : fmt("MMM dd, yyyy", d);
        });

        hb.registerHelper("isEdited", (Object publishedAt, Options o) -> {
            Date pub = toDate(publishedAt), upd = toDate(o.param(0, null));
            if (pub == null || upd == null) return false;
            return upd.getTime() - pub.getTime() > 60000;
        });

        hb.registerHelper("formatType", (Object type, Options o) -> {
            if (!truthy(type)) return "";
            return switch (String.valueOf(type)) {
                case "must_know" -> "MUST KNOW";
                case "hot" -> "HOT NEWS";
                case "normal" -> "ARTICLE";
                case "advertisement" -> "SPONSORED";
                default -> String.valueOf(type).toUpperCase();
            };
        });
    }

    // Múi giờ giống máy chạy Node (đặt TZ=Asia/Ho_Chi_Minh trên server)
    private static String fmt(String pattern, Date d) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(d);
    }

    private static Date toDate(Object v) {
        if (v instanceof Date d) return d;
        if (v instanceof Number n) return new Date(n.longValue());
        return null;
    }

    /** === trong JavaScript: so sánh chuỗi/số/boolean; ObjectId thì so theo chuỗi hex */
    private static boolean jsStrictEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof CharSequence || b instanceof CharSequence) {
            return a instanceof CharSequence && b instanceof CharSequence && a.toString().equals(b.toString());
        }
        return a.equals(b);
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof CharSequence s) return s.length() > 0;
        if (v instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        return true;
    }

    private Helpers() {}
}
