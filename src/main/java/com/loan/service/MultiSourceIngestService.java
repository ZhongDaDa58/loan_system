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

    /**
     * 接收 App 端上报的事件并落库为多源特征（多源"入口"打通）
     *
     * @param userId 用户 ID
     * @param events App 上报事件（eventType：device / behavior；location 暂不作为特征）
     * @return 落库统计（各源字段数、跳过事件数）
     */
    Map<String, Object> ingestEvents(String userId, java.util.List<com.loan.entity.dto.MultiSourceEventDTO> events);

    /**
     * 按数据源直接写入特征字段（清洗 → upsert），用于"模拟金融信息"等非 SPI 来源
     *
     * @param userId     用户 ID
     * @param sourceType 数据源（credit/operator/social/device/behavior）
     * @param fields     原始字段（会经白名单过滤与区间截断）
     * @return 落库结果（实际写入的字段）
     */
    Map<String, Object> ingestFields(String userId, String sourceType, Map<String, Object> fields);
}
