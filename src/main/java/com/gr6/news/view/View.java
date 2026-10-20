package com.gr6.news.view;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.ValueResolver;
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache;
import com.github.jknack.handlebars.context.JavaBeanValueResolver;
import com.github.jknack.handlebars.context.MapValueResolver;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import com.github.jknack.handlebars.io.CompositeTemplateLoader;
import com.gr6.news.config.Env;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;

public final class View {
    private static Handlebars hb;

    private static final ValueResolver LENGTH = new ValueResolver() {
        @Override
        public Object resolve(Object context, String name) {
            if (!"length".equals(name)) return UNRESOLVED;
            if (context instanceof Collection<?> c) return c.size();
            if (context instanceof CharSequence s) return s.length();
            if (context != null && context.getClass().isArray()) return Array.getLength(context);
            return UNRESOLVED;
        }

        @Override
        public Object resolve(Object context) {
            return UNRESOLVED;
        }

        @Override
        public Set<Map.Entry<String, Object>> propertySet(Object context) {
            return Collections.emptySet();
        }
    };

    private static final ValueResolver[] RESOLVERS = {
            LENGTH, MapValueResolver.INSTANCE, JavaBeanValueResolver.INSTANCE
    };

    public static synchronized void init() {
        if (hb != null) return;
        Handlebars h = new Handlebars(new CompositeTemplateLoader(
                new ClassPathTemplateLoader("/views", ".hbs"),
                new ClassPathTemplateLoader("/views/partials", ".hbs")));
        h.setCharset(java.nio.charset.StandardCharsets.UTF_8);
        h.setPrettyPrint(true);
        if (!Env.isDev()) h = h.with(new ConcurrentMapTemplateCache());
        Helpers.register(h);
        hb = h;
    }

    @SuppressWarnings("unchecked")
    public static void render(HttpServletRequest req, HttpServletResponse resp, String view,
                              Map<String, Object> locals) throws IOException {
        init();
        Map<String, Object> data = new HashMap<>();
        Object globals = req.getAttribute(GlobalLocals.ATTR);
        if (globals instanceof Map<?, ?> g) data.putAll((Map<String, Object>) g);
        if (locals != null) data.putAll(locals);

        String html = renderToString(view, data);
        Object layout = data.get("layout");
        if (layout == null) layout = "main";
        if (!Boolean.FALSE.equals(layout)) {
            data.put("body", html);
            html = renderToString("layouts/" + layout, data);
        }
        if (resp.getContentType() == null) resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().write(html);
    }

    private static String renderToString(String template, Map<String, Object> data) throws IOException {
        Context ctx = Context.newBuilder(data).resolver(RESOLVERS).build();
        try {
            return hb.compile(template).apply(ctx);
        } finally {
            ctx.destroy();
        }
    }

    private View() {}
}
