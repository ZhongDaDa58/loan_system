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

    // ===== S1：Y 标签（人工结论回流训练库，设计 §8；三项均可选填）=====

    /** 人工判定的最终标签：1=欺诈 / 0=正常 / 不传=未标注 */
    private Integer finalLabel;

    /** 欺诈类型标签（如 资料伪造 / 团伙欺诈 / 中介代办） */
    private String fraudTag;

    /** 结构化拒绝原因（与 auditOpinion 自由文本并存） */
    private String rejectReason;
}