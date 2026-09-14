package com.loan.util;

import com.lowagie.text.pdf.BaseFont;
import org.springframework.core.io.ClassPathResource;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;



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
     * 重新生成 PDF（将签名/公章嵌入 HTML 模板后重新渲染，确保位置准确）
     * @param htmlContent 包含签名/公章 <img> 标签的 HTML
     * @param outputPath 输出路径
     */
    public static void regeneratePdf(String htmlContent, String outputPath) throws Exception {
        generatePdf(htmlContent, outputPath);
    }


}
