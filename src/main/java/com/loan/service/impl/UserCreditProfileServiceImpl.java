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

        UserCreditProfile profile = new UserCreditProfile();
        profile.setUserId(userId);
        copyDtoToEntity(dto, profile);
        profile.setCreateTime(new Date());
        profile.setUpdateTime(new Date());

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

        copyDtoToEntity(dto, existing);
        existing.setUpdateTime(new Date());

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
            log.warn("⚠️ 特征转换未通过，未生成评分: userId={}", entity.getUserId());
            return;
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
            log.warn("⚠️ 评分失败（档案已保存，信用分为空）: userId={}, 原因={}",
                    entity.getUserId(), e.getMessage());
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
