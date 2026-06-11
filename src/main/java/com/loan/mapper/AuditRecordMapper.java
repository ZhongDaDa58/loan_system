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
}