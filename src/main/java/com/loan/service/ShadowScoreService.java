package com.loan.service;

import com.loan.entity.ShadowScoreLog;

import java.util.List;
import java.util.Map;

/**
 * 影子评分服务（W4 · 主线 A6）
 *
 * 职责：把"主模型已出分"的请求**异步旁路记录**下来，供影子模型对照。
 * 记录本身不影响主流程结果，也不阻塞接口响应。
 */
public interface ShadowScoreService {

    /** 异步记录一笔影子任务（状态 PENDING，含特征快照） */
    void recordAsync(ShadowScoreLog record);

    /** 待影子打分的记录（供离线批量回填拉取） */
    List<ShadowScoreLog> listPending(int limit);

    /** 最近的影子记录（含已回填的） */
    List<ShadowScoreLog> listRecent(int limit);

    /** 影子记录统计（各状态数量） */
    Map<String, Object> stats();

    /** 回填影子分数（离线批量打分后写回） */
    boolean fillShadowResult(Long id, String shadowModelKey, Integer shadowScore,
                             String shadowDecision, String note);
}
