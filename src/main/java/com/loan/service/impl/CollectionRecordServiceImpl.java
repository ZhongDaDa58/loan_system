package com.loan.service.impl;

import com.loan.entity.CollectionRecord;
import com.loan.entity.CollectionTask;
import com.loan.exception.BusinessException;
import com.loan.mapper.CollectionRecordMapper;
import com.loan.mapper.CollectionTaskMapper;
import com.loan.service.CollectionRecordService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 催收记录服务实现（逾期处理功能升级 第 6 步-c）
 *
 * <p><b>为什么直接注入 Mapper 而不是 CollectionTaskService</b>：
 * 后者将来可能反过来依赖本服务（比如关闭任务时自动补一条记录），
 * 两个 Service 互相注入会形成循环依赖。这里只需要改任务的两个字段，
 * 直接用 Mapper 更简单也更安全。
 */
@Service
public class CollectionRecordServiceImpl implements CollectionRecordService {

    private static final int MAX_PAGE_SIZE = 200;

    @Resource
    private CollectionRecordMapper collectionRecordMapper;

    @Resource
    private CollectionTaskMapper collectionTaskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CollectionRecord add(String taskId, String method, String result,
                                LocalDate promisedPayDate, String remark, String operatorName) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(400, "必须指定催收任务");
        }
        if (!METHODS.contains(method)) {
            throw new BusinessException(400, "联系方式只能是：" + String.join("/", METHODS));
        }
        if (!RESULTS.contains(result)) {
            throw new BusinessException(400, "跟进结果只能是：" + String.join("/", RESULTS));
        }
        if ("promised".equals(result) && promisedPayDate == null) {
            // 承诺不写日期等于没承诺，后面没法判断"承诺违约"
            throw new BusinessException(400, "客户承诺还款时必须填写承诺还款日期");
        }

        CollectionTask task = collectionTaskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw new BusinessException(404, "催收任务不存在");
        }
        if ("closed".equals(task.getStatus())) {
            throw new BusinessException(400, "该催收任务已关闭，不能再添加跟进记录");
        }

        CollectionRecord record = new CollectionRecord();
        record.setRecordId("CR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        record.setTaskId(taskId);
        record.setUserId(task.getUserId());
        record.setMethod(method);
        record.setResult(result);
        record.setPromisedPayDate(promisedPayDate == null ? null
                : java.sql.Date.valueOf(promisedPayDate));
        record.setRemark(remark);
        record.setOperatorName(operatorName);
        collectionRecordMapper.insert(record);

        // 承诺还款 -> 同步到任务上（催收员一眼能看到"客户答应哪天还"）
        if ("promised".equals(result)) {
            collectionTaskMapper.updateFollowInfo(
                    taskId, null, "promised", null, promisedPayDate, null);
        } else if (task.getAssignedTo() == null || task.getStatus().equals("pending")) {
            // 只要有了跟进动作，任务就不再是"待跟进"
            collectionTaskMapper.updateFollowInfo(taskId, null, "in_progress", null, null, null);
        } else {
            // 只更新 last_follow_at
            collectionTaskMapper.updateFollowInfo(taskId, null, null, null, null, null);
        }

        return record;
    }

    @Override
    public List<CollectionRecord> listByTaskId(String taskId) {
        return collectionRecordMapper.selectByTaskId(taskId);
    }

    @Override
    public List<CollectionRecord> listByUserId(String userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return collectionRecordMapper.selectByUserId(userId, (safePage - 1) * safeSize, safeSize);
    }

    @Override
    public long countByUserId(String userId) {
        return collectionRecordMapper.countByUserId(userId);
    }

    @Override
    public int countPromised(String taskId) {
        return collectionRecordMapper.countPromisedByTaskId(taskId);
    }
}
