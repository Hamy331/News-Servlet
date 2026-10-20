package com.gr6.news.util;

import java.util.*;

public final class Youtube {
    /** convertToEmbedUrls: lấy phần sau dấu "=" hoặc "/" cuối cùng rồi ghép vào link embed */
    public static List<String> toEmbedUrls(List<?> urls) {
        List<String> out = new ArrayList<>();
        if (urls == null) return out;
        for (Object o : urls) {
            String str = String.valueOf(o);
            String videoId = "";
            for (int i = str.length() - 1; i >= 0; i--) {
                if (str.charAt(i) == '=' || str.charAt(i) == '/') {
                    videoId = str.substring(i + 1);
                    break;
                }
            }
            out.add("https://www.youtube.com/embed/" + videoId);
        }
        return out;
    }

    private Youtube() {}
}
