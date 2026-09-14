package com.loan.service.impl;

import com.loan.entity.LoanApplication;
import com.loan.entity.UserIdentity;
import com.loan.entity.UserSignature;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.UserIdentityMapper;
import com.loan.mapper.UserSignatureMapper;
import com.loan.service.ContractService;
import com.loan.util.FileStorageUtil;
import com.loan.util.PdfGeneratorUtil;
import org.springframework.core.io.ClassPathResource;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
public class ContractServiceImpl implements ContractService {

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    @Resource
    private UserSignatureMapper userSignatureMapper;

    @Resource
    private TemplateEngine templateEngine;

    @Resource
    private FileStorageUtil fileStorageUtil;

    @Value("${contract.storage.path:./contracts/}")
    private String storagePath;

    @Override
    public Result<String> generateDraftContract(String applicationId) {
        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            return Result.error(400, "贷款申请不存在");
        }

        UserIdentity identity = userIdentityMapper.selectByUserId(application.getUserId());
        if (identity == null) {
            return Result.error(400, "用户未完成实名认证");
        }

        // 1. 生成 HTML（草稿不含签名和公章，日期为空）
        String htmlContent = buildContractHtml(application, null, null);
        String fileName = "draft_" + applicationId + ".pdf";
        File outputFile = new File(storagePath + fileName);

        try {
            PdfGeneratorUtil.generatePdf(htmlContent, outputFile.getAbsolutePath());

            // 3. 计算初始 Hash 并更新数据库状态为“待签署” (1)
            String hash = calculateFileHash(outputFile);
            loanApplicationMapper.updateContractInfo(applicationId, "/contracts/" + fileName, hash);
            loanApplicationMapper.updateContractStatus(applicationId, 1);

            return Result.success("/contracts/" + fileName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException(500, "合同草稿生成失败: " + e.getMessage());
        }
    }

    @Override
    public Result<String> signByUser(String applicationId) {
        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null || application.getContractPath() == null) {
            return Result.error(400, "合同尚未生成，请稍后重试");
        }

        try {
            // 1. 获取用户默认签名
            UserSignature signature = userSignatureMapper.selectDefaultByUserId(application.getUserId());
            String signatureBase64 = null;
            if (signature != null && signature.getSignatureImageUrl() != null) {
                signatureBase64 = fileStorageUtil.getSignatureBase64(signature.getSignatureImageUrl());
            }

            // 2. 重新渲染 HTML（嵌入签名图片，排版引擎自动定位到签字栏横线上方）
            String signDateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            String fileName = application.getContractPath().replace("/contracts/", "");
            String htmlContent = buildContractHtml(application, signatureBase64, null,
                    signDateStr, null);
            String filePath = storagePath + fileName;
            PdfGeneratorUtil.regeneratePdf(htmlContent, filePath);

            // 3. 更新状态为"已签署"，记录签署时间
            loanApplicationMapper.updateContractStatus(applicationId, 2);
            loanApplicationMapper.updateSignTime(applicationId, new java.util.Date());

            return Result.success("签署成功");
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException(500, "用户签名处理失败: " + e.getMessage());
        }
    }

    @Override
    public Result<String> stampAndActivate(String applicationId) {
        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            return Result.error(400, "申请不存在");
        }

        try {
            String fileName = application.getContractPath().replace("/contracts/", "");
            String filePath = storagePath + fileName;

            // 1. 加载公章图片为 Base64
            ClassPathResource stampResource = new ClassPathResource("stamps/company_stamp.png");
            byte[] stampBytes = java.nio.file.Files.readAllBytes(stampResource.getFile().toPath());
            String stampBase64 = "data:image/png;base64," +
                    java.util.Base64.getEncoder().encodeToString(stampBytes);

            // 2. 重新获取用户签名（保持签名不丢失）
            UserSignature signature = userSignatureMapper.selectDefaultByUserId(application.getUserId());
            String signatureBase64 = null;
            if (signature != null && signature.getSignatureImageUrl() != null) {
                signatureBase64 = fileStorageUtil.getSignatureBase64(signature.getSignatureImageUrl());
            }

            // 3. 重新渲染 HTML（签名+公章都由排版引擎自动定位）
            String signDateStr = application.getSignTime() != null
                    ? new java.text.SimpleDateFormat("yyyy年MM月dd日").format(application.getSignTime())
                    : null;
            String stampDateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            String htmlContent = buildContractHtml(application, signatureBase64, stampBase64,
                    signDateStr, stampDateStr);
            PdfGeneratorUtil.regeneratePdf(htmlContent, filePath);

            // 4. 计算 Hash 并更新状态
            String finalHash = calculateFileHash(new File(filePath));
            loanApplicationMapper.updateContractHash(applicationId, finalHash);
            loanApplicationMapper.updateContractStatus(applicationId, 3);
            loanApplicationMapper.updateStampTime(applicationId, new java.util.Date());

            return Result.success("平台盖章成功，合同已生效");
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException(500, "平台盖章失败: " + e.getMessage());
        }
    }
    @Override
    public Result<LoanApplication> getContractStatus(String applicationId) {
        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            return Result.error(400, "申请不存在");
        }

        // 返回完整的对象，前端可以根据 contractStatus 字段判断进度
        return Result.success(application);
    }

    /**
     * 构建合同 HTML（可嵌入签名和公章图片）
     */
    private String buildContractHtml(LoanApplication application,
                                      String signatureBase64, String stampBase64) {
        return buildContractHtml(application, signatureBase64, stampBase64, null, null);
    }

    private String buildContractHtml(LoanApplication application,
                                      String signatureBase64, String stampBase64,
                                      String signDate, String stampDate) {
        UserIdentity identity = userIdentityMapper.selectByUserId(application.getUserId());

        String now = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        Context context = new Context();
        context.setVariable("contractNo", "LOAN-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + application.getApplicationId().substring(0, 4));
        context.setVariable("borrowerName", identity != null ? identity.getUserRealName() : "");
        context.setVariable("idCard", identity != null ? identity.getIdCardNumber() : "");
        context.setVariable("amount", application.getApplyAmount());
        context.setVariable("term", application.getApplyTerm());
        context.setVariable("rate", "12.5");
        context.setVariable("signDate", signDate != null ? signDate : "");
        context.setVariable("stampDate", stampDate != null ? stampDate : "");
        context.setVariable("signatureImage", signatureBase64);  // 嵌入签名
        context.setVariable("stampImage", stampBase64);          // 嵌入公章

        return templateEngine.process("contract_template", context);
    }

    private String calculateFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] hashBytes = digest.digest(fileBytes);
        return HexFormat.of().formatHex(hashBytes);
    }
}
