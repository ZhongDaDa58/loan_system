package com.loan.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.CreditProfileDTO;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.UserCreditProfileMapper;
import com.loan.service.FeatureTransformationService;
import com.loan.service.ScorecardService;
import com.loan.service.UserCreditProfileService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Date;

@Service
public class UserCreditProfileServiceImpl implements UserCreditProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserCreditProfileServiceImpl.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    @Resource
    private UserCreditProfileMapper profileMapper;

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private ScorecardService scorecardService;

    /** 统一评分路由：优先「多源特征 + 多模型 v4」，覆盖不足回落单模型 v3（与申请链路口径一致） */
    @Resource
    private com.loan.service.ScorecardRoutingService scorecardRoutingService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createProfile(String userId, CreditProfileDTO dto) {
        UserCreditProfile existing = profileMapper.selectByUserId(userId);
        if (existing != null) {
            return Result.error(400, "您已创建信用档案，如需修改请使用更新接口");
        }

        // ① 生日缺失时从身份证推（身份证必有生日，不该让用户填两遍）
        fillBirthDateFromIdCard(dto);
        // ② 评分必需字段前置校验 —— 缺就报错，不落库
        //    这样才不会出现「档案建了但没分数」：建成功 = 一定有分数
        validateScoringRequirements(dto);

        UserCreditProfile profile = new UserCreditProfile();
        profile.setUserId(userId);
        copyDtoToEntity(dto, profile);
        profile.setCreateTime(new Date());
        profile.setUpdateTime(new Date());

        // 评分失败会抛异常 -> 事务回滚 -> 档案不落库（与「创建成功」绑定）
        computeFeatures(profile, dto);

        profileMapper.insert(profile);
        return Result.success(buildSaveMessage("信用档案创建成功", profile.getCreditScore()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateProfile(String userId, CreditProfileDTO dto) {
        UserCreditProfile existing = profileMapper.selectByUserId(userId);
        if (existing == null) {
            return Result.error(400, "请先创建信用档案");
        }

        // 与 createProfile 保持同一套规则：出生日期可推导，评分必需字段缺就报错
        fillBirthDateFromIdCard(dto);
        validateScoringRequirements(dto);

        copyDtoToEntity(dto, existing);
        existing.setUpdateTime(new Date());

        // 评分失败抛异常 -> 回滚 -> 档案不会被改成"有档案无分"的状态
        computeFeatures(existing, dto);

        profileMapper.update(existing);
        return Result.success(buildSaveMessage("信用档案更新成功", existing.getCreditScore()));
    }

    @Override
    public Result<UserCreditProfile> getProfile(String userId) {
        UserCreditProfile profile = profileMapper.selectByUserId(userId);
        if (profile == null) {
            return Result.error(404, "请先创建信用档案");
        }
        return Result.success(profile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deleteProfile(String userId) {
        profileMapper.deleteByUserId(userId);
        return Result.success("信用档案已删除");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> rescore(String userId) {
        UserCreditProfile profile = profileMapper.selectByUserId(userId);
        if (profile == null) {
            return Result.error(404, "请先创建信用档案");
        }

        UserProfileDTO userProfile = new UserProfileDTO();
        userProfile.setAge(profile.getAge());
        userProfile.setDebtRatio(profile.getDebtRatio());
        userProfile.setMonthlyIncome(profile.getMonthlyIncome());
        userProfile.setCreditLines(profile.getCreditLines());
        userProfile.setDependents(profile.getDependents());
        userProfile.setRevolvingUtil(profile.getRevolvingUtil());

        try {
            // 【口径统一】与建档/申请链路走同一入口：ScorecardRoutingService
            // （多源多模型 v4 → 多源覆盖不足回落 v3）
            // 原实现直接调 scorecardService.calculateScoreOnly()（固定 v3 单模型），
            // 会让 rescore 把档案分从"多源分"覆盖成"单源分"（同类问题见 P-030）
            com.loan.service.ScorecardRoutingService.ScoreOutcome outcome =
                    scorecardRoutingService.score(userId, userProfile);
            Integer score = outcome.score();
            profileMapper.updateScore(userId, score);
            log.info("✅ 重新评分: userId={} score={} model={} source={}",
                    userId, score, outcome.modelKey(), outcome.source());
            return Result.success("重新评分完成，当前信用分：" + score);
        } catch (Exception e) {
            log.warn("⚠️ 重新评分失败: userId={}, 原因={}", userId, e.getMessage());
            return Result.error(400, "重新评分失败：" + e.getMessage());
        }
    }

    // ========== 私有方法 ==========

    private void copyDtoToEntity(CreditProfileDTO dto, UserCreditProfile entity) {
        entity.setName(dto.getName());
        entity.setIdCard(dto.getIdCard());
        entity.setPhone(dto.getPhone());
        entity.setEmail(dto.getEmail());
        entity.setGender(dto.getGender());
        entity.setEducation(dto.getEducation());
        entity.setOccupation(dto.getOccupation());
        // 阶段 C2：地址（可选填，用于第一层交叉验证）
        entity.setHomeAddress(dto.getHomeAddress());
        entity.setCompanyAddress(dto.getCompanyAddress());

        // 嵌套对象序列化为 JSON 字符串存储
        entity.setFamilyInfo(toJson(dto.getFamilyInfo()));
        entity.setFinancialInfo(toJson(dto.getFinancialInfo()));
        entity.setEmploymentInfo(toJson(dto.getEmploymentInfo()));

        if (dto.getBirthDate() != null) {
            entity.setBirthDate(Date.from(dto.getBirthDate()
                    .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
    }

    /**
     * 计算画像特征并评分
     *
     * 注意：评分失败不再静默——会记录告警，并在保存结果提示中告知用户（避免"保存成功但无法申请"）。
     */
    private void computeFeatures(UserCreditProfile entity, CreditProfileDTO dto) {
        UserBasicInfoDTO basicInfo = buildBasicInfo(dto);

        FeatureTransformationResultDTO result =
                featureTransformationService.transformToUserProfile(basicInfo);

        if (result.getIsValid() == null || !result.getIsValid()) {
            // ⚠️ 这里以前是 log.warn + return —— 会留下「档案建了但 credit_score 是 NULL」的中间态。
            //    用户去申请时被告知"请先创建信用档案"（其实档案在、只是没分），
            //    再去创建又撞"您已创建信用档案"，形成死循环（2026-10-27 陈静就是这个问题）。
            //    现在改为抛异常 -> 事务回滚 -> 档案不落库，保证「有档案必有分数」。
            String reason = result.getErrorMessage() != null && !result.getErrorMessage().isBlank()
                    ? result.getErrorMessage()
                    : "请检查出生日期、财务信息、职业信息是否完整";
            log.warn("特征转换未通过，拒绝落库: userId={}, reason={}", entity.getUserId(), reason);
            throw new BusinessException(400, "信用档案评分失败：" + reason);
        }

        UserProfileDTO profile = result.getUserProfile();
        entity.setAge(profile.getAge());
        entity.setDebtRatio(profile.getDebtRatio());
        entity.setMonthlyIncome(profile.getMonthlyIncome());
        entity.setCreditLines(profile.getCreditLines());
        entity.setDependents(profile.getDependents());
        entity.setRevolvingUtil(profile.getRevolvingUtil());

        try {
            // 统一评分口径：优先「多源特征 + 多模型 v4」，覆盖不足时回落单模型 v3 —— 保证档案分与申请分一致
            com.loan.service.ScorecardRoutingService.ScoreOutcome outcome =
                    scorecardRoutingService.score(entity.getUserId(), profile);
            entity.setCreditScore(outcome.score());
            log.info("✅ 档案评分: userId={} score={} model={} source={} 缺失特征={}/{}",
                    entity.getUserId(), outcome.score(), outcome.modelKey(), outcome.source(),
                    outcome.missingFeatures(), outcome.requiredFeatures());
        } catch (Exception e) {
            // 以前 catch 后只打日志，档案照样落库、分数留空 —— 同样是"档案无分"的来源
            log.warn("评分失败，拒绝落库: userId={}, 原因={}", entity.getUserId(), e.getMessage());
            throw new BusinessException(400, "信用档案评分失败：" + e.getMessage());
        }
    }

    /**
     * 评分前置校验：缺字段就直接报错，<b>不落库</b>。
     *
     * <p>为什么要在评分之前先查一遍：{@code transformToUserProfile} 里只有
     * {@code calculateAge} 会抛异常（出生日期为空），其它特征缺失是静默取 0 ——
     * 会出现"能建档但分数毫无意义"的情况。这里把<b>评分真正需要的</b>信息列清楚，
     * 让用户在提交那一刻就知道缺什么，而不是建完档案去申请才发现没分。
     *
     * <p>出生日期不算必填：会自动从身份证推导（见 {@link #fillBirthDateFromIdCard}）。
     */
    private void validateScoringRequirements(CreditProfileDTO dto) {
        java.util.List<String> missing = new java.util.ArrayList<>();

        // 出生日期：没填、且身份证也推不出来，才算缺
        if (dto.getBirthDate() == null
                && com.loan.util.IdCardValidator.deriveBirthDate(dto.getIdCard()) == null) {
            missing.add("出生日期（填身份证号可自动推导，或直接选择生日）");
        }

        // 财务信息：负债率 / 授信额度 / 循环额度使用率 全部来自这里
        if (dto.getFinancialInfo() == null) {
            missing.add("财务信息（收入来源、资产、负债）");
        } else {
            java.math.BigDecimal income = monthlyIncomeOf(dto);
            if (income == null || income.signum() <= 0) {
                missing.add("月收入（职业信息中的月薪，或财务信息中的收入来源）");
            }
        }

        if (!missing.isEmpty()) {
            throw new BusinessException(400,
                    "评分需要以下信息，请补全后再提交：" + String.join("、", missing));
        }
    }

    /** 月收入：职业月薪 + 职业附加收入 + 财务其它收入（与 FeatureTransformationServiceImpl 口径一致） */
    private java.math.BigDecimal monthlyIncomeOf(CreditProfileDTO dto) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        if (dto.getEmploymentInfo() != null) {
            if (dto.getEmploymentInfo().getMonthlySalary() != null) {
                total = total.add(dto.getEmploymentInfo().getMonthlySalary());
            }
            if (dto.getEmploymentInfo().getAdditionalIncome() != null) {
                total = total.add(dto.getEmploymentInfo().getAdditionalIncome());
            }
        }
        if (dto.getFinancialInfo() != null && dto.getFinancialInfo().getIncomeSources() != null) {
            for (CreditProfileDTO.IncomeSource src : dto.getFinancialInfo().getIncomeSources()) {
                if (src.getMonthlyAmount() != null) {
                    total = total.add(src.getMonthlyAmount());
                }
            }
        }
        return total;
    }

    /** 生日为空时，从身份证号推导后回填 —— 身份证本来就有生日，不该让用户填两遍 */
    private void fillBirthDateFromIdCard(CreditProfileDTO dto) {
        if (dto.getBirthDate() == null) {
            java.time.LocalDate derived = com.loan.util.IdCardValidator.deriveBirthDate(dto.getIdCard());
            if (derived != null) {
                dto.setBirthDate(derived);
                log.info("出生日期为空，已从身份证推导: idCard={}****, birthDate={}",
                        dto.getIdCard() != null && dto.getIdCard().length() >= 6
                                ? dto.getIdCard().substring(0, 6) : "?",
                        derived);
            }
        }
    }

    /**
     * 保存结果提示：分数为空时给出明确原因
     */
    private String buildSaveMessage(String prefix, Integer creditScore) {
        if (creditScore == null) {
            return prefix + "，但评分未生成：请检查财务信息（收入来源、负债信息）与职业信息是否填写完整，完善后重新保存";
        }
        return prefix + "，信用分：" + creditScore;
    }

    /**
     * 将 CreditProfileDTO 转为 UserBasicInfoDTO（嵌套结构直接映射）
     */
    private UserBasicInfoDTO buildBasicInfo(CreditProfileDTO dto) {
        UserBasicInfoDTO info = new UserBasicInfoDTO();

        // PersonalInfo
        UserBasicInfoDTO.PersonalInfo personalInfo = new UserBasicInfoDTO.PersonalInfo();
        personalInfo.setName(dto.getName());
        personalInfo.setIdCard(dto.getIdCard());
        personalInfo.setPhone(dto.getPhone());
        personalInfo.setEmail(dto.getEmail());
        personalInfo.setBirthDate(dto.getBirthDate());
        personalInfo.setGender(dto.getGender());
        personalInfo.setEducation(dto.getEducation());
        personalInfo.setOccupation(dto.getOccupation());
        // 阶段 C2：地址回显（App/Web 读取档案时能看到已填写的地址）
        personalInfo.setHomeAddress(dto.getHomeAddress());
        personalInfo.setCompanyAddress(dto.getCompanyAddress());
        info.setPersonalInfo(personalInfo);

        // FamilyInfo / FinancialInfo / EmploymentInfo 直接映射（类型结构一致）
        info.setFamilyInfo(mapFamilyInfo(dto.getFamilyInfo()));
        info.setFinancialInfo(mapFinancialInfo(dto.getFinancialInfo()));
        info.setEmploymentInfo(mapEmploymentInfo(dto.getEmploymentInfo()));

        return info;
    }

    // ========== 类型映射（CreditProfileDTO.* → UserBasicInfoDTO.*，通过 JSON 做结构转换） ==========

    private UserBasicInfoDTO.FamilyInfo mapFamilyInfo(CreditProfileDTO.FamilyInfo src) {
        if (src == null) return new UserBasicInfoDTO.FamilyInfo();
        return objectMapper.convertValue(src, UserBasicInfoDTO.FamilyInfo.class);
    }

    private UserBasicInfoDTO.FinancialInfo mapFinancialInfo(CreditProfileDTO.FinancialInfo src) {
        if (src == null) return new UserBasicInfoDTO.FinancialInfo();
        return objectMapper.convertValue(src, UserBasicInfoDTO.FinancialInfo.class);
    }

    private UserBasicInfoDTO.EmploymentInfo mapEmploymentInfo(CreditProfileDTO.EmploymentInfo src) {
        if (src == null) return new UserBasicInfoDTO.EmploymentInfo();
        return objectMapper.convertValue(src, UserBasicInfoDTO.EmploymentInfo.class);
    }

    /**
     * 对象 → JSON 字符串（失败返回 null）
     */
    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("⚠️ JSON 序列化失败 (" + obj.getClass().getSimpleName() + "): " + e.getMessage());
            return null;
        }
    }
}
