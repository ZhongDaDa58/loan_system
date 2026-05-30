package com.loan.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class UserBasicInfoDTO {
    @NotBlank(message = "产品ID不能为空")
    private String productId;       // 产品ID

    @NotNull(message = "申请金额不能为空")
    @DecimalMin(value = "0.01", message = "申请金额必须大于0")
    private BigDecimal applyAmount; // 申请金额

    @NotNull(message = "申请期限不能为空")
    @Min(value = 1, message = "申请期限必须大于0")
    private Integer applyTerm;      // 申请期限（月）

    private Long disbursementCardId;

    @NotNull(message = "个人基本信息不能为空")
    @Valid
    private PersonalInfo personalInfo;

    @NotNull(message = "家庭状况信息不能为空")
    @Valid
    private FamilyInfo familyInfo;

    @NotNull(message = "财务状况信息不能为空")
    @Valid
    private FinancialInfo financialInfo;

    @NotNull(message = "职业信息不能为空")
    @Valid
    private EmploymentInfo employmentInfo;

    @Data
    public static class PersonalInfo {
        @NotBlank(message = "姓名不能为空")
        private String name;

        @NotBlank(message = "身份证号不能为空")
        private String idCard;

        @NotBlank(message = "手机号不能为空")
        private String phone;

        private String email;

        @NotNull(message = "出生日期不能为空")
        private LocalDate birthDate;

        @NotBlank(message = "性别不能为空")
        private String gender;

        private String education;

        private String occupation;
    }

    @Data
    public static class FamilyInfo {
        @NotBlank(message = "婚姻状况不能为空")
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
        @NotBlank(message = "子女姓名不能为空")
        private String name;

        @NotNull(message = "子女出生日期不能为空")
        private LocalDate birthDate;

        private String relationship;
    }

    @Data
    public static class ParentInfo {
        @NotBlank(message = "父母姓名不能为空")
        private String name;

        private LocalDate birthDate;

        private String relationship;

        private Boolean isDependent;
    }

    @Data
    public static class DependentInfo {
        @NotBlank(message = "家属姓名不能为空")
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
        @NotBlank(message = "收入来源类型不能为空")
        private String type;

        @NotNull(message = "月收入金额不能为空")
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
        @NotBlank(message = "资产类型不能为空")
        private String assetType;

        private String description;

        @NotNull(message = "资产估值不能为空")
        private BigDecimal estimatedValue;

        private LocalDate acquisitionDate;
    }

    @Data
    public static class LiabilityInfo {
        @NotBlank(message = "负债类型不能为空")
        private String liabilityType;

        private String creditor;

        @NotNull(message = "负债金额不能为空")
        private BigDecimal totalAmount;

        @NotNull(message = "每月还款额不能为空")
        private BigDecimal monthlyPayment;

        private LocalDate dueDate;
    }

    @Data
    public static class EmploymentInfo {
        @NotBlank(message = "公司名称不能为空")
        private String companyName;

        private String position;

        @NotNull(message = "工作年限不能为空")
        private Integer workDurationYears;

        private String industryType;

        @NotNull(message = "月工资不能为空")
        private BigDecimal monthlySalary;

        private BigDecimal additionalIncome;

        private String employmentType;
    }
}
