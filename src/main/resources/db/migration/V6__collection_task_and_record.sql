-- =============================================================================
-- V6：催收任务与催收记录（逾期处理功能升级 第 6 步-c）
-- =============================================================================
-- 背景：前几步解决了"逾期能被识别、能算罚息、能拦截、能拉黑"，
--       但**没有人去催** —— 系统知道客户逾期了，却没有任何地方能记录
--       "谁在什么时候用什么方式联系过客户、客户答应什么时候还"。
--       本迁移建立催收工作台的数据基础。
--
-- ⚠️ 六个设计决策（不要改）：
--
--   ① 任务按「期次」建，不按「用户」建
--      一个客户可能有 3 期都逾期，催收员需要分别跟（第 2 期可能已经协商好、
--      第 3 期还没谈）。按用户建会丢掉这个粒度。
--      用户级别的汇总在查询时按 user_id 聚合即可。
--
--   ② 不建唯一索引，靠 INSERT ... WHERE NOT EXISTS 保证"一期最多一条 open 任务"
--      和 V5 黑名单踩过的坑一样：MySQL 不支持部分索引，
--      而 UNIQUE(repayment_id, status) 会让多条 closed 历史互相冲突。
--
--   ③ 承诺还款日放 task 表（当前有效）+ record 表（每次承诺的历史）
--      task.promised_pay_date 是"最新一次承诺"，催收员一眼能看到；
--      collection_record.promised_pay_date 是流水，用于追溯"承诺过几次、兑现几次"。
--      只存一处都不行：只存 record 要每次聚合，只存 task 会丢历史。
--
--   ④ priority 用数字（1高/2中/3低）而不是直接存等级
--      M3 未必一定是最高优先级（长期失联的老案子可能人工调高）。
--      生成时按等级给默认值，之后允许人工调整。
--
--   ⑤ close_reason 必填（Service 层校验）
--      任务关闭总得有个说法："已还款" / "已核销" / "误判"。没有原因的关闭
--      等于把问题藏起来。
--
--   ⑥ method / result 用 VARCHAR 而不是 ENUM
--      ENUM 加值要 ALTER TABLE，催收手段（微信、上门、法务）以后肯定还会加。
--      取值约束放在 Service 层的常量里。
--
-- 回滚：DROP TABLE collection_record; DROP TABLE collection_task;
--       （两张表互不引用外键，collection_record.task_id 只是逻辑关联）
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 催收任务
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collection_task (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id           VARCHAR(64)  NOT NULL                COMMENT '业务 ID（对外暴露）',
    user_id           VARCHAR(32)  NOT NULL                COMMENT '欠款用户',
    repayment_id      VARCHAR(64)  NOT NULL                COMMENT '对应的还款期次',
    plan_id           VARCHAR(64)  NOT NULL                COMMENT '还款计划 ID（冗余，省一次 JOIN）',
    overdue_level     VARCHAR(8)   NULL                    COMMENT '生成时的逾期等级 M1/M2/M3',
    overdue_days      INT          NOT NULL DEFAULT 0      COMMENT '生成时的逾期天数（快照）',
    priority          TINYINT      NOT NULL DEFAULT 2      COMMENT '1=高 2=中 3=低，生成时按等级给默认值，可人工调整',
    status            VARCHAR(16)  NOT NULL DEFAULT 'pending'
                      COMMENT 'pending=待跟进 / in_progress=跟进中 / promised=已承诺 / closed=已关闭',
    assigned_to       VARCHAR(64)  NULL                    COMMENT '负责的催收员',
    next_follow_date  DATE         NULL                    COMMENT '下次跟进日期（到期该任务会出现在"今日待办"）',
    promised_pay_date DATE         NULL                    COMMENT '最新一次客户承诺的还款日',
    promise_violated  TINYINT      NOT NULL DEFAULT 0      COMMENT '承诺是否已违约（1=违约，可用于优先处理）',
    last_follow_at    DATETIME     NULL                    COMMENT '最后一次跟进时间',
    close_reason      VARCHAR(200) NULL                    COMMENT '关闭原因（关闭时必填）',
    closed_at         DATETIME     NULL                    COMMENT '关闭时间',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_user_status (user_id, status),
    KEY idx_status_next (status, next_follow_date),
    KEY idx_repayment (repayment_id),
    KEY idx_assigned (assigned_to, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '催收任务（按还款期次建，不是按用户）';

-- ---------------------------------------------------------------------------
-- 催收记录（每次跟进的留痕）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collection_record (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id         VARCHAR(64)  NOT NULL                COMMENT '业务 ID',
    task_id           VARCHAR(64)  NOT NULL                COMMENT '所属催收任务',
    user_id           VARCHAR(32)  NOT NULL                COMMENT '欠款用户（冗余，便于按用户查全量记录）',
    method            VARCHAR(16)  NOT NULL                COMMENT '联系方式：phone/sms/visit/other（约束在 Service 层）',
    result            VARCHAR(32)  NOT NULL
                      COMMENT '结果：connected/no_answer/refused/promised/paid/wrong_number',
    promised_pay_date DATE         NULL                    COMMENT '本次承诺的还款日（仅 result=promised 时有意义）',
    remark            VARCHAR(500) NULL                    COMMENT '备注（客户说了什么）',
    operator_name     VARCHAR(64)  NULL                    COMMENT '催收员',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_id (record_id),
    KEY idx_task (task_id),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '催收记录（电话/短信/上门的留痕）';
