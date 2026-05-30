package com.loan.entity.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AuditSubmitDTO {
    @NotBlank(message = "申请ID不能为空")
    private String applicationId;   // 申请ID

    @NotNull(message = "审核结果不能为空")
    private String auditResult;     // 审核结果（approved/rejected/supplement）

    private String auditOpinion;    // 审核意见（可选）
}