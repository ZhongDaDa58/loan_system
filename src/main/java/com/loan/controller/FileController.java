package com.loan.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/v1/file")
@Tag(name = "文件服务模块", description = "图片/文件流访问")
public class FileController {

    @Value("${app.file.upload-dir:uploads}")
    private String uploadDir;

    @GetMapping("/image")
    @Operation(summary = "获取图片文件流")
    public ResponseEntity<Resource> getImage(@RequestParam String path) {
        if (path == null || path.startsWith("..")) {
            return ResponseEntity.badRequest().build();
        }

        // 清理路径前缀
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        String fullPath = Paths.get(uploadDir, cleanPath).toString();
        File file = new File(fullPath);

        // 调试：打印实际查找的路径
        System.out.println("🔍 FileController 查找文件: " + file.getAbsolutePath() + " 是否存在: " + file.exists());

        if (!file.exists()) {
            // 第二次尝试：检查项目根目录下的绝对路径
            File altFile = new File(System.getProperty("user.dir"), fullPath);
            System.out.println("🔍 备选路径: " + altFile.getAbsolutePath() + " 是否存在: " + altFile.exists());
            if (altFile.exists()) {
                file = altFile;
            } else {
                return ResponseEntity.notFound().build();
            }
        }

        MediaType mediaType = getMediaType(path);
        FileSystemResource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(resource);
    }

    private MediaType getMediaType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
