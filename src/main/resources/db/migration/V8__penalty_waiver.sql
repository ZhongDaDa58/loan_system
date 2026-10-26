-- =============================================================================
-- V8：罚息减免（逾期处理功能升级 第 6 步-d）
-- =============================================================================
-- 背景：罚息每天在累加，但现实中总有"该减免"的情况 —— 银行系统故障导致扣款失败、
--       客户已存入但到账延迟、协商还款时的让步、金额极小的一分钱尾差。
--       开发文档对这块的要求只有一句，但很关键：
--
--           「业务常见需求，但要走审批，别直接改数」
--
--       所以本表存在的意义就是：**让"改罚息"这件事有单据、有审批人、有原因**。
--       有了它，monthly_repayment.penalty_amount 就不应该再被任何代码直接改小
--       （唯一改小的路径是「审批通过」，见 PenaltyWaiverServiceImpl#approve）。
--
-- ⚠️ 五个设计决策（不要改）：
--
--   ① 记录申请时的罚息快照（penalty_snapshot）
--      罚息每天都在涨，申请时看到 50 元、审批时可能已经 62 元。
--      存快照是为了事后能回答"当时批的是基于多少钱批的"，
--      而不是拿今天的数字去解释昨天的决定。
--      **审批通过时按 waived_amount 减，不按快照减** —— 客户申请的是具体金额。
--
--   ② 审批通过用条件 UPDATE（WHERE status='pending'）
--      两个人同时点"通过"，只有一个能成功（返回 0 的那个视为已被处理）。
--      不这么做就会减两次罚息，而罚息是钱。
--
--   ③ 记录审批人，且要求审批人 != 申请人（内控）
--      自己申请自己批 = 没有审批。这条在 Service 层强制。
--
--   ④ 一个期次同时只能有一条 pending 申请
--      两条同时通过会把罚息减两次。靠 Service 层查询校验，
--      不建部分唯一索引（MySQL 不支持 WHERE 条件索引，和 V5/V6 同一个坑）。
--
--   ⑤ 不建"直接改罚息"的接口
--      只有 waiver 这一条路径能改小 penalty_amount。
--      这是本表的核心价值 —— 如果允许直接改，这张表就没意义了。
--
-- 回滚：DROP TABLE penalty_waiver;
-- =============================================================================

CREATE TABLE IF NOT EXISTS penalty_waiver (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    waiver_id         VARCHAR(64)   NOT NULL                COMMENT '业务 ID（对外暴露）',
    user_id           VARCHAR(32)   NOT NULL                COMMENT '客户',
    repayment_id      VARCHAR(64)   NOT NULL                COMMENT '还款期次',
    plan_id           VARCHAR(64)   NULL                    COMMENT '还款计划（冗余，便于查询）',
    waived_amount     DECIMAL(10,2) NOT NULL                COMMENT '申请减免的罚息金额',
    penalty_snapshot  DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '申请那一刻的罚息余额（快照，用于事后核对）',
    reason            VARCHAR(200)  NOT NULL                COMMENT '减免原因（必填，展示给审批人）',
    status            VARCHAR(16)   NOT NULL DEFAULT 'pending'
                      COMMENT 'pending=待审批 / approved=已通过 / rejected=已驳回 / cancelled=已撤销',
    applicant         VARCHAR(64)   NOT NULL                COMMENT '申请人',
    approver          VARCHAR(64)   NULL                    COMMENT '审批人（必须与申请人不同）',
    approve_remark    VARCHAR(200)  NULL                    COMMENT '审批意见',
    approved_at       DATETIME      NULL                    COMMENT '审批时间',
    create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_waiver_id (waiver_id),
    KEY idx_repayment_status (repayment_id, status),
    KEY idx_status_time (status, create_time),
    KEY idx_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '罚息减免申请单（改罚息的唯一合法路径）';
