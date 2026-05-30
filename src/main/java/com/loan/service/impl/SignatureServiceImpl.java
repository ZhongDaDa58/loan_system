package com.loan.service.impl;

import com.loan.entity.UserSignature;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.SignatureVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.UserSignatureMapper;
import com.loan.service.SignatureService;
import com.loan.util.FileStorageUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class SignatureServiceImpl implements SignatureService {

    private static final Logger log = LoggerFactory.getLogger(SignatureServiceImpl.class);

    @Resource
    private UserSignatureMapper userSignatureMapper;

    @Resource
    private FileStorageUtil fileStorageUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<SignatureVO> uploadSignature(String userId, String signatureBase64) {
        log.info("🖊️ 用户上传签名: userId={}", userId);

        try {
            // 1. 保存签名图片
            String imageUrl = fileStorageUtil.saveSignature(signatureBase64);

            // 2. 计算哈希值
            String hash = calculateHash(signatureBase64);

            // 3. ⭐ 修改这里：取消旧的默认签名（如果存在）
            userSignatureMapper.cancelDefaultSignature(userId);

            // 4. 创建新签名记录并设为默认
            UserSignature signature = new UserSignature();
            signature.setUserId(userId);
            signature.setSignatureImageUrl(imageUrl);
            signature.setSignatureHash(hash);
            signature.setIsDefault(1); // 新上传的自动成为默认

            int rows = userSignatureMapper.insert(signature);
            if (rows != 1) {
                throw new BusinessException(500, "签名保存失败");
            }

            log.info("✅ 用户 {} 签名上传成功: {}", userId, imageUrl);
            return Result.success(convertToVO(signature));

        } catch (IOException e) {
            log.error("❌ 签名图片保存失败", e);
            throw new BusinessException(500, "签名图片保存失败: " + e.getMessage());
        }
    }

    @Override
    public Result<SignatureVO> uploadSignatureFile(String userId, MultipartFile file) {
        log.info("🖊️ 用户上传签名文件: userId={}", userId);

        if (file.isEmpty()) {
            throw new BusinessException(400, "签名文件不能为空");
        }

        try {
            // 1. 将文件转为Base64
            byte[] bytes = file.getBytes();
            String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);

            // 2. 调用上面的方法
            return uploadSignature(userId, base64);

        } catch (IOException e) {
            log.error("❌ 签名文件读取失败", e);
            throw new BusinessException(500, "签名文件处理失败: " + e.getMessage());
        }
    }

    @Override
    public Result<SignatureVO> getDefaultSignature(String userId) {
        log.info("🔍 获取用户默认签名: userId={}", userId);

        UserSignature signature = userSignatureMapper.selectDefaultByUserId(userId);
        if (signature == null) {
            throw new BusinessException(404, "您还未创建签名，请先上传签名");
        }

        SignatureVO vo = convertToVO(signature);
        return Result.success(vo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deleteSignature(String userId, Long signatureId) {
        log.info("🗑️ 删除签名: userId={}, signatureId={}", userId, signatureId);

        // 1. 查询签名是否存在且属于当前用户
        UserSignature signature = userSignatureMapper.selectDefaultByUserId(userId);
        if (signature == null || !signature.getId().equals(signatureId)) {
            throw new BusinessException(404, "签名不存在或无权删除");
        }

        // 2. 如果是默认签名，不允许删除（应先设置新的默认签名）
        if (signature.getIsDefault() == 1) {
            throw new BusinessException(400, "不能删除默认签名，请先设置其他签名为默认");
        }

        // 3. 删除签名记录
        int rows = userSignatureMapper.deleteById(signatureId);
        if (rows != 1) {
            throw new BusinessException(500, "删除失败");
        }

        log.info("✅ 用户 {} 删除签名成功: {}", userId, signatureId);
        return Result.success("签名删除成功");
    }

    /**
     * 计算哈希值
     */
    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法不存在", e);
            return "";
        }
    }

    /**
     * 实体转VO
     */
    private SignatureVO convertToVO(UserSignature signature) {
        SignatureVO vo = new SignatureVO();
        vo.setId(signature.getId());
        vo.setSignatureImageUrl(signature.getSignatureImageUrl());
        vo.setIsDefault(signature.getIsDefault());
        vo.setCreateTime(signature.getCreateTime());
        return vo;
    }
}
