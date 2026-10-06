package com.loan.service;

import com.loan.entity.dto.MultiSourceEventDTO;
import com.loan.entity.vo.MultiSourceProfileVO;
import com.loan.entity.vo.Result;

import java.util.List;

/**
 * 多源数据服务（摘要展示 + 事件上报预留）
 */
public interface MultiSourceService {

    /**
     * 多源数据脱敏摘要（结构先行）
     */
    Result<MultiSourceProfileVO> getProfile(String userId);

    /**
     * App 端设备/行为事件上报（预留：本期仅校验与记录日志）
     */
    Result<?> reportEvents(String userId, List<MultiSourceEventDTO> events);
}
