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

        // 1. 准备 Thymeleaf 上下文
        Context context = new Context();
        context.setVariable("contractNo", "LOAN-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + applicationId.substring(0, 4));
        context.setVariable("borrowerName", identity.getUserRealName());
        context.setVariable("idCard", identity.getIdCardNumber());
        context.setVariable("amount", application.getApplyAmount());
        context.setVariable("term", application.getApplyTerm());
        context.setVariable("rate", "12.5"); // 实际应从产品表获取
        context.setVariable("signDate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));

        // 2. 渲染 HTML 并生成基础 PDF
        String htmlContent = templateEngine.process("contract_template", context);
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
            if (signature != null) {
                signatureBase64 = fileStorageUtil.getSignatureBase64(signature.getSignatureImageUrl());
            }

            // 2. 在草稿 PDF 上添加用户签名 (不传公章路径)
            String sourcePath = storagePath + application.getContractPath().replace("/contracts/", "");
            PdfGeneratorUtil.addSignatures(sourcePath, signatureBase64, null, sourcePath);

            // 3. 更新状态为“已签署” (2) 并记录时间
            loanApplicationMapper.updateContractStatus(applicationId, 2);
            // 可以在 Mapper 中增加一个 updateSignTime 方法

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
            String sourcePath = storagePath + application.getContractPath().replace("/contracts/", "");

            // 1. 在已签名的 PDF 上添加平台公章
            String stampPath = "stamps/company_stamp.png";
            PdfGeneratorUtil.addSignatures(sourcePath, null, stampPath, sourcePath);

            // 2. 计算最终法律效力 Hash 并更新状态为“已生效” (3)
            String finalHash = calculateFileHash(new File(sourcePath));
            loanApplicationMapper.updateContractHash(applicationId, finalHash);
            loanApplicationMapper.updateContractStatus(applicationId, 3);

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

    private String calculateFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] hashBytes = digest.digest(fileBytes);
        return HexFormat.of().formatHex(hashBytes);
    }
}
