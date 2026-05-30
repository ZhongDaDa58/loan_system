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
}