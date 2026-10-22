package com.loan.mapper;
import com.loan.entity.AuditRecord;
import com.loan.entity.vo.PendingAuditVO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AuditRecordMapper {
    // 保存审核记录
    int insert(AuditRecord auditRecord);

    // 查询待审核列表（状态为pending的申请）
    List<PendingAuditVO> selectPendingList();

    /**
     * 查询某审核员的历史审核记录，可按时间区间过滤并支持分页
     */
    java.util.List<com.loan.entity.vo.AuditHistoryVO> selectByAuditorHistory(@org.apache.ibatis.annotations.Param("auditorId") String auditorId,
                                                                                @org.apache.ibatis.annotations.Param("startDate") String startDate,
                                                                                @org.apache.ibatis.annotations.Param("endDate") String endDate,
                                                                                @org.apache.ibatis.annotations.Param("offset") int offset,
                                                                                @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * S1：导出「特征 + Y 标签」样本（设计 §8 人工结论回流训练库）
     *
     * <p>只返回**已标注**（{@code final_label} 非空）的审核记录，并关联
     * {@code risk_decision_log} 取出当时实际装配的特征快照。
     */
    java.util.List<java.util.Map<String, Object>> selectLabeledSamples(@Param("limit") int limit);
}