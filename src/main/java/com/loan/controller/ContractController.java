package com.loan.controller;

import com.loan.entity.LoanApplication;
import com.loan.entity.vo.Result;
import com.loan.service.ContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/v1/contract")
@Tag(name = "电子合同模块", description = "合同生成、签署与存证")
public class ContractController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContractController.class);

    @Resource
    private ContractService contractService;

    /** 阶段四 4.1：用于资源归属校验 */
    @Resource
    private com.loan.service.LoanApplicationService loanApplicationService;

    @Value("${contract.storage.path:D:/code/loan/contracts/}")
    private String contractStoragePath;

    // ==================== 阶段四 4.1：资源归属校验 ====================

    /**
     * 取当前登录用户 ID。
     *
     * <p>从 {@code SecurityContextHolder} 取，而不是给每个方法加 {@code HttpServletRequest} 参数 ——
     * 保持方法签名不变，也更符合 Spring Security 的用法。
     */
    private String currentUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails ud) {
            return ud.getUsername();
        }
        return auth.getName();
    }

    /** 当前用户是否为管理员（管理员可查看任意申请，不受归属限制） */
    private boolean isAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * 归属校验：非管理员访问他人资源时返回 403。
     *
     * @return {@code true} 表示放行（管理员，或这确实是本人的申请）
     */
    private boolean canAccess(String applicationId) {
        if (isAdmin()) {
            return true;
        }
        return loanApplicationService.belongsTo(applicationId, currentUserId());
    }

    @PostMapping("/generate/{applicationId}")
    @Operation(summary = "阶段一：生成待签合同", description = "审核通过后调用，生成无章无签的合同草稿")
    public Result<String> generateDraft(@PathVariable String applicationId) {
        if (!canAccess(applicationId)) {
            throw new com.loan.exception.BusinessException(403, "无权访问该合同");
        }
        return contractService.generateDraftContract(applicationId);
    }

    @PostMapping("/sign/{applicationId}")
    @Operation(summary = "阶段二：用户电子签名", description = "用户在APP确认签署后调用")
    public Result<String> signByUser(@PathVariable String applicationId) {
        if (!canAccess(applicationId)) {
            throw new com.loan.exception.BusinessException(403, "无权签署该合同");
        }
        return contractService.signByUser(applicationId);
    }

    @PostMapping("/stamp/{applicationId}")
    @Operation(summary = "阶段三：平台盖章生效", description = "管理员放款时调用，加盖平台公章")
    public Result<String> stampAndActivate(@PathVariable String applicationId) {
        // 阶段四 4.1：盖章是平台行为，普通用户即使是自己的合同也不能触发
        if (!isAdmin()) {
            throw new com.loan.exception.BusinessException(403, "仅管理员可执行盖章操作");
        }
        return contractService.stampAndActivate(applicationId);
    }

    @GetMapping("/status/{applicationId}")
    @Operation(summary = "查询合同签署状态", description = "返回合同状态及PDF路径")
    public Result<LoanApplication> getContractStatus(@PathVariable String applicationId) {
        // 阶段四 4.1：归属校验 —— 不能查看别人的合同状态
        if (!canAccess(applicationId)) {
            throw new com.loan.exception.BusinessException(403, "无权查看该合同");
        }
        return contractService.getContractStatus(applicationId);
    }

    @GetMapping("/file/{applicationId}")
    @Operation(summary = "下载/预览合同PDF", description = "返回合同PDF文件流，供前端展示或下载")
    public ResponseEntity<byte[]> downloadContract(@PathVariable String applicationId) {
        // 阶段四 4.1：归属校验 —— ★ 这是最敏感的一个接口：不加校验就能下载别人的合同
        if (!canAccess(applicationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Result<LoanApplication> res = contractService.getContractStatus(applicationId);
        if (res == null || res.getData() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        LoanApplication app = res.getData();
        if (app.getContractPath() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            String fileName = app.getContractPath();
            if (fileName.contains("/")) {
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
            }

            File file = new File(contractStoragePath + fileName);
            if (!file.exists() || !file.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            byte[] content = Files.readAllBytes(file.toPath());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentLength(content.length);
            ContentDisposition cd = ContentDisposition.inline().filename(URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString()), StandardCharsets.UTF_8).build();
            headers.setContentDisposition(cd);

            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("合同 PDF 下载失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
