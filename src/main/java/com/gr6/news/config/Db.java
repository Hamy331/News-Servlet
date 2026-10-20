package com.gr6.news.config;
 
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
 
public final class Db {
    private static MongoClient client;
    private static MongoDatabase db;
 
    public static synchronized void connect() {
        if (client != null) return;
        String uri = Env.get("MONGODB_URI");
        if (uri == null) throw new IllegalStateException("Thiếu biến môi trường MONGODB_URI");
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(ClassLoader.getSystemClassLoader());
        try {
            ConnectionString cs = new ConnectionString(uri);
            client = MongoClients.create(cs);
            db = client.getDatabase(cs.getDatabase() != null ? cs.getDatabase() : "test");
            db.runCommand(new Document("ping", 1));
        } finally {
            t.setContextClassLoader(old);
        }
        System.out.println("MongoDB connected successfully");
    }
 
    public static MongoCollection<Document> col(String name) {
        return db.getCollection(name);
    }
 
    public static synchronized void close() {
        if (client != null) client.close();
        client = null;
    }
 
    private Db() {}
}