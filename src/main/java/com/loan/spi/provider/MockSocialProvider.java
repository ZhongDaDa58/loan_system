package com.loan.spi.provider;

import com.loan.entity.enums.SourceTypeEnum;
import com.loan.spi.DataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 社保/公积金数据源 —— 模拟 Provider（占位实现）
 *
 * W2：只保证注册表链路可跑通，返回空结构。
 * W3：按《多源数据.md》口径生成模拟数据（连续缴纳月数/缴存基数等）。
 */
@Component
public class MockSocialProvider implements DataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MockSocialProvider.class);

    @Override
    public String sourceType() {
        return SourceTypeEnum.SOCIAL.getCode();
    }

    @Override
    public Map<String, Object> fetch(String userId) {
        log.info("🔧 [模拟社保公积金] 拉取用户 {} 数据（W3 填充模拟逻辑）", userId);
        return new HashMap<>();
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
