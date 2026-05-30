package com.loan.controller;

import com.loan.entity.LoanApplication;
import com.loan.entity.vo.Result;
import com.loan.service.ContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contract")
@Tag(name = "电子合同模块", description = "合同生成、签署与存证")
public class ContractController {

    @Resource
    private ContractService contractService;

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
}
