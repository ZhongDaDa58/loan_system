package com.loan.service;

import com.loan.entity.CollectionRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * 催收记录服务（逾期处理功能升级 第 6 步-c）
 */
public interface CollectionRecordService {

    /** 合法的联系方式 */
    List<String> METHODS = List.of("phone", "sms", "visit", "other");

    /** 合法的跟进结果 */
    List<String> RESULTS = List.of("connected", "no_answer", "refused", "promised", "paid", "wrong_number");

    /**
     * 记一次跟进。
     *
     * <p>当 {@code result=promised} 时：
     * <ul>
     *   <li>{@code promisedPayDate} <b>必填</b>（承诺不写日期等于没承诺）</li>
     *   <li>同时更新任务的 {@code promised_pay_date} 和状态为 {@code promised}</li>
     * </ul>
     *
     * @return 落库后的记录（含生成的 recordId）
     */
    CollectionRecord add(String taskId, String method, String result,
                         LocalDate promisedPayDate, String remark, String operatorName);

    /** 某任务的全部跟进记录（时间倒序） */
    List<CollectionRecord> listByTaskId(String taskId);

    /** 某用户的全部催收记录（跨任务） */
    List<CollectionRecord> listByUserId(String userId, int page, int size);

    /** 某用户的催收记录总数 */
    long countByUserId(String userId);

    /** 某任务下"承诺过还款"的次数 —— 回答"承诺几次、兑现几次"的分子 */
    int countPromised(String taskId);
}
