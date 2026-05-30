package com.loan.controller;
import com.loan.entity.dto.LoanIssueDTO;
import com.loan.entity.vo.Result;
import com.loan.service.LoanIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/loan/issue")
@Tag(name = "放款模块", description = "执行放款操作（管理员/系统专用）")
public class LoanIssueController {

    @Resource
    private LoanIssueService loanIssueService;

    /**
     * 执行放款（需权限，后续控制）
     */
    @PostMapping("/execute")
    @Operation(summary = "执行放款", description = "校验通过后模拟放款，更新余额和申请状态")
    public Result<?> executeIssue(@Valid @RequestBody LoanIssueDTO issueDTO) {
        return loanIssueService.executeIssue(issueDTO);
    }
}