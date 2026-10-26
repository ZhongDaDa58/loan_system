-- =============================================================================
-- V5：逾期黑名单（逾期处理功能升级 第 6 步）
-- =============================================================================
-- 背景：前 5 步做完后，M2/M3 逾期已经能被准入拦截（ApplicationEligibilityChecker）。
--       但拦截是"当场算"的 —— 一旦客户把欠款还清，就能立刻再借。
--       现实中严重逾期（M3）的客户即使结清，也应该有一段观察期。
--       这就是黑名单要解决的问题：把"曾经严重逾期"这个事实**记下来**，
--       而不是每次现算。
--
-- ⚠️ 四个设计决策（不要改）：
--
--   ① 和"设备黑名单"是两个维度，不要合并
--      设备黑名单管的是"这台设备有问题"（模拟器、多账号、骗贷团伙），
--      逾期黑名单管的是"这个用户有严重逾期历史"。同一个用户可能换设备继续申请，
--      同一台设备也可能被清白用户使用 —— 混在一起会导致误杀。
--      本表当前没有同行表（项目里目前不存在 device_blacklist），所以是新增而非扩展。
--
--   ② 不建唯一索引，靠 Service 层保证"一个用户最多一条 active"
--      MySQL 不支持部分索引（WHERE status='active'），
--      而 UNIQUE(user_id, status) 会让多条 released 历史记录互相冲突
--      （第二次解除时唯一键就炸了）。所以用普通索引 + 应用层检查。
--      并发下的兜底：加记录时用 INSERT ... WHERE NOT EXISTS 的写法（Service 层实现）。
--
--   ③ 过期用 expire_at 判断，**不自动改 status**
--      status 只反映"人工/系统是否解除"，过期是时间到了自然失效。
--      查询时用 status='active' AND (expire_at IS NULL OR expire_at > NOW())。
--      这样历史可追溯，也不会有一个"每天要跑任务改状态"的隐性依赖。
--
--   ④ 记录 related_repayment_id 和 overdue_level
--      出了问题能回答"这笔黑名单是哪一期逾期、按哪一级加进去的"，
--      而不是只留一句原因。
--
-- 回滚：DROP TABLE overdue_blacklist;（本表不被其他表外键引用，可安全回滚）
-- =============================================================================

CREATE TABLE IF NOT EXISTS overdue_blacklist (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id               VARCHAR(32)  NOT NULL                COMMENT '被拉黑的用户 ID',
    reason                VARCHAR(200) NOT NULL                COMMENT '加入原因（展示给审核员）',
    overdue_level         VARCHAR(8)   NULL                    COMMENT '触发时的逾期等级 M1/M2/M3',
    related_repayment_id  VARCHAR(64)  NULL                    COMMENT '触发的逾期期次 ID',
    status                VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active=生效中 / released=已解除',
    effective_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
    expire_at             DATETIME     NULL                    COMMENT '到期时间，NULL 表示永久',
    released_at           DATETIME     NULL                    COMMENT '解除时间',
    release_reason        VARCHAR(200) NULL                    COMMENT '解除原因',
    operator_name         VARCHAR(64)  NULL                    COMMENT '操作人（人工加入/解除时记录）',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_status_expire (status, expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '逾期黑名单（与风控设备黑名单是不同维度）';
