package com.loan.entity;
import com.loan.entity.enums.AuditResultEnum;
import lombok.Data;
import java.util.Date;

/**
 * 审核记录表实体类
 * 对应数据库表：audit_record
 */
@Data
public class AuditRecord {

    /**
     * 审核记录ID（主键，建议用UUID或IdUtil生成）
     */
    private String auditId;

    /**
     * 关联的贷款申请ID（外键，关联loan_application.application_id）
     */
    private String applicationId;

    /**
     * 审核员ID（外键，关联sys_user.user_id）
     */
    private String auditorId;

    /**
     * 审核结果（使用枚举管理，避免硬编码）
     * 关联枚举：AuditResultEnum（approved=通过，rejected=拒绝，supplement=需补充材料）
     */
    private String auditResult;

    /**
     * 审核意见（可选，如拒绝原因、补充材料说明等）
     */
    private String auditOpinion;

    /**
     * 审核时间（提交审核结果时自动生成）
     */
    private Date auditTime;
}
