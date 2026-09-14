package com.loan.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户信用档案 DTO（建档 / 更新时使用）
 * 结构与 UserBasicInfoDTO 一致，前端可共用同一套 JSON
 */
@Data
public class CreditProfileDTO {

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotBlank(message = "身份证号不能为空")
    private String idCard;

    @NotBlank(message = "手机号不能为空")
    private String phone;

    private String email;
    private LocalDate birthDate;

    @NotBlank(message = "性别不能为空")
    private String gender;

    private String education;
    private String occupation;

    @Valid
    private FamilyInfo familyInfo;

    @Valid
    private FinancialInfo financialInfo;

    @Valid
    private EmploymentInfo employmentInfo;

    // ===== 内嵌类（与 UserBasicInfoDTO 一致） =====

    @Data
    public static class FamilyInfo {
        private String maritalStatus;
        private SpouseInfo spouseInfo;
        private List<ChildInfo> childrenList;
        private List<ParentInfo> parentsInfo;
        private List<DependentInfo> otherDependents;
    }

    @Data
    public static class SpouseInfo {
        private String name;
        private LocalDate birthDate;
        private String occupation;
        private BigDecimal monthlyIncome;
    }

    @Data
    public static class ChildInfo {
        private String name;
        private LocalDate birthDate;
        private String relationship;
    }

    @Data
    public static class ParentInfo {
        private String name;
        private LocalDate birthDate;
        private String relationship;
        private Boolean isDependent;
    }

    @Data
    public static class DependentInfo {
        private String name;
        private String relationship;
        private Boolean isFinancialDependent;
    }

    @Data
    public static class FinancialInfo {
        private List<IncomeSource> incomeSources;
        private List<BankStatement> bankStatements;
        private List<AssetInfo> assetInfo;
        private List<LiabilityInfo> liabilityInfo;
    }

    @Data
    public static class IncomeSource {
        private String type;
        private BigDecimal monthlyAmount;
        private String description;
    }

    @Data
    public static class BankStatement {
        private String bankName;
        private String accountNumber;
        private List<MonthlyBalance> monthlyBalances;
    }

    @Data
    public static class MonthlyBalance {
        private String month;
        private BigDecimal averageBalance;
        private BigDecimal totalIncome;
        private BigDecimal totalExpense;
    }

    @Data
    public static class AssetInfo {
        private String assetType;
        private String description;
        private BigDecimal estimatedValue;
        private LocalDate acquisitionDate;
    }

    @Data
    public static class LiabilityInfo {
        private String liabilityType;
        private String creditor;
        private BigDecimal totalAmount;
        private BigDecimal monthlyPayment;
        private LocalDate dueDate;
    }

    @Data
    public static class EmploymentInfo {
        private String companyName;
        private String position;
        private Integer workDurationYears;
        private String industryType;
        private BigDecimal monthlySalary;
        private BigDecimal additionalIncome;
        private String employmentType;
    }
}
