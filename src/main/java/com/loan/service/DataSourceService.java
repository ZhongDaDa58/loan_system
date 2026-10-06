package com.loan.service;

import com.loan.entity.dto.DataSourceAuthorizeDTO;
import com.loan.entity.vo.DataSourceAuthorizationVO;
import com.loan.entity.vo.Result;

import java.util.List;

/**
 * 多数据源授权服务（授权留痕）
 */
public interface DataSourceService {

    /**
     * 发起数据源授权
     */
    Result<?> authorize(String userId, DataSourceAuthorizeDTO dto);

    /**
     * 撤销数据源授权
     */
    Result<?> revoke(String userId, DataSourceAuthorizeDTO dto);

    /**
     * 查询当前用户各数据源授权状态（含未授权源的 PENDING 占位）
     */
    Result<List<DataSourceAuthorizationVO>> listAuthorizations(String userId);

    /**
     * 查询授权状态 VO（供其它服务复用，不含 Result 包装）
     */
    List<DataSourceAuthorizationVO> queryAuthorizationVOs(String userId);
}
