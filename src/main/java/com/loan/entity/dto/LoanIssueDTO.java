package com.loan.entity.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoanIssueDTO {
    @NotBlank(message = "申请ID不能为空")
    private String applicationId; // 申请ID（审核通过的申请）
}
