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

    /** 多源数据：补齐用户没填的财务信息（社保基数→月收入、征信 debt_to_income→负债率） */
    @Resource
    private com.loan.service.MultiSourceIngestService multiSourceIngestService;

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

        // 用多源数据补齐财务字段（rescore 没有 dto，按"用户没填"处理 ——
        // 重新评分的语义就是"以最新数据重算"，多源比手填更可信）
        enrichFromMultiSource(profile, null);

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

            // 整条更新（不只 updateScore）：否则多源补齐的 monthlyIncome / debtRatio
            // 只进了模型、没落到档案里，页面仍显示 0
            profile.setCreditScore(score);
            profile.setUpdateTime(new Date());
            profileMapper.update(profile);

            log.info("✅ 重新评分: userId={} score={} model={} source={}, 月收入={} 负债率={}",
                    userId, score, outcome.modelKey(), outcome.source(),
                    profile.getMonthlyIncome(), profile.getDebtRatio());
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

        // 用户没填财务信息时，用多源数据补齐（只补空值，用户填的优先）
        // —— 社保基数 ≈ 月收入、征信 debt_to_income ≈ 负债率，这些本来就不该让用户手填
        enrichFromMultiSource(entity, dto);

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
     * 评分前置校验：只校验<b>无法自动获得</b>的字段。
     *
     * <p><b>为什么不校验财务信息</b>：项目有多源数据融合（社保 `social_base` = 月收入、
     * 征信 `debt_to_income` = 负债率），模型特征集里本来就有这些键
     * （见 {@code MultiSourceIngestServiceImpl.RANGE_RULES}），
     * {@code buildMultiSourceFeatures} 还会用多源值<b>覆盖</b>档案字段。
     * 让用户再手填一遍纯属重复劳动 —— 实测陈静（13800138005）六个源齐全
     * （社保基数 19600、负债率 0.0571），却因为强制要求手填财务信息而卡住。
     *
     * <p>真正自动不了的只有出生日期，而它还能从身份证推 —— 所以这里通常不会拦人，
     * 只在身份证也不合法时才报错。
     */
    private void validateScoringRequirements(CreditProfileDTO dto) {
        if (dto.getBirthDate() == null
                && com.loan.util.IdCardValidator.deriveBirthDate(dto.getIdCard()) == null) {
            throw new BusinessException(400,
                    "请填写有效的身份证号（用于自动获取出生日期），或直接选择出生日期");
        }
    }

    /**
     * 用多源数据补齐档案里为空的财务字段（<b>只补空值，用户填的优先</b>）。
     *
     * <p>多源数据能提供的对应关系：
     * <ul>
     *   <li>社保 {@code social_base}（缴费基数）→ 月收入 {@code monthlyIncome}</li>
     *   <li>征信 {@code debt_to_income} → 负债率 {@code debtRatio}</li>
     * </ul>
     *
     * <p><b>为什么在这里补而不是让用户填</b>：这些数据在用户授权后由
     * {@code MultiSourceSyncService} 自动同步，再让用户手填一遍既是重复劳动，
     * 也可能和真实数据对不上。模型侧本来就是多源优先
     * （{@code buildMultiSourceFeatures} 用多源值覆盖档案字段），
     * 这里补只是为了让档案页面能看到有意义的数字。
     *
     * <p>失败只打日志，不影响建档 —— 多源数据缺失是正常情况（用户未授权/未同步）。
     */
    private void enrichFromMultiSource(UserCreditProfile entity, CreditProfileDTO dto) {
        try {
            java.util.Map<String, Object> snapshot =
                    multiSourceIngestService.featureSnapshot(entity.getUserId());
            Object raw = snapshot == null ? null : snapshot.get("features");
            if (!(raw instanceof java.util.Map<?, ?> features)) {
                return;
            }

            // 月收入：0 一定不合理（有工作就有收入），用社保基数补
            if (entity.getMonthlyIncome() == null || entity.getMonthlyIncome().signum() <= 0) {
                BigDecimal socialBase = toDecimal(features.get("social_base"));
                if (socialBase != null && socialBase.signum() > 0) {
                    entity.setMonthlyIncome(socialBase);
                    log.info("月收入为空，已用社保缴费基数补齐: userId={}, monthlyIncome={}",
                            entity.getUserId(), socialBase);
                }
            }

            // 负债率：⚠️ 只在"用户没填财务信息"时补 ——
            // calculateDebtRatio(null) 返回的是 0 而不是 null，
            // 而"用户真的无负债"和"用户没填"都表现为 0，无法区分。
            // 所以用 dto.financialInfo 是否为空来判定：没填才用多源覆盖，填了就尊重用户输入。
            boolean userProvidedFinancials = dto != null && dto.getFinancialInfo() != null;
            if (!userProvidedFinancials
                    && (entity.getDebtRatio() == null || entity.getDebtRatio().signum() <= 0)) {
                BigDecimal dti = toDecimal(features.get("debt_to_income"));
                if (dti != null && dti.signum() > 0) {
                    entity.setDebtRatio(dti);
                    log.info("负债率为空，已用征信 debt_to_income 补齐: userId={}, debtRatio={}",
                            entity.getUserId(), dti);
                }
            }
        } catch (Exception e) {
            log.warn("多源财务信息补齐失败（不影响建档）: userId={}, 原因={}",
                    entity.getUserId(), e.getMessage());
        }
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
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
