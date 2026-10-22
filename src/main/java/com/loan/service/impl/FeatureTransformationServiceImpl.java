package com.loan.service.impl;

import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.*;
import com.loan.exception.BusinessException;
import com.loan.mapper.UserCreditProfileMapper;
import com.loan.service.FeatureTransformationService;
import com.loan.service.MultiSourceIngestService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeatureTransformationServiceImpl implements FeatureTransformationService {

    private static final Logger log = LoggerFactory.getLogger(FeatureTransformationServiceImpl.class);

    @Override
    public FeatureTransformationResultDTO transformToUserProfile(UserBasicInfoDTO request) {
        try {
            log.info("🔄 开始特征转换，用户: {}", request.getPersonalInfo().getName());

            Map<String, Object> transformationDetails = new LinkedHashMap<>();

            Integer age = calculateAge(request.getPersonalInfo().getBirthDate());
            transformationDetails.put("age", age);

            Integer dependents = calculateDependents(request.getFamilyInfo());
            transformationDetails.put("dependents", dependents);

            BigDecimal monthlyIncome = calculateMonthlyIncome(
                    request.getFinancialInfo(),
                    request.getEmploymentInfo()
            );
            transformationDetails.put("monthlyIncome", monthlyIncome);

            BigDecimal debtRatio = calculateDebtRatio(request.getFinancialInfo());
            transformationDetails.put("debtRatio", debtRatio);

            BigDecimal revolvingUtil = calculateRevolvingUtil(request.getFinancialInfo());
            transformationDetails.put("revolvingUtil", revolvingUtil);

            Integer creditLines = calculateCreditLines(request.getFinancialInfo());
            transformationDetails.put("creditLines", creditLines);

            UserProfileDTO userProfile = new UserProfileDTO();
            userProfile.setAge(age);
            userProfile.setDependents(dependents);
            userProfile.setMonthlyIncome(monthlyIncome);
            userProfile.setDebtRatio(debtRatio);
            userProfile.setRevolvingUtil(revolvingUtil);
            userProfile.setCreditLines(creditLines);

            log.info("✅ 特征转换完成，用户: {}", request.getPersonalInfo().getName());

            return FeatureTransformationResultDTO.success(userProfile, transformationDetails);

        } catch (Exception e) {
            log.error("❌ 特征转换失败: {}", e.getMessage(), e);
            return FeatureTransformationResultDTO.failure("特征转换失败: " + e.getMessage());
        }
    }

    @Override
    public Integer calculateDependents(UserBasicInfoDTO.FamilyInfo familyInfo) {
        int count = 0;

        if ("MARRIED".equalsIgnoreCase(familyInfo.getMaritalStatus())
                && familyInfo.getSpouseInfo() != null) {
            count++;
        }

        if (familyInfo.getChildrenList() != null) {
            count += familyInfo.getChildrenList().size();
        }

        if (familyInfo.getParentsInfo() != null) {
            count += familyInfo.getParentsInfo().stream()
                    .filter(parent -> Boolean.TRUE.equals(parent.getIsDependent()))
                    .mapToInt(parent -> 1)
                    .sum();
        }

        if (familyInfo.getOtherDependents() != null) {
            count += familyInfo.getOtherDependents().stream()
                    .filter(dep -> Boolean.TRUE.equals(dep.getIsFinancialDependent()))
                    .mapToInt(dep -> 1)
                    .sum();
        }

        log.debug("👨‍👩‍👧‍👦 家属数量计算结果: {}", count);
        return count;
    }

    @Override
    public BigDecimal calculateMonthlyIncome(UserBasicInfoDTO.FinancialInfo financialInfo,
                                             UserBasicInfoDTO.EmploymentInfo employmentInfo) {
        BigDecimal totalIncome = BigDecimal.ZERO;

        if (employmentInfo != null) {
            if (employmentInfo.getMonthlySalary() != null) {
                totalIncome = totalIncome.add(employmentInfo.getMonthlySalary());
            }
            if (employmentInfo.getAdditionalIncome() != null) {
                totalIncome = totalIncome.add(employmentInfo.getAdditionalIncome());
            }
        }

        if (financialInfo != null && financialInfo.getIncomeSources() != null) {
            BigDecimal otherIncome = financialInfo.getIncomeSources().stream()
                    .filter(source -> !"SALARY".equalsIgnoreCase(source.getType()))
                    .map(UserBasicInfoDTO.IncomeSource::getMonthlyAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            totalIncome = totalIncome.add(otherIncome);
        }

        log.debug("💰 月收入计算结果: {}", totalIncome);
        return totalIncome.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal calculateDebtRatio(UserBasicInfoDTO.FinancialInfo financialInfo) {
        if (financialInfo == null || financialInfo.getLiabilityInfo() == null
                || financialInfo.getLiabilityInfo().isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalMonthlyPayment = financialInfo.getLiabilityInfo().stream()
                .map(UserBasicInfoDTO.LiabilityInfo::getMonthlyPayment)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalMonthlyIncome = calculateMonthlyIncomeFromFinancial(financialInfo);

        if (totalMonthlyIncome.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal debtRatio = totalMonthlyPayment.divide(totalMonthlyIncome, 4, RoundingMode.HALF_UP);

        log.debug("📊 债务比率计算结果: {}", debtRatio);
        return debtRatio;
    }

    @Override
    public BigDecimal calculateRevolvingUtil(UserBasicInfoDTO.FinancialInfo financialInfo) {
        if (financialInfo == null || financialInfo.getLiabilityInfo() == null
                || financialInfo.getLiabilityInfo().isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 同时匹配英文 CREDIT_CARD 和中文 信用卡
        BigDecimal totalCreditLimit = financialInfo.getLiabilityInfo().stream()
                .filter(li -> "CREDIT_CARD".equalsIgnoreCase(li.getLiabilityType())
                        || "信用卡".equals(li.getLiabilityType()))
                .map(UserBasicInfoDTO.LiabilityInfo::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ⚠️ 当前用 monthlyPayment 代替已用额度，数据上会偏低
        // 后续如有已用额度字段可改为 usedAmount
        BigDecimal totalUsed = financialInfo.getLiabilityInfo().stream()
                .filter(li -> "CREDIT_CARD".equalsIgnoreCase(li.getLiabilityType())
                        || "信用卡".equals(li.getLiabilityType()))
                .map(UserBasicInfoDTO.LiabilityInfo::getMonthlyPayment)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCreditLimit.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal utilization = totalUsed.divide(totalCreditLimit, 4, RoundingMode.HALF_UP);

        log.debug("💳 循环信贷使用率计算结果: {}", utilization);
        return utilization;
    }

    // ==================== W5-A1：多源特征装配（新增，不影响上述 6 特征方法） ====================

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;

    @Resource
    private MultiSourceIngestService multiSourceIngestService;

    /** XGB 多源提纯推理器（为融合模型 v5-fusion 提供 xgb_multi_score 特征） */
    @Resource
    private com.loan.scorecard.XgbTreeScorer xgbTreeScorer;

    /**
     * 组装"基础 6 特征 + 多源特征"的评分入参（供 /api/v1/scorecard/v2/calculate 使用）。
     * 读不到的部分不放入 Map，由模型侧按 missingFeatures 标注缺失，不影响评分可用性。
     */
    @Override
    public Map<String, Object> buildMultiSourceFeatures(String userId) {
        Map<String, Object> features = new LinkedHashMap<>();
        try {
            // 1) 基础 6 特征（信用档案口径，与 v3 模型一致）
            UserCreditProfile profile = userCreditProfileMapper.selectByUserId(userId);
            if (profile != null) {
                putIfNotNull(features, "revolving_util", profile.getRevolvingUtil());
                putIfNotNull(features, "age", profile.getAge());
                // S5 修正：模型特征名是 debt_to_income（v4 的 21 特征 + 多源 credit 块同名），
                // 而档案字段叫 debtRatio —— 原实现喂的键名是 debt_ratio，导致**模型判该特征缺失**。
                // 两个键都填：debt_to_income 供模型；debt_ratio 兼容其它引用方。
                // 注意顺序：多源特征在下方装配，会覆盖同名键（多源优先）。
                putIfNotNull(features, "debt_ratio", profile.getDebtRatio());
                putIfNotNull(features, "debt_to_income", profile.getDebtRatio());
                putIfNotNull(features, "monthly_income", profile.getMonthlyIncome());
                putIfNotNull(features, "credit_lines", profile.getCreditLines());
                putIfNotNull(features, "dependents", profile.getDependents());
            }
            // 2) 多源特征（特征宽表快照：征信/运营商/设备等）
            Map<String, Object> snapshot = multiSourceIngestService.featureSnapshot(userId);
            Object multi = snapshot == null ? null : snapshot.get("features");
            if (multi instanceof Map<?, ?> multiMap) {
                for (Map.Entry<?, ?> entry : multiMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        features.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            // 3.5) 【T1 修复】补上 gps_deviation_km_longterm —— 它是 v4 / v4-fusion / XGB 的
            //      **在册特征**，但此前线上没有任何装配来源（恒判缺失，P-038）。
            //      此处用「档案地址 + 多源 location 经纬度」计算本次偏离距离（城市级）。
            //      语义说明：设计 §3.3 原意是"长期偏离"，当前实现为"本次偏离"（见开发文档 D-T1）。
            putGpsDeviation(features, profile);

            // 4) XGB 多源提纯分（v4-fusion / v5-fusion 使用；v3 / v4 会忽略该特征）
            if (xgbTreeScorer.isReady()) {
                Double xgbScore = xgbTreeScorer.predictMultiScore(features);
                if (xgbScore != null) {
                    features.put("xgb_multi_score", xgbScore);
                    log.info("🧠 XGB 多源提纯分：{} -> xgb_multi_score={}", userId, String.format("%.6f", xgbScore));
                }
            }
            log.info("🧩 多源特征装配完成（含 XGB 提纯分）：用户 {}，特征数 {}", userId, features.size());
        } catch (Exception e) {
            log.warn("⚠️ 多源特征装配失败（返回已装配部分）: {}", e.getMessage());
        }
        return features;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 【T1】计算并写入 {@code gps_deviation_km_longterm}（地址与定位的城市级偏离距离）
     *
     * <p>该特征此前在线**无任何生产者**，导致 v4 / v4-fusion / XGB 恒判其缺失（P-038）。
     * 数据来源：档案的 {@code home_address} / {@code company_address} +
     * 多源 location 块的 {@code latitude} / {@code longitude}（阶段 C3 已落库）。
     *
     * <p><b>算不出时不写入</b>（无地址 / 无定位 / 城市解析失败）→ 由模型侧用
     * {@code fill_value} 兜底（P-037 修复），保持训练/在线口径一致。
     *
     * <p>⚠️ 语义说明：设计 §3.3 原意是"**长期**偏离常驻地的程度"，当前实现是"**本次**偏离"，
     * 完整的历史聚合版本见 `特征工程层修复开发文档.md` 阶段 D。
     */
    private void putGpsDeviation(Map<String, Object> features, UserCreditProfile profile) {
        try {
            String address = null;
            if (profile != null) {
                String home = profile.getHomeAddress();
                address = (home != null && !home.isBlank()) ? home : profile.getCompanyAddress();
            }
            double[] city = com.loan.util.GeoCodeUtil.locate(address);
            if (city == null) {
                return;   // 无地址或城市解析失败
            }
            Object lat = features.get("latitude");
            Object lon = features.get("longitude");
            if (!(lat instanceof Number latN) || !(lon instanceof Number lonN)) {
                return;   // 无定位数据
            }
            double km = com.loan.util.GeoCodeUtil.distanceKm(city[0], city[1],
                    latN.doubleValue(), lonN.doubleValue());
            double rounded = Math.round(km * 100d) / 100d;
            features.put("gps_deviation_km_longterm", rounded);
            log.info("📍 GPS 偏离距离 = {} km（阶段 C3/T1）", rounded);
        } catch (Exception e) {
            log.warn("⚠️ gps_deviation_km_longterm 计算失败（跳过，交由 fill_value 兜底）: {}", e.getMessage());
        }
    }

    @Override
    public Integer calculateCreditLines(UserBasicInfoDTO.FinancialInfo financialInfo) {
        if (financialInfo == null || financialInfo.getLiabilityInfo() == null) {
            return 0;
        }

        long creditLines = financialInfo.getLiabilityInfo().stream()
                .filter(liability -> "CREDIT_CARD".equalsIgnoreCase(liability.getLiabilityType())
                        || "信用卡".equals(liability.getLiabilityType())
                        || "LOAN".equalsIgnoreCase(liability.getLiabilityType())
                        || "消费贷".equals(liability.getLiabilityType()))
                .count();

        log.debug("🔢 信用额度数量计算结果: {}", creditLines);
        return (int) creditLines;
    }

    @Override
    public Integer calculateAge(LocalDate birthDate) {
        if (birthDate == null) {
            throw new BusinessException(400, "出生日期不能为空");
        }

        int age = Period.between(birthDate, LocalDate.now()).getYears();

        if (age < 18) {
            throw new BusinessException(400, "申请人年龄必须大于等于18岁");
        }

        if (age > 65) {
            throw new BusinessException(400, "申请人年龄不能超过65岁");
        }

        log.debug("🎂 年龄计算结果: {}", age);
        return age;
    }

    private BigDecimal calculateMonthlyIncomeFromFinancial(UserBasicInfoDTO.FinancialInfo financialInfo) {
        if (financialInfo == null || financialInfo.getIncomeSources() == null) {
            return BigDecimal.ZERO;
        }

        return financialInfo.getIncomeSources().stream()
                .map(UserBasicInfoDTO.IncomeSource::getMonthlyAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
