package com.gr6.news.model;

import com.gr6.news.config.Db;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.*;

public final class Populate {

    public static void apply(List<Document> docs, String path, String collection) {
        apply(docs, path, collection, null, null);
    }

    public static void apply(List<Document> docs, String path, String collection, String select) {
        apply(docs, path, collection, select, null);
    }

    /**
     * @param select tên trường cách nhau bởi dấu cách (như mongoose), null = lấy hết (trừ password)
     * @param match  điều kiện thêm; document không khớp → null (hoặc bị loại khỏi mảng)
     */
    public static void apply(List<Document> docs, String path, String collection, String select, Bson match) {
        if (docs == null || docs.isEmpty()) return;
        String[] segs = path.split("\\.");
        String key = segs[segs.length - 1];

        List<Document> holders = new ArrayList<>();
        for (Document d : docs) collect(d, segs, 0, holders);

        Set<ObjectId> ids = new LinkedHashSet<>();
        for (Document h : holders) {
            Object v = h.get(key);
            if (v instanceof List<?> list) list.forEach(x -> addId(ids, x));
            else addId(ids, v);
        }
        if (ids.isEmpty()) {
            for (Document h : holders) if (h.containsKey(key) && !(h.get(key) instanceof List)) h.put(key, null);
            return;
        }

        Bson filter = Filters.in("_id", ids);
        if (match != null) filter = Filters.and(filter, match);
        var query = Db.col(collection).find(filter);
        if (select != null && !select.isBlank()) {
            query = query.projection(Projections.include(select.trim().split("\\s+")));
        } else if ("users".equals(collection)) {
            query = query.projection(Projections.exclude("password"));
        }
        Map<String, Document> found = new HashMap<>();
        for (Document d : query) found.put(d.getObjectId("_id").toHexString(), d);

        for (Document h : holders) {
            Object v = h.get(key);
            if (v instanceof List<?> list) {
                List<Object> out = new ArrayList<>();
                for (Object x : list) {
                    Document f = found.get(Ids.idOf(x));
                    if (f != null) out.add(f);
                }
                h.put(key, out);
            } else if (v != null) {
                h.put(key, found.get(Ids.idOf(v)));
            }
        }
    }

    public static void apply(Document doc, String path, String collection, String select) {
        if (doc != null) apply(List.of(doc), path, collection, select, null);
    }

    public static void apply(Document doc, String path, String collection) {
        apply(doc, path, collection, null);
    }

    private static void addId(Set<ObjectId> ids, Object v) {
        ObjectId o = v instanceof Document d ? d.getObjectId("_id") : Ids.oid(v);
        if (o != null) ids.add(o);
    }

    private static void collect(Object node, String[] segs, int i, List<Document> out) {
        if (node instanceof List<?> list) {
            for (Object o : list) collect(o, segs, i, out);
            return;
        }
        if (!(node instanceof Document d)) return;
        if (i == segs.length - 1) {
            out.add(d);
            return;
        }
        collect(d.get(segs[i]), segs, i + 1, out);
    }

    private Populate() {}
}
