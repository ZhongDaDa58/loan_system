package com.loan.util;
import java.util.UUID;

public class IdUtil {
    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase();
    }

    // Generate product id with prefix product_ e.g. product_5f2e9a3b7c1d
    public static String generateProductId() {
        String s = UUID.randomUUID().toString().replace("-", "").toLowerCase();
        return "product_" + s.substring(0, 12);
    }
}