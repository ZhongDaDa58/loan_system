package com.loan.entity;
import com.loan.entity.enums.ApplicationStatusEnum;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 贷款申请表实体类
 * 对应数据库表：loan_application
 */
@Data
public class LoanApplication {

    /**
     * 申请ID（由IdUtil.generateId()生成）
     */
    private String applicationId;

    /**
     * 申请人ID（关联sys_user表的user_id）
     */
    private String userId;

    /**
     * 产品ID（关联loan_product表的product_id）
     */
    private String productId;

    /**
     * 申请金额
     */
    private BigDecimal applyAmount;

    /**
     * 申请期限（月）
     */
    private Integer applyTerm;

    /**
     * 申请人信用分（用于风控预审）
     */
    private Integer creditScore;

    /**
     * 风险评分结果（使用枚举管理，避免硬编码）
     * 关联枚举：ScorecardResultEnum（autoPass=自动通过，manualReview=人工审核等）
     */
    private Integer scorecardScore;

    /**
     * 风险评分结果描述（用于前端展示）
     */
    private String scorecardDecision;

    /**
     * 申请状态（使用枚举管理，避免硬编码）
     * 关联枚举：ApplicationStatusEnum（pending=待审核，approved=审核通过等）
     */
    private String applicationStatus;

    /**
     * 申请时间（提交申请时自动生成）
     */
    private Date applyTime;

    /**
     * 放款银行卡ID（关联mock_bank_card_pool表的id）
     */

    private Long disbursementCardId;

    /**
     * 记录更新时间（状态变更时自动更新）
     */
    private Date updateTime;

    /**
     * 合同状态
     * 0-未生成, 1-待签署, 2-已签署(待盖章), 3-已生效
     */
    private Integer contractStatus;

    /**
     * 合同PDF文件路径
     */
    private String contractPath;

    /**
     * 合同SHA-256哈希值(防篡改存证)
     */
    private String contractHash;

    /**
     * 新增：用户签署时间
     */
    private Date signTime;

    /**
     * 新增：平台盖章时间
     */
    private Date stampTime;



    // 扩展方法：获取状态描述（非持久化字段，用于前端展示）
    public String getStatusDesc() {
        ApplicationStatusEnum statusEnum = ApplicationStatusEnum.getByCode(applicationStatus);
        return statusEnum != null ? statusEnum.getDesc() : "未知状态";
    }
}