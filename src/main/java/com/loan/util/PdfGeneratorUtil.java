package com.loan.util;

import com.lowagie.text.pdf.BaseFont;
import org.springframework.core.io.ClassPathResource;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;

public class PdfGeneratorUtil {

    /**
     * 将 HTML 内容转换为 PDF 文件
     * @param htmlContent HTML 字符串
     * @param outputPath 输出 PDF 的路径
     */
    public static void generatePdf(String htmlContent, String outputPath) throws Exception {
        // 1. 创建渲染器
        ITextRenderer renderer = new ITextRenderer();

        // 2. 设置中文字体 (关键步骤)
        ITextFontResolver fontResolver = renderer.getFontResolver();
        // 从 resources/fonts 目录下加载宋体
        try {
            ClassPathResource fontResource = new ClassPathResource("fonts/simsun.ttc");
            System.out.println("🔍 字体文件绝对路径: " + fontResource.getFile().getAbsolutePath());

            // ⭐ 尝试使用 STSong-Light (iText 内置的中文字体支持) 作为备选
            // 如果 simsun.ttc 依然不行，我们可以改用这种方式
            fontResolver.addFont(fontResource.getFile().getAbsolutePath(),
                    BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED);
            System.out.println("✅ 字体加载指令已发送");
        } catch (Exception e) {
            System.err.println("❌ 字体加载失败: " + e.getMessage());
            e.printStackTrace();
        }

        // 3. 解析 HTML
        renderer.setDocumentFromString(htmlContent);

        // 4. 布局计算
        renderer.layout();

        // 5. 生成 PDF 文件
        File outputFile = new File(outputPath);
        if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
        }

        try (OutputStream os = new FileOutputStream(outputFile)) {
            renderer.createPDF(os);
        }
        System.out.println("✅ PDF 文件已生成: " + outputFile.getAbsolutePath());

    }
    /**
     * 在已有的 PDF 上添加签名和公章图片
     * @param pdfPath PDF 文件路径
     * @param signatureBase64 用户签名 Base64
     * @param stampPath 平台公章图片路径 (resources/stamps/company_stamp.png)
     * @param outputPath 输出路径
     */
    public static void addSignatures(String pdfPath, String signatureBase64, String stampPath, String outputPath) throws Exception {
        // ⭐ 1. 创建一个临时文件用于输出，避免覆盖正在读取的原文件
        File originalFile = new File(pdfPath);
        File tempFile = new File(pdfPath + ".tmp");

        PdfReader reader = new PdfReader(pdfPath);
        FileOutputStream fos = new FileOutputStream(tempFile);
        PdfStamper stamper = new PdfStamper(reader, fos);

        try {
            // 2. 处理用户签名
            if (signatureBase64 != null && !signatureBase64.isEmpty()) {
                try {
                    String base64Data = signatureBase64.contains(",") ? signatureBase64.split(",")[1] : signatureBase64;
                    byte[] signatureBytes = java.util.Base64.getDecoder().decode(base64Data.replaceAll("\\s+", ""));

                    Image signImage = Image.getInstance(signatureBytes);
                    signImage.scaleToFit(120, 60);
                    signImage.setAbsolutePosition(50, 80);
                    stamper.getOverContent(1).addImage(signImage);
                } catch (Exception e) {
                    System.err.println("⚠️ 签名处理跳过: " + e.getMessage());
                }
            }

            // 3. 处理平台公章
            if (stampPath != null) {
                ClassPathResource stampResource = new ClassPathResource(stampPath);
                if (stampResource.exists()) {
                    Image stampImage = Image.getInstance(stampResource.getFile().getAbsolutePath());
                    stampImage.scaleToFit(80, 80);
                    stampImage.setAbsolutePosition(450, 80);
                    stamper.getOverContent(1).addImage(stampImage);
                }
            }
        } finally {
            // ⭐ 确保按顺序关闭所有资源
            try { stamper.close(); } catch (Exception e) { e.printStackTrace(); }
            try { reader.close(); } catch (Exception e) { e.printStackTrace(); }
            try { fos.close(); } catch (Exception e) { e.printStackTrace(); }
        }

        // ⭐ 4. 用临时文件替换原文件 (增加重试和强制删除)
        boolean success = false;
        for (int i = 0; i < 3; i++) {
            if (originalFile.delete()) {
                success = tempFile.renameTo(originalFile);
                break;
            }
            // 如果删除失败，等待一下再试，给系统一点释放句柄的时间
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        if (!success) {
            // 如果还是失败，尝试强制删除并抛出更详细的错误
            originalFile.deleteOnExit(); // 注册退出时删除
            throw new Exception("无法更新合同文件，请检查是否被其他程序占用。临时文件位于: " + tempFile.getAbsolutePath());
        }

    }


}
