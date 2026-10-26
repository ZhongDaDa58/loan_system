package com.loan.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 加入逾期黑名单的请求体（逾期处理功能升级 第 6 步）
 */
@Data
@Schema(description = "加入逾期黑名单请求")
public class OverdueBlacklistAddDTO {

    @Schema(description = "用户 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "加入原因（必填，会展示给审核员）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "M3 逾期 75 天，结清后仍需观察 6 个月")
    private String reason;

    @Schema(description = "触发时的逾期等级 M1/M2/M3", example = "M3")
    private String overdueLevel;

    @Schema(description = "触发的逾期期次 ID（便于追溯）")
    private String relatedRepaymentId;

    @Schema(description = "到期时间，不传表示永久")
    private Date expireAt;

    @Schema(description = "操作人")
    private String operatorName;
}
