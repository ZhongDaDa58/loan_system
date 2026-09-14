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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Date;

@Service
public class UserCreditProfileServiceImpl implements UserCreditProfileService {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    @Resource
    private UserCreditProfileMapper profileMapper;

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private ScorecardService scorecardService;

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
        return Result.success("信用档案创建成功，信用分：" + profile.getCreditScore());
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
        return Result.success("信用档案更新成功，信用分：" + existing.getCreditScore());
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

        Integer score = scorecardService.calculateScoreOnly(userProfile);
        profileMapper.updateScore(userId, score);

        return Result.success("重新评分完成，当前信用分：" + score);
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

        // 嵌套对象序列化为 JSON 字符串存储
        entity.setFamilyInfo(toJson(dto.getFamilyInfo()));
        entity.setFinancialInfo(toJson(dto.getFinancialInfo()));
        entity.setEmploymentInfo(toJson(dto.getEmploymentInfo()));

        if (dto.getBirthDate() != null) {
            entity.setBirthDate(Date.from(dto.getBirthDate()
                    .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
    }

    private void computeFeatures(UserCreditProfile entity, CreditProfileDTO dto) {
        UserBasicInfoDTO basicInfo = buildBasicInfo(dto);

        FeatureTransformationResultDTO result =
                featureTransformationService.transformToUserProfile(basicInfo);

        if (result.getIsValid() != null && result.getIsValid()) {
            UserProfileDTO profile = result.getUserProfile();
            entity.setAge(profile.getAge());
            entity.setDebtRatio(profile.getDebtRatio());
            entity.setMonthlyIncome(profile.getMonthlyIncome());
            entity.setCreditLines(profile.getCreditLines());
            entity.setDependents(profile.getDependents());
            entity.setRevolvingUtil(profile.getRevolvingUtil());

            Integer score = scorecardService.calculateScoreOnly(profile);
            entity.setCreditScore(score);
        }
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
            System.err.println("⚠️ JSON 序列化失败 (" + obj.getClass().getSimpleName() + "): " + e.getMessage());
            return null;
        }
    }
}
