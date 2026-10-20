package com.gr6.news.util;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;

/** bcrypt giống bcryptjs: cost 10, đọc được hash $2a$ / $2b$ / $2y$ do bản Node tạo ra */
public final class Passwords {
    private static final BCrypt.Hasher HASHER =
            BCrypt.with(BCrypt.Version.VERSION_2A, LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2A));

    public static String hash(String password) {
        return HASHER.hashToString(10, password.toCharArray());
    }

    public static boolean matches(String password, String hash) {
        if (password == null || hash == null) return false;
        try {
            return BCrypt.verifyer(null, LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2A))
                    .verify(password.toCharArray(), hash).verified;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Passwords() {}
}
