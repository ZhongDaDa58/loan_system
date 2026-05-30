package com.loan.service.impl;

import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.service.DataValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class DataValidationServiceImpl implements DataValidationService {

    private static final Logger log = LoggerFactory.getLogger(DataValidationServiceImpl.class);

    @Override
    public List<String> validateInputData(UserBasicInfoDTO request) {
        List<String> errors = new ArrayList<>();

        if (request.getPersonalInfo() == null) {
            errors.add("个人基本信息不能为空");
            return errors;
        }

        if (request.getFamilyInfo() == null) {
            errors.add("家庭状况信息不能为空");
        }

        if (request.getFinancialInfo() == null) {
            errors.add("财务状况信息不能为空");
        }

        if (request.getEmploymentInfo() == null) {
            errors.add("职业信息不能为空");
        }

        validatePersonalInfo(request.getPersonalInfo(), errors);
        validateFamilyInfo(request.getFamilyInfo(), errors);
        validateFinancialInfo(request.getFinancialInfo(), errors);
        validateEmploymentInfo(request.getEmploymentInfo(), errors);

        return errors;
    }

    @Override
    public List<String> validateLogicalConsistency(UserBasicInfoDTO request) {
        List<String> warnings = new ArrayList<>();

        if (request.getPersonalInfo() != null && request.getEmploymentInfo() != null) {
            validateAgeEmploymentConsistency(request, warnings);
        }

        if (request.getFinancialInfo() != null && request.getEmploymentInfo() != null) {
            validateIncomeConsistency(request, warnings);
        }

        if (request.getFamilyInfo() != null) {
            validateFamilyRelationships(request, warnings);
        }

        return warnings;
    }

    @Override
    public List<String> validateBusinessRules(UserBasicInfoDTO request) {
        List<String> violations = new ArrayList<>();

        if (request.getEmploymentInfo() != null) {
            validateEmploymentDuration(request, violations);
        }

        if (request.getFinancialInfo() != null) {
            validateDebtToIncomeRatio(request, violations);
        }

        return violations;
    }

    @Override
    public boolean isValid(UserBasicInfoDTO request) {
        List<String> errors = validateInputData(request);
        return errors.isEmpty();
    }

    private void validatePersonalInfo(UserBasicInfoDTO.PersonalInfo info, List<String> errors) {
        if (info.getIdCard() != null && !isValidIdCard(info.getIdCard())) {
            errors.add("身份证号格式不正确");
        }

        if (info.getPhone() != null && !isValidPhone(info.getPhone())) {
            errors.add("手机号格式不正确");
        }

        if (info.getBirthDate() != null && info.getBirthDate().isAfter(LocalDate.now())) {
            errors.add("出生日期不能晚于当前日期");
        }
    }

    private void validateFamilyInfo(UserBasicInfoDTO.FamilyInfo info, List<String> errors) {
        if (info == null) {
            return;
        }

        if ("MARRIED".equalsIgnoreCase(info.getMaritalStatus()) && info.getSpouseInfo() == null) {
            errors.add("已婚状态必须填写配偶信息");
        }
    }

    private void validateFinancialInfo(UserBasicInfoDTO.FinancialInfo info, List<String> errors) {
        if (info == null) {
            return;
        }

        if (info.getIncomeSources() != null) {
            info.getIncomeSources().forEach(source -> {
                if (source.getMonthlyAmount() != null && source.getMonthlyAmount().compareTo(BigDecimal.ZERO) < 0) {
                    errors.add("收入金额不能为负数: " + source.getType());
                }
            });
        }

        if (info.getLiabilityInfo() != null) {
            info.getLiabilityInfo().forEach(liability -> {
                if (liability.getTotalAmount() != null && liability.getTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
                    errors.add("负债金额不能为负数: " + liability.getLiabilityType());
                }
            });
        }
    }

    private void validateEmploymentInfo(UserBasicInfoDTO.EmploymentInfo info, List<String> errors) {
        if (info == null) {
            return;
        }

        if (info.getWorkDurationYears() != null && info.getWorkDurationYears() < 0) {
            errors.add("工作年限不能为负数");
        }

        if (info.getMonthlySalary() != null && info.getMonthlySalary().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("月工资不能为负数");
        }
    }

    private void validateAgeEmploymentConsistency(UserBasicInfoDTO request, List<String> warnings) {
        int age = calculateAge(request.getPersonalInfo().getBirthDate());
        int workDuration = request.getEmploymentInfo().getWorkDurationYears() != null
                ? request.getEmploymentInfo().getWorkDurationYears() : 0;

        if (workDuration > age - 18) {
            warnings.add("工作年限超过可能的最大工作时长");
        }
    }

    private void validateIncomeConsistency(UserBasicInfoDTO request, List<String> warnings) {
        if (request.getEmploymentInfo().getMonthlySalary() != null
                && request.getFinancialInfo().getIncomeSources() != null) {

            BigDecimal salaryIncome = request.getEmploymentInfo().getMonthlySalary();
            BigDecimal totalReportedIncome = request.getFinancialInfo().getIncomeSources().stream()
                    .filter(source -> "SALARY".equalsIgnoreCase(source.getType()))
                    .map(source -> source.getMonthlyAmount())
                    .filter(amount -> amount != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (!salaryIncome.equals(totalReportedIncome)) {
                warnings.add("职业信息中的工资与财务信息中的工资收入不一致");
            }
        }
    }

    private void validateFamilyRelationships(UserBasicInfoDTO request, List<String> warnings) {
        if (request.getFamilyInfo().getChildrenList() != null) {
            request.getFamilyInfo().getChildrenList().forEach(child -> {
                if (child.getBirthDate() != null && child.getBirthDate().isAfter(LocalDate.now())) {
                    warnings.add("子女出生日期不能晚于当前日期: " + child.getName());
                }
            });
        }
    }

    private void validateEmploymentDuration(UserBasicInfoDTO request, List<String> violations) {
        if (request.getEmploymentInfo().getWorkDurationYears() != null
                && request.getEmploymentInfo().getWorkDurationYears() < 1) {
            violations.add("工作年限少于1年可能影响贷款审批");
        }
    }

    private void validateDebtToIncomeRatio(UserBasicInfoDTO request, List<String> violations) {
        if (request.getFinancialInfo().getLiabilityInfo() != null
                && request.getEmploymentInfo().getMonthlySalary() != null) {

            BigDecimal totalMonthlyDebt = request.getFinancialInfo().getLiabilityInfo().stream()
                    .map(liability -> liability.getMonthlyPayment())
                    .filter(payment -> payment != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal monthlyIncome = request.getEmploymentInfo().getMonthlySalary();

            if (monthlyIncome.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal debtRatio = totalMonthlyDebt.divide(monthlyIncome, 2, java.math.RoundingMode.HALF_UP);

                if (debtRatio.compareTo(new BigDecimal("0.7")) > 0) {
                    violations.add("债务收入比超过70%，风险较高");
                }
            }
        }
    }

    private boolean isValidIdCard(String idCard) {
        return idCard != null && (idCard.length() == 15 || idCard.length() == 18);
    }

    private boolean isValidPhone(String phone) {
        return phone != null && phone.matches("^1[3-9]\\d{9}$");
    }

    private int calculateAge(LocalDate birthDate) {
        return java.time.Period.between(birthDate, LocalDate.now()).getYears();
    }
}
