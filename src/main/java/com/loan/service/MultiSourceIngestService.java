package com.loan.service;

import java.util.Map;

/**
 * 多源数据接入服务（W3 · B 线）
 *
 * 职责：把 SPI 层各数据源拉取到的原始数据，经"标准化清洗"后落库到
 * `user_multi_source_data`，并支持按数据源或全量同步。
 */
public interface MultiSourceIngestService {

    /**
     * 同步并落库用户多源数据。
     *
     * @param userId     用户 ID
     * @param sourceType 数据源类型（credit/operator/social/device）；为空则同步全部已注册源
     * @return 每源的同步结果（状态/字段数/时间）
     */
    Map<String, Object> sync(String userId, String sourceType);

    /**
     * 读取用户多源特征宽表快照（各源字段合并，供评分卡/展示使用）。
     */
    Map<String, Object> featureSnapshot(String userId);
}
