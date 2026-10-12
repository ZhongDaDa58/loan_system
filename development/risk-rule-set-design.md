# 风险规则层 · 规则集设计（Rule Set Design）

> 版本：V1.0（设计稿）
> 定位：补齐设计文档第一层（反欺诈/信息校验）与第三层（策略修正）中的**规则判定**部分
> 关联：《多数据融合评分卡设计-最终版.md》（四层架构）、《多源数据.md》（字段口径）、`loan/development/*-api.md`（接口）
> 现状说明：**当前代码中不存在规则集**（无 Drools、无 `risk_rule` 表），仅 `RiskControlUtil` 的三档阈值判定 + 产品级 `auto_pass_score/manual_review_score`；本设计为**待实现**方案。

---

## 1. 设计原则

| 原则 | 说明 |
|---|---|
| **分层执行** | **L1 前置拦截**在评分**之前**（不通过则不评分）；**L3 后置修正**在评分**之后**（只改处置动作，不改分数） |
| **组合优于单点** | 单条件只**标记（F）**，**两个及以上条件组合**才**拒绝（R）**——控制误杀 |
| **可配置** | 阈值全部落在配置/表里，支持热更新，不写死在代码 |
| **可解释留痕** | 每次命中写 `RiskDecisionLog`（规则 ID + 命中条件 + 动作），可审计、可统计 |
| **先影子后生效** | 新规则先**只记录不生效**运行一段时间，观察命中率与误杀率后再启用 |
| **与模型解耦** | 规则层用**原始/瞬时信号**；模型层用**稳定特征**；两者可迁移（漂移特征降级到规则层，稳定规则特征转正入模） |

---

## 2. 规则集（基于现有字段，真实可落地）

> 字段名均取自《多源数据.md》与现有实现（`MultiSourceIngestServiceImpl` 的字段白名单与区间规则）。
> 阈值为**建议初始值**，需用历史数据回放校准。

### 2.1 L1 前置拦截（评分前执行）

| ID | 规则名 | 触发条件（真实字段） | 动作 | 业务依据 | 优先级 |
|---|---|---|---|---|---|
| **L1-01** | 模拟器/非真机 | `device_emulator_flag = 1` | **拒绝** | 非真机环境是团伙欺诈的强特征 | P0 |
| **L1-02** | 设备黑名单即时命中 | `device_blacklist_hit_now = 1` | **拒绝** | 已知欺诈设备 | P0 |
| **L1-03** | 设备集群欺诈 | `device_related_app_cnt_30d ≥ 5` **AND** `device_multi_account_now = 1` | **拒绝** | 一机多号聚集，典型中介/团伙 | P0 |
| **L1-04** | IP 代理 + 属地冲突 | `ip_proxy_risk ≥ 0.8` **AND** `ip_geo_mismatch = 1` | **拒绝** | 网络环境伪造 | P0 |
| **L1-05** | 位置严重矛盾 | `gps_deviation_km_longterm ≥ 200` **AND** `ip_geo_mismatch = 1` | **转人工** | 资料与位置严重不符 | P1 |
| **L1-06** | 资料包装嫌疑 | `behavior_anomaly_score ≥ 0.8` **AND**（`ip_proxy_risk ≥ 0.6` **OR** `gps_deviation_km_longterm ≥ 100`） | **转人工** | 行为异常 + 环境异常组合 | P1 |
| **L1-07** | 行为脚本化 | 上报事件 `form_fill_ms < 5000` **AND** `field_count ≥ 10` | **转人工** | 填表速度异常快，疑似脚本/代办 | P1 |
| **L1-08** | 征信多头借贷激增 | `credit_query_cnt_3m ≥ 15` | **转人工** | 短时间密集查询 | P1 |
| **L1-09** | 黑名单历史上限 | `device_blacklist_hit_cnt ≥ 3` | **转人工** | 多次历史命中 | P1 |

**L1 输出**：`REJECT`（直接拒绝，不进入评分）/ `MANUAL`（转人工，可跳过评分或先评分再人工）/ `PASS`（进入特征工程与评分）。

### 2.2 L3 后置修正（评分后执行，不改分数）

| ID | 规则名 | 触发条件 | 动作 | 说明 |
|---|---|---|---|---|
| **L3-01** | 达标但设备聚集 | `score ≥ autoPass` **AND** `device_related_app_cnt_30d ≥ 3` | **转人工** | 分数好但设备可疑 |
| **L3-02** | 达标但多头借贷 | `score ≥ autoPass` **AND** `credit_query_cnt_3m ≥ 10` | **降额 50%** | 控制敞口 |
| **L3-03** | 达标但负债过高 | `score ≥ autoPass` **AND** `debt_to_income ≥ 0.8` | **降额 / 转人工** | 还款压力大 |
| **L3-04** | 达标但有历史逾期 | `score ≥ autoPass` **AND** `hist_overdue_cnt_m1 ≥ 1` | **降额 / 提价** | 历史行为证据 |
| **L3-05** | 达标但行为异常 | `score ≥ autoPass` **AND** `behavior_anomaly_score ≥ 0.5` | **标记 + 人工抽检** | 弱信号，只抽检 |
| **L3-06** | 新号风险 | `score ≥ autoPass` **AND** `operator_realname_months < 3` | **转人工** | 实名/在网时间极短 |
| **L3-07** | 额度使用率过高 | `score ≥ autoPass` **AND** `revolving_util ≥ 0.9` | **降额** | 现金流紧张 |
| **L3-08** | 社保断缴（待 social 接入） | `social_contrib_months = 0` **AND** `age ≥ 30` | **转人工** | 收入稳定性存疑 |

**L3 输出**：在 `AUTO_APPROVE` 基础上产出 `转人工 / 降额 / 提价 / 保持`，**分数不变**（保证模型结果权威与可解释）。

### 2.3 F 组：仅标记（不影响决策，用于观察与后续转正）

| ID | 条件 | 用途 |
|---|---|---|
| F-01 | `device_first_seen_months < 3` | 新设备观察期 |
| F-02 | `operator_tenure_months > operator_realname_months` | **数据质量告警**（自洽性被破坏） |
| F-03 | `gps_deviation_km_longterm ≥ 50` | 位置偏离观察 |
| F-04 | 落选特征命中（如 `hist_overdue_cnt_m2/m3 ≥ 1`） | 观察落选特征是否有增量价值 |

---

## 3. 阈值配置（建议初始值，需回放校准）

| 参数 | 建议值 | 说明 |
|---|---|---|
| `device_related_app_cnt_30d.reject` | ≥ 5 | 集群欺诈拒绝线 |
| `device_related_app_cnt_30d.manual` | ≥ 3 | 转人工线 |
| `ip_proxy_risk.high` | ≥ 0.8 | 高代理风险 |
| `gps_deviation_km_longterm.manual` | ≥ 200 | 严重偏离 |
| `behavior_anomaly_score.high` | ≥ 0.8 | 行为异常 |
| `credit_query_cnt_3m.manual` | ≥ 15 | 多头借贷 |
| `device_blacklist_hit_cnt.manual` | ≥ 3 | 历史命中 |
| `form_fill_ms.fast` | < 5000 | 填表过快（配合字段数） |

> 全部参数建议落到配置表 `risk_rule_config`（或 YAML），支持**在线调整、即时生效**（不重训模型）。

---

## 4. 执行位置与数据流

```
申请提交 ─► 【L1 规则集】──REJECT──► 直接拒绝（写留痕，不评分）
                 │PASS              │MANUAL
                 ▼                  ▼
        特征工程层 → 评分卡（LR+WOE）  ─► 【L3 规则集】──► 最终决策（通过/转人工/降额/拒绝）
                 │                                      │
                 └─────────────► RiskDecisionLog ◄───────┘
                       （记录：模型版本、分数、命中规则 ID、命中条件、动作）
```

| 接入点（现有代码） | 改动 |
|---|---|
| `LoanApplicationServiceImpl`（申请提交 → 评分 → 决策映射） | 评分前插 **L1**；`mapScorecardResultToDecisionCode()` 前插 **L3** |
| `MultiSourceIngestService.featureSnapshot(userId)` | 作为规则判定的**入参来源**（已存在，无需改动） |
| `RiskDecisionLog` | 复用现有留痕表，新增字段或放入 `decisionReason` / `featureSnapshot`（建议加 `hit_rules` 列） |
| `RiskControlUtil` | 保留三档阈值判定，作为规则集的**兜底** |

---

## 5. 留痕与监控指标

| 指标 | 定义 | 用途 |
|---|---|---|
| 规则命中率 | 命中数 / 总申请数（按规则 ID 分组） | 识别"从不命中"（可下线）与"命中过多"（阈值过松） |
| 误杀率 | 命中后被人工判为**通过**的比例 | **规则质量的核心指标**，>20% 需调阈值 |
| 与评分卡分歧率 | 规则动作与模型决策不一致的比例 | 观察规则层与模型层的边界 |
| 规则覆盖率 | 有规则命中的申请占比 | 衡量规则层的实际作用面 |

> 建议：新规则先**影子运行**（只记录不生效）2–4 周，收集上述指标后再启用/转正。

---

## 6. 数据结构（可选两种实现）

**方案 A（最小可用，无需建表）**：规则以 `if/else` + 配置常量实现，命中写留痕
- 优点：改动小、无依赖；缺点：调整需重启

**方案 B（可配置，推荐中期）**：`risk_rule` 表 + 缓存 + 管理端 CRUD（Web 已有 `RuleConfig.vue` 与 `/risk/rules` 调用，后端补齐即可接通）

```sql
CREATE TABLE risk_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_code VARCHAR(32) NOT NULL,          -- L1-01
  rule_name VARCHAR(64) NOT NULL,
  layer VARCHAR(8) NOT NULL,               -- L1 / L3
  source_type VARCHAR(32),                 -- device / behavior / credit / operator / social
  condition_expr VARCHAR(512) NOT NULL,    -- 条件表达式（字段+阈值+AND/OR）
  action VARCHAR(24) NOT NULL,             -- REJECT / MANUAL / DOWNGRADE / FLAG
  priority INT NOT NULL DEFAULT 100,
  enabled TINYINT NOT NULL DEFAULT 0,      -- 默认关闭，影子运行后开启
  shadow_mode TINYINT NOT NULL DEFAULT 1,  -- 1=只记录不生效
  remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rule_code (rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险规则表';
```

---

## 7. 与模型层的协同（可迁移）

| 方向 | 场景 | 动作 |
|---|---|---|
| 规则 → 模型 | 某规则长期命中、误杀率低、且特征稳定 | 该特征**转正入模**（进入 IV/PSI 门禁） |
| 模型 → 规则 | 入模特征 PSI 漂移（不稳定） | 该特征**降级**回规则层使用 |
| 共同兜底 | 模型未覆盖的极端情况 | 规则层兜底（如黑名单即时命中不需要模型） |

---

## 8. 待补齐的前置条件（如实标注）

| 依赖 | 现状 | 影响规则 |
|---|---|---|
| `social_*` 字段 | **本期未接入**（Provider 占位） | L3-08 无法生效 |
| `form_fill_ms` / `field_count` 事件 | App 端已实现（填表埋点上报） | L1-07 可用；`field_count` 当前传 0，需补真实统计 |
| `gps_addr_distance_once` | 未实现（需后端地理编码） | 位置类规则暂用 `gps_deviation_km_longterm` 替代 |
| `ip_geo_mismatch` / `ip_proxy_risk` | 字段存在（device/behavior 源） | L1-04/L1-05 可用 |
| 黑名单库 | 当前为模拟 Provider 的字段 | L1-02 依赖真实黑名单或模拟数据 |

---

**文档结束**
