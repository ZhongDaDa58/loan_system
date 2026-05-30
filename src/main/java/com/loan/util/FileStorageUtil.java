// FileStorageUtil.java（工具类）
package com.loan.util;

import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

@Component
public class FileStorageUtil {

    private static final String SIGNATURE_DIR = "src/main/resources/static/signatures/";
    private static final String CONTRACT_DIR = "src/main/resources/static/contracts/";

    /**
     * 保存签名图片
     * @param base64Image Base64编码的图片
     * @return 相对路径（如：/signatures/20240505/uuid.png）
     */
    public String saveSignature(String base64Image) throws IOException {
        // 1. 解析Base64
        byte[] imageBytes = decodeBase64(base64Image);

        // 2. 生成文件名
        String fileName = UUID.randomUUID().toString() + ".png";
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String fullPath = SIGNATURE_DIR + dateDir + "/";

        // 3. 创建目录
        File dir = new File(fullPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 4. 保存文件
        Path filePath = Paths.get(fullPath + fileName);
        Files.write(filePath, imageBytes);

        // 5. 返回访问URL
        return "/signatures/" + dateDir + "/" + fileName;
    }

    private byte[] decodeBase64(String base64) {
        // 1. 移除可能存在的 Data URI 前缀 (如 data:image/png;base64,)
        if (base64.contains(",")) {
            base64 = base64.split(",")[1];
        }

        // 2. ⭐ 关键修复：在某些传输过程中，'+' 可能会被替换为空格 ' '
        //    我们需要把它换回来，否则解码器会识别为非法字符或错误数据
        base64 = base64.replace(' ', '+');

        // 3. 移除其他所有空白字符（换行符 \n, \r 等）
        base64 = base64.replaceAll("[\\t\\n\\r]", "");

        // 4. 处理末尾填充符 '='
        while (base64.length() % 4 != 0) {
            base64 += "=";
        }

        // 5. 尝试解码
        try {
            return java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            // 如果依然失败，记录一下前 100 个字符以便调试
            System.err.println("Base64 解码失败，开头部分: " + base64.substring(0, Math.min(100, base64.length())));
            throw new IllegalArgumentException("Base64 格式严重错误，无法解码", e);
        }
    }
    /**
     * ⭐ 新增：将签名图片文件转换为 Base64 字符串
     * @param imagePath 图片相对路径 (如 /signatures/xxx.png)
     * @return 带前缀的 Base64 字符串
     */
    public String getSignatureBase64(String imagePath) throws Exception {
        if (imagePath == null || imagePath.isEmpty()) {
            return null;
        }

        // 假设图片存储在 static 目录下，根据你实际的路径配置调整
        String fullPath = "src/main/resources/static" + imagePath;
        File file = new File(fullPath);

        if (!file.exists()) {
            throw new Exception("签名文件不存在: " + fullPath);
        }

        byte[] fileContent = Files.readAllBytes(file.toPath());
        String base64String = Base64.getEncoder().encodeToString(fileContent);

        // 根据文件后缀添加 MIME 类型前缀
        String prefix = "data:image/png;base64,";
        if (imagePath.toLowerCase().endsWith(".jpg") || imagePath.toLowerCase().endsWith(".jpeg")) {
            prefix = "data:image/jpeg;base64,";
        }

        return prefix + base64String;
    }
}
