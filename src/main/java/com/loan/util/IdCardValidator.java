package com.loan.util;

public class IdCardValidator {
    /**
     * 简单的身份证合法性校验（18位）
     */
    public static boolean isValid(String idCard) {
        if (idCard == null || idCard.length() != 18) return false;

        // 1. 前17位必须是数字
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(idCard.charAt(i))) return false;
        }

        // 2. 校验位检查 (最后一位可以是X)
        char lastChar = idCard.charAt(17);
        if (!Character.isDigit(lastChar) && lastChar != 'X' && lastChar != 'x') return false;

        // 这里可以进一步加入 GB 11643-1999 的加权因子校验算法
        return true;
    }
}
