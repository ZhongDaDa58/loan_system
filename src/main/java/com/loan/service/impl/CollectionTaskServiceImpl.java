package com.loan.service.impl;

import com.loan.entity.CollectionTask;
import com.loan.exception.BusinessException;
import com.loan.mapper.CollectionTaskMapper;
import com.loan.service.CollectionTaskService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 催收任务服务实现（逾期处理功能升级 第 6 步-c）
 *
 * <p>任务的<b>自动生成</b>在 {@link CollectionTaskGenerator}（独立类+独立定时任务），
 * 这里只管人工操作：查询、认领、更新跟进、关闭。
 */
@Service
public class CollectionTaskServiceImpl implements CollectionTaskService {

    private static final Logger log = LoggerFactory.getLogger(CollectionTaskServiceImpl.class);

    /** 每页最多返回多少条 —— 防止 size 传成 100000 拉爆内存 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 合法状态（closed 只能通过 close() 进入，不能直接 updateFollow 设） */
    private static final List<String> FOLLOW_STATUSES = List.of("in_progress", "promised");

    @Resource
    private CollectionTaskMapper collectionTaskMapper;

    @Override
    public CollectionTask getByTaskId(String taskId) {
        return taskId == null ? null : collectionTaskMapper.selectByTaskId(taskId);
    }

    @Override
    public List<CollectionTask> list(String status, String assignedTo, boolean dueFollowOnly,
                                     int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return collectionTaskMapper.selectPage(status, assignedTo, dueFollowOnly,
                (safePage - 1) * safeSize, safeSize);
    }

    @Override
    public long count(String status, String assignedTo, boolean dueFollowOnly) {
        return collectionTaskMapper.countPage(status, assignedTo, dueFollowOnly);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean claim(String taskId, String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(400, "认领任务必须指定操作人");
        }
        int affected = collectionTaskMapper.updateFollowInfo(
                taskId, operator, "in_progress", null, null, null);
        if (affected > 0) {
            log.info("催收任务 {} 已被 {} 认领", taskId, operator);
        }
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateFollow(String taskId, String assignedTo, String status,
                                LocalDate nextFollowDate, LocalDate promisedPayDate) {
        if (status != null && !FOLLOW_STATUSES.contains(status)) {
            // closed 必须走 close()，否则 close_reason 会是空的
            throw new BusinessException(400,
                    "状态只能是 in_progress 或 promised；关闭请用关闭接口（需要填原因）");
        }
        int affected = collectionTaskMapper.updateFollowInfo(
                taskId, assignedTo, status, nextFollowDate, promisedPayDate, null);
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean close(String taskId, String reason) {
        if (reason == null || reason.isBlank()) {
            // 没有原因的关闭等于把问题藏起来
            throw new BusinessException(400, "关闭催收任务必须填写原因");
        }
        int affected = collectionTaskMapper.closeTask(taskId, reason);
        if (affected > 0) {
            log.info("催收任务 {} 已关闭：{}", taskId, reason);
        }
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeByRepayment(String repaymentId, String reason) {
        if (repaymentId == null || repaymentId.isBlank()) {
            return 0;
        }
        int closed = collectionTaskMapper.closeByRepaymentId(repaymentId, reason);
        if (closed > 0) {
            log.info("期次 {} 已还款，自动关闭 {} 个催收任务", repaymentId, closed);
        }
        return closed;
    }
}
