package com.loan.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.mapper.UserCreditProfileMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 信用档案的自动保存（阶段四 4.2 第 5 步补）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl#autoSaveCreditProfile}（68 行），
 * 纯搬移，逻辑零变更。
 *
 * <p><b>为什么值得单独成类</b>：它是"提交申请"顺带做的事 ——
 * 用户填的基本信息 + 算出的评分特征，顺手写进信用档案，下次申请就不用再填。
 * 这个职责与"申请决策链"无关，混在主类里会让 68 行淹没主线逻辑。
 *
 * <p><b>⚠️ 事务语义必须注意</b>：本方法<b>不加 {@code @Transactional}</b>，
 * 依赖调用方（{@code submitApplyWithBasicInfo}）的事务。
 * 因为 Spring 的事务是<b>线程绑定</b>的，跨 bean 调用只要传播行为是默认的
 * {@code REQUIRED}，就仍然在同一个事务里 —— 档案保存失败会连带申请回滚（符合预期：
 * 档案存了但申请没成，用户会看到"申请失败"却发现自己信息已经被改过）。
 *
 * <p><b>但本方法内部 catch 掉了所有异常</b>（原逻辑如此）→ 实际上不会触发回滚。
 * 这是刻意的：<b>档案保存是"顺带优化"，失败不该阻断申请</b>。
 * 保留原行为，未做改动 —— 如果将来要改成"必须成功"，去掉 catch 即可。
 */
@Service
public class CreditProfileAutoSaveService {

    private static final Logger log = LoggerFactory.getLogger(CreditProfileAutoSaveService.class);

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;

    /**
     * 保存或更新信用档案（幂等：有则更新，无则创建）。
     *
     * @param userId        用户 ID
     * @param basicInfo     用户填写的完整信息（含个人信息/家庭/财务/职业）
     * @param userProfile   评分特征（已填充默认值）
     * @param scorecardScore 本次评分卡分数，写入 {@code credit_score}
     */
    public void saveOrUpdate(String userId, UserBasicInfoDTO basicInfo,
                             UserProfileDTO userProfile, Integer scorecardScore) {
        try {
            UserCreditProfile existing = userCreditProfileMapper.selectByUserId(userId);
            boolean isNew = (existing == null);

            if (isNew) {
                existing = new UserCreditProfile();
                existing.setUserId(userId);
                existing.setCreateTime(new Date());
            }

            // 个人信息
            UserBasicInfoDTO.PersonalInfo p = basicInfo.getPersonalInfo();
            if (p != null) {
                existing.setName(p.getName());
                existing.setIdCard(p.getIdCard());
                existing.setPhone(p.getPhone());
                existing.setEmail(p.getEmail());
                existing.setGender(p.getGender());
                existing.setEducation(p.getEducation());
                existing.setOccupation(p.getOccupation());
                if (p.getBirthDate() != null) {
                    existing.setBirthDate(java.sql.Date.valueOf(p.getBirthDate()));
                }
            }

            // JSON 序列化（注册 JavaTimeModule 支持 LocalDate）
            try {
                ObjectMapper om = new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .registerModule(new JavaTimeModule());
                if (basicInfo.getFamilyInfo() != null) {
                    existing.setFamilyInfo(om.writeValueAsString(basicInfo.getFamilyInfo()));
                }
                if (basicInfo.getFinancialInfo() != null) {
                    existing.setFinancialInfo(om.writeValueAsString(basicInfo.getFinancialInfo()));
                }
                if (basicInfo.getEmploymentInfo() != null) {
                    existing.setEmploymentInfo(om.writeValueAsString(basicInfo.getEmploymentInfo()));
                }
            } catch (Exception e) {
                log.warn("序列化档案 JSON 失败: {}", e.getMessage());
            }

            // 评分特征
            existing.setAge(userProfile.getAge());
            existing.setDebtRatio(userProfile.getDebtRatio());
            existing.setMonthlyIncome(userProfile.getMonthlyIncome());
            existing.setCreditLines(userProfile.getCreditLines());
            existing.setDependents(userProfile.getDependents());
            existing.setRevolvingUtil(userProfile.getRevolvingUtil());
            existing.setCreditScore(scorecardScore);
            existing.setUpdateTime(new Date());

            if (isNew) {
                userCreditProfileMapper.insert(existing);
            } else {
                userCreditProfileMapper.update(existing);
            }

            log.info("✅ 信用档案已自动{}", isNew ? "创建" : "更新");
        } catch (Exception e) {
            // 档案保存是顺带优化 —— 失败不阻断申请（保持原有行为）
            log.warn("⚠️ 自动保存信用档案失败（不影响申请）: {}", e.getMessage());
        }
    }
}
