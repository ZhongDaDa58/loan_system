package com.loan.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 模拟金融信息（模拟"银行/征信/社保"侧的用户经济画像）
 *
 * 用途：替代"用户手填收入负债"——系统按用户身份自动拉取，回填信用档案；
 *       并可为多源数据（征信/运营商/社保）提供确定来源。
 * 对应表：mock_financial_profile
 */
@Data
public class MockFinancialProfile {

    private Long id;

    /** 匹配键：身份证号（优先）或手机号 */
    private String matchKey;

    private String realName;

    private String phone;

    // ==================== 收入 ====================
    /** 银行流水推算的月收入 */
    private BigDecimal monthlyIncome;

    /** 主要收入来源（SALARY/BUSINESS/INVESTMENT/FREELANCE） */
    private String incomeSource;

    /** 可核实的银行流水月数 */
    private Integer bankFlowMonths;

    // ==================== 负债 ====================
    /** 信用卡总额度 */
    private BigDecimal creditCardLimit;

    /** 信用卡已用额度 */
    private BigDecimal creditCardUsed;

    /** 在贷笔数 */
    private Integer existingLoanCount;

    /** 月还款额合计 */
    private BigDecimal monthlyDebtPayment;

    /** 近 2 年逾期次数 */
    private Integer overdueCount2y;

    /** 近 3 月征信查询次数 */
    private Integer queryCount3m;

    // ==================== 资产 ====================
    private Boolean hasHouse;

    private Boolean hasCar;

    /** 存款余额 */
    private BigDecimal depositBalance;

    // ==================== 社保 / 运营商 ====================
    /** 社保连续缴纳月数 */
    private Integer socialContribMonths;

    /** 社保/公积金缴费基数 */
    private BigDecimal socialBase;

    /** 手机号实名在网月数 */
    private Integer operatorMonths;

    // ==================== 职业（用于回填 employmentInfo） ====================
    private String companyName;

    private String position;

    private String industryType;

    private Integer workYears;

    private String employmentType;

    /** 数据来源标识：MOCK_BANK（预置）/ AUTO_GEN（按身份确定性生成） */
    private String dataSource;

    private Date createTime;

    private Date updateTime;
}
