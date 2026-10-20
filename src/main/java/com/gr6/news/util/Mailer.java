package com.gr6.news.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gr6.news.config.Env;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeBodyPart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 *  1. SendGrid (SENDGRID_API_KEY + EMAIL_FROM là địa chỉ đã xác minh trong SendGrid)
 *  2. SMTP     (SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS — vd. Gmail + App Password)
 * Không ném lỗi ra ngoài.
 */
public final class Mailer {
    public record Result(boolean ok, String via, String error) {}

    private static String from() {
        return Env.get("EMAIL_FROM", Env.get("SMTP_USER", "no-reply@gr6news.local"));
    }

    public static Result send(String to, String subject, String html, String text) {
        List<String> problems = new ArrayList<>();

        String key = Env.get("SENDGRID_API_KEY");
        if (key != null) {
            try {
                JsonObject body = new Gson().fromJson("""
                        {"personalizations":[{"to":[{"email":""}]}],"from":{"email":""},"subject":"",
                         "content":[{"type":"text/plain","value":""},{"type":"text/html","value":""}]}
                        """, JsonObject.class);
                body.getAsJsonArray("personalizations").get(0).getAsJsonObject()
                        .getAsJsonArray("to").get(0).getAsJsonObject().addProperty("email", to);
                body.getAsJsonObject("from").addProperty("email", from());
                body.addProperty("subject", subject);
                body.getAsJsonArray("content").get(0).getAsJsonObject().addProperty("value", text);
                body.getAsJsonArray("content").get(1).getAsJsonObject().addProperty("value", html);

                HttpResponse<String> res = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("https://api.sendgrid.com/v3/mail/send"))
                                .header("Authorization", "Bearer " + key)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) return new Result(true, "sendgrid", null);
                String hint = res.statusCode() == 403
                        ? " → Kiểm tra: (1) EMAIL_FROM=\"" + from() + "\" đã được xác minh trong SendGrid; (2) API key có quyền \"Mail Send\"."
                        : res.statusCode() == 401 ? " → SENDGRID_API_KEY sai hoặc đã bị thu hồi." : "";
                problems.add("SendGrid " + res.statusCode() + ": " + res.body() + hint);
            } catch (Exception e) {
                problems.add("SendGrid: " + e.getMessage());
            }
        }

        String host = Env.get("SMTP_HOST"), user = Env.get("SMTP_USER"), pass = Env.get("SMTP_PASS");
        if (host != null && user != null && pass != null) {
            try {
                int port = Integer.parseInt(Env.get("SMTP_PORT", "587"));
                Properties p = new Properties();
                p.put("mail.smtp.host", host);
                p.put("mail.smtp.port", String.valueOf(port));
                p.put("mail.smtp.auth", "true");
                if (port == 465) p.put("mail.smtp.ssl.enable", "true");
                else p.put("mail.smtp.starttls.enable", "true");
                Session session = Session.getInstance(p, new jakarta.mail.Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                });
                MimeMessage msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(from()));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                msg.setSubject(subject, "UTF-8");
                MimeBodyPart plain = new MimeBodyPart();
                plain.setText(text, "UTF-8");
                MimeBodyPart rich = new MimeBodyPart();
                rich.setContent(html, "text/html; charset=UTF-8");
                msg.setContent(new MimeMultipart("alternative", plain, rich));
                Transport.send(msg);
                return new Result(true, "smtp", null);
            } catch (Exception e) {
                problems.add("SMTP: " + e.getMessage());
            }
        }

        if (problems.isEmpty()) problems.add("Chưa cấu hình SENDGRID_API_KEY hoặc SMTP_HOST/SMTP_USER/SMTP_PASS trong .env");
        return new Result(false, null, String.join("\n   ", problems));
    }

    private Mailer() {}
}
