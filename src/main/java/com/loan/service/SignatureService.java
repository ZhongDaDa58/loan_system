package com.loan.service;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.SignatureVO;
import org.springframework.web.multipart.MultipartFile;

public interface SignatureService {

    /**
     * 上传签名图片（Base64或文件）
     */
    Result<SignatureVO> uploadSignature(String userId, String signatureBase64);

    /**
     * 上传签名图片（MultipartFile方式）
     */
    Result<SignatureVO> uploadSignatureFile(String userId, MultipartFile file);

    /**
     * 获取用户默认签名
     */
    Result<SignatureVO> getDefaultSignature(String userId);

    /**
     * 删除签名
     */
    Result<?> deleteSignature(String userId, Long signatureId);
}
