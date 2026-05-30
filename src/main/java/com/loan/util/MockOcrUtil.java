package com.loan.util;

import java.util.HashMap;
import java.util.Map;

public class MockOcrUtil {
    /**
     * 模拟 OCR 识别身份证图片
     * @param isFront true为正面, false为反面
     * @return 识别出的信息
     */
    public static Map<String, String> recognize(boolean isFront) {
        Map<String, String> result = new HashMap<>();
        if (isFront) {
            // 模拟返回一个固定的测试数据，实际项目中这里会调用百度/阿里OCR API
            result.put("name", "张三");
            result.put("idNumber", "110101199001011234");
            result.put("address", "北京市东城区某某街道1号");
        } else {
            result.put("authority", "北京市公安局东城分局");
            result.put("validDate", "2020.01.01-2030.01.01");
        }
        return result;
    }
}
