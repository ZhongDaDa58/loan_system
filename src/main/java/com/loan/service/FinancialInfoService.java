package com.loan.service;

import com.loan.entity.MockFinancialProfile;
import com.loan.entity.vo.Result;

/**
 * 经济信息获取服务（模拟"银行/征信/社保"侧数据，替代用户手填收入负债）
 *
 * 流程：预览（按身份拉取模拟金融信息）→ 确认应用（回填信用档案并重新评分 + 派生多源特征）
 */
public interface FinancialInfoService {

    /** 预览：按当前登录用户身份从模拟库拉取经济信息（含将要派生出的多源特征） */
    Result<?> preview(String userId);

    /**
     * 应用：把经济信息写入信用档案（自动重算特征与评分），并派生 credit/operator/social 多源特征
     *
     * @param dto 可选 —— App 端"获取后经用户编辑确认"的档案数据；为空时使用模拟库原始数据自动组装
     */
    Result<?> apply(String userId, com.loan.entity.dto.CreditProfileDTO dto);

    /** 管理端：查看模拟库全部记录 */
    Result<?> listMock();

    /** 管理端：新增/更新一条模拟记录（按手机号匹配） */
    Result<?> upsertMock(MockFinancialProfile profile);

    /** 管理端：删除模拟记录 */
    Result<?> deleteMock(Long id);
}
