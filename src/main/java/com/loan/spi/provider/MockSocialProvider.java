package com.loan.spi.provider;

import com.loan.entity.enums.SourceTypeEnum;
import com.loan.spi.DataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 社保/公积金数据源 —— 模拟 Provider（W3 实现）
 *
 * <p>按《多源数据.md》社保源口径返回标准化字段（键名即特征名）：
 * <ul>
 *   <li>{@code social_contrib_months} 连续缴纳月数（0-480）</li>
 *   <li>{@code social_base} 缴存基数（元/月）—— <b>这是月收入的唯一自动来源</b></li>
 *   <li>{@code social_employer_cnt} 缴费单位数（0-20）</li>
 * </ul>
 *
 * <p><b>为什么这个类很重要</b>：信用档案里的「月收入」在用户不手填时，
 * 唯一来源就是这里的 {@code social_base}
 * （见 {@code UserCreditProfileServiceImpl#enrichFromMultiSource}）。
 * 本类在 W2 阶段是<b>返回空 Map 的占位实现</b>，导致：
 * <pre>
 *   新用户授权 social + 同步 -> 同步结果里没有 social -> social_base 拿不到
 *   -> 档案 monthlyIncome 恒为 0 -> 模型判该特征缺失
 * </pre>
 *
 * <p>生成方式：以 userId 为种子的<b>确定性伪随机</b>（同一用户每次结果稳定），
 * 与 {@code MockOperatorProvider} / {@code MockCreditProvider} 保持一致。
 */
@Component
public class MockSocialProvider implements DataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MockSocialProvider.class);

    /** 缴存基数下限（元/月）：按最低缴费工资口径取整 */
    private static final int BASE_MIN = 3000;

    /** 缴存基数上限（元/月）：按社保缴纳上限量级 */
    private static final int BASE_MAX = 30000;

    /** 缴纳月数上限（40 年） */
    private static final int CONTRIB_CAP_MONTHS = 480;

    @Override
    public String sourceType() {
        return SourceTypeEnum.SOCIAL.getCode();
    }

    @Override
    public Map<String, Object> fetch(String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            Random random = MockCreditProvider.deterministicRandom(userId, sourceType());

            // 连续缴纳月数：多数在职人员 1~15 年；少量新参保（<12 个月）
            int contribMonths = 12 + random.nextInt(180);   // 12 ~ 191
            if (random.nextInt(100) < 12) {                  // 12% 为新参保
                contribMonths = 1 + random.nextInt(11);      // 1 ~ 11
            }
            contribMonths = Math.min(contribMonths, CONTRIB_CAP_MONTHS);

            // 缴费单位数：多数 1~2 家；跳槽多的人 3~5 家（概率低）
            int employerCnt = 1 + random.nextInt(2);         // 1 ~ 2
            if (random.nextInt(100) < 18) {
                employerCnt = 3 + random.nextInt(3);         // 3 ~ 5
            }

            // 缴存基数：与缴纳时长正相关（缴得越久通常收入越高），叠加噪声
            // 不用纯随机，让分布更接近真实社保基数
            double ratio = Math.min(1.0, contribMonths / 180.0);
            int floor = BASE_MIN + (int) Math.round((BASE_MAX - BASE_MIN) * ratio * 0.5);
            int base = floor + random.nextInt(Math.max(1, (BASE_MAX - floor) / 100)) * 100;
            base = Math.min(base, BASE_MAX);
            base = Math.round(base / 100f) * 100;   // 取整到百位（社保基数通常按百/千取整）

            data.put("social_contrib_months", contribMonths);
            data.put("social_base", base);
            data.put("social_employer_cnt", employerCnt);
            data.put("data_version", "v1");

            log.info("✅ [模拟社保公积金] 用户 {} 拉取成功：缴纳 {} 月 / 基数 {} 元 / 单位 {} 家",
                    userId, contribMonths, base, employerCnt);
        } catch (Exception e) {
            log.error("❌ [模拟社保公积金] 用户 {} 数据生成失败: {}", userId, e.getMessage(), e);
        }
        return data;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean healthCheck() {
        return true;
    }
}
