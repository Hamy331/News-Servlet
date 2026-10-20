package com.gr6.news.config;

import com.gr6.news.view.View;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/** Khởi động: kết nối DB và nạp view engine khi app chạy */
@WebListener
public class AppListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent e) {
        Db.connect();
        View.init();
        System.out.println("✅ Server running");
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        Db.close();
    }
}
