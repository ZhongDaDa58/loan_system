package com.loan.controller;

import com.loan.entity.dto.SignatureUploadDTO;
import com.loan.entity.vo.Result;
import com.loan.service.SignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/signature")
@Tag(name = "电子签名模块", description = "签名上传、查询、删除")
public class SignatureController {

    @Resource
    private SignatureService signatureService;

    /**
     * 上传签名（Base64方式）
     */
    @PostMapping("/upload")
    @Operation(summary = "上传签名", description = "上传Base64编码的签名图片")
    public Result<?> uploadSignature(@Valid @RequestBody SignatureUploadDTO uploadDTO,
                                     HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        // ⭐ 这里传入的是纯 Base64 字符串（带前缀）
        return signatureService.uploadSignature(userId, uploadDTO.getSignatureImage());
    }

    /**
     * 上传签名（文件方式）
     */
    @PostMapping("/upload-file")
    @Operation(summary = "上传签名文件", description = "上传签名图片文件")
    public Result<?> uploadSignatureFile(@RequestParam("file") MultipartFile file,
                                         HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return signatureService.uploadSignatureFile(userId, file);
    }

    /**
     * 获取默认签名
     */
    @GetMapping("/default")
    @Operation(summary = "获取默认签名", description = "获取当前用户的默认签名")
    public Result<?> getDefaultSignature(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return signatureService.getDefaultSignature(userId);
    }

    /**
     * 删除签名
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除签名", description = "删除指定的签名记录")
    public Result<?> deleteSignature(@PathVariable Long id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return signatureService.deleteSignature(userId, id);
    }
}
