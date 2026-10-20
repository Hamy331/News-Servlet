package com.gr6.news.model;

import org.bson.types.ObjectId;

import java.util.*;

/** Chuyển chuỗi ↔ ObjectId */
public final class Ids {
    /** Chuỗi không hợp lệ → null*/
    public static ObjectId oid(Object v) {
        if (v == null) return null;
        if (v instanceof ObjectId o) return o;
        String s = String.valueOf(v).trim();
        return ObjectId.isValid(s) ? new ObjectId(s) : null;
    }

    public static List<ObjectId> oids(Collection<?> values) {
        List<ObjectId> out = new ArrayList<>();
        if (values == null) return out;
        for (Object v : values) {
            ObjectId o = oid(v);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** Lấy _id của một giá trị đã populate (Document) hoặc chưa (ObjectId) */
    public static String idOf(Object v) {
        if (v == null) return "";
        if (v instanceof org.bson.Document d) return String.valueOf(d.get("_id"));
        return String.valueOf(v);
    }

    public static boolean same(Object a, Object b) {
        return a != null && b != null && idOf(a).equals(idOf(b));
    }

    private Ids() {}
}
