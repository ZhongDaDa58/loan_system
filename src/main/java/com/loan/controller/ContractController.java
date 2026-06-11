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

    @Resource
    private ContractService contractService;

    @Value("${contract.storage.path:D:/code/loan/contracts/}")
    private String contractStoragePath;

    @PostMapping("/generate/{applicationId}")
    @Operation(summary = "阶段一：生成待签合同", description = "审核通过后调用，生成无章无签的合同草稿")
    public Result<String> generateDraft(@PathVariable String applicationId) {
        return contractService.generateDraftContract(applicationId);
    }

    @PostMapping("/sign/{applicationId}")
    @Operation(summary = "阶段二：用户电子签名", description = "用户在APP确认签署后调用")
    public Result<String> signByUser(@PathVariable String applicationId) {
        return contractService.signByUser(applicationId);
    }

    @PostMapping("/stamp/{applicationId}")
    @Operation(summary = "阶段三：平台盖章生效", description = "管理员放款时调用，加盖平台公章")
    public Result<String> stampAndActivate(@PathVariable String applicationId) {
        return contractService.stampAndActivate(applicationId);
    }

    @GetMapping("/status/{applicationId}")
    @Operation(summary = "查询合同签署状态", description = "返回合同状态及PDF路径")
    public Result<LoanApplication> getContractStatus(@PathVariable String applicationId) {
        return contractService.getContractStatus(applicationId);
    }

    @GetMapping("/file/{applicationId}")
    @Operation(summary = "下载/预览合同PDF", description = "返回合同PDF文件流，供前端展示或下载")
    public ResponseEntity<byte[]> downloadContract(@PathVariable String applicationId) {
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
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
