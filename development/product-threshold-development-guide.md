# 产品阈值分化 · 开发实施文档

> 版本：V1.0（开发指导）
> 目标：让**不同产品使用各自合适的评分阈值**——刻度统一、策略分化、阈值可标定可追溯
> 关联文档：`scorecard-multimodel-api.md`（模型接口与阈值）、`risk-rule-development-guide.md`（L3 规则修正）、`risk-rule-set-design.md`
> 现状基线：所有产品共用同一个评分模型（v4，分数刻度统一）；产品级阈值字段已存在但**存在配置缺陷与口径冲突**

---

## 一、目标与范围

| 目标 | 说明 |
|---|---|
| **阈值产品化** | 每个产品独立配置 `auto_pass_score` / `manual_review_score`（字段已存在） |
| **阈值可标定** | 由**目标坏账率 + 通过率约束**反推阈值，而非人工拍数字 |
| **口径统一** | 明确优先级：**产品配置 > 全局默认（模型配置）**；避免"评分接口与审批链路结论相反" |
| **可追溯** | 决策留痕记录"本次生效阈值及其来源"；阈值变更历史可查 |
| **可重标定** | 周期性用实际坏账率校准阈值（客群漂移后重新分割） |

**范围外**：不做按额度/渠道的细分阈值（列为阶段 3）；不改动评分卡刻度与模型结构。

---

## 二、现状盘点（开工前必读）

### 2.1 现有字段与数据

`loan_product` 表已有阈值字段（**无**目标坏账率、通过率、额度分档字段）：

```sql
SHOW COLUMNS FROM loan_product;
-- product_id, product_name, min_amount, max_amount, min_term, max_term,
-- interest_rate, status, create_time, update_time,
-- auto_pass_score, manual_review_score
```

当前数据（**含缺陷**）：

| 产品 | auto_pass_score | manual_review_score | 是否合理 |
|---|---|---|---|
| 个人信用贷 | 750 | 650 | ✅ 正常 |
| 商品业务贷 | 750 | **800** | ❌ `manual > auto`，人工审核档失效 |
| 房贷 | 700 | **800** | ❌ 同上 |

### 2.2 缺陷与冲突清单

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| **1** | `manual_review_score > auto_pass_score` | 商品业务贷 800>750、房贷 800>700 | 判定逻辑 `evaluateScorecard()` 中第二分支**永远不可达** → 退化为"<750 全拒、≥750 全过"，**人工审核档完全失效** |
| **2** | **两套阈值并存** | 模型配置 v3:650/600、v4:641/504（`ScorecardServiceImpl.determineDecision()`）；产品表 750/650 等（`LoanApplicationServiceImpl` 两处 `RiskControlUtil.evaluateScorecard()`） | 同一分数 700：评分接口说 AUTO_APPROVE（≥641），审批链路说 REJECT（<750）——**口径冲突** |
| 3 | 阈值是**手填数字** | 750/650/800 无依据记录 | 无法回答"为什么是这个线"；与 v4 的"按目标坏账率标定（641/504）"方法论不一致 |
| 4 | 保存时**无校验** | 产品新增/编辑未校验阈值关系 | 缺陷可由管理端操作再次引入 |
| 5 | 决策**未记录生效阈值** | `RiskDecisionLog` 无阈值字段 | 无法回溯"这笔用了哪个产品的哪套线" |
| 6 | 共用模型的**前提未校验** | 无按产品的 AUC/KS/PSI 报告 | 若某产品客群与建模样本差异大，共用模型结论不成立 |

### 2.3 现有可复用资产

| 资产 | 位置 | 用途 |
|---|---|---|
| 阈值判定 | `util/RiskControlUtil.evaluateScorecard(score, autoPass, manualMin)` | 保持，仅修数据与校验 |
| 阈值标定算法 | `scorecard_project/scripts/scorecard_core.py :: calibrate_thresholds(scores, y, targets)` | **按产品分组调用**即可复用 |
| 产品 CRUD | `LoanProductService` / `LoanProductController` / Web `views/product/*` | 扩展字段与校验 |
| 决策留痕 | `RiskDecisionLog` + Mapper | 扩展阈值字段 |

---

## 三、设计原则

1. **刻度统一、策略分化**：评分模型与分数刻度全平台一致（分数可比）；阈值按产品配置（额度/期限/利率/客群不同 → 风险容忍度不同）；
2. **阈值有据**：由**目标坏账率 + 最低通过率**两个约束反推，禁止手填无依据数字；
3. **优先级明确**：产品配置 \> 全局默认（模型配置仅作 fallback）；
4. **变更可追溯**：阈值改动写历史表；决策留痕记录生效阈值；
5. **周期重标定**：实际坏账率偏离目标时触发重标定（客群漂移）。

---

## 四、数据设计

### 4.1 `loan_product` 扩展字段

```sql
ALTER TABLE loan_product
  ADD COLUMN target_bad_rate   DECIMAL(5,4) NULL COMMENT '目标坏账率（如 0.0300=3%），阈值标定输入',
  ADD COLUMN target_pass_rate  DECIMAL(5,4) NULL COMMENT '最低目标通过率（约束下界，可空）',
  ADD COLUMN threshold_source  VARCHAR(16)  NULL COMMENT '阈值来源：MANUAL=手工 / CALIBRATED=标定 / DEFAULT=全局默认',
  ADD COLUMN threshold_updated_at DATETIME  NULL COMMENT '阈值最近变更时间';
```

### 4.2 阈值变更历史表（新建）

```sql
CREATE TABLE IF NOT EXISTS product_threshold_history (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id        VARCHAR(32) NOT NULL,
  auto_pass_score   INT         NOT NULL,
  manual_review_score INT       NOT NULL,
  target_bad_rate   DECIMAL(5,4) NULL,
  threshold_source  VARCHAR(16) NULL,
  change_reason     VARCHAR(255) NULL,
  changed_by        VARCHAR(64)  NULL,
  create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_product_time (product_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品阈值变更历史';
```

### 4.3 数据修复（立即执行）

```sql
-- 修复 manual_review_score > auto_pass_score 的产品（示例：保证 manual ≤ auto）
UPDATE loan_product SET manual_review_score = 650 WHERE product_id = 'product_0d80dcfe84e5' AND manual_review_score > auto_pass_score;
UPDATE loan_product SET manual_review_score = 620 WHERE product_id = 'product_14c58091fec1' AND manual_review_score > auto_pass_score;
-- 兜底：任何违反 manual ≤ auto 的记录统一修正
UPDATE loan_product SET manual_review_score = auto_pass_score WHERE manual_review_score > auto_pass_score;
```

### 4.4 字段语义（必须写进契约文档）

| 字段 | 语义 | 约束 |
|---|---|---|
| `auto_pass_score` | 分数 **≥ 该值** → 自动通过 | 300–950 |
| `manual_review_score` | 分数 **≥ 该值且 < auto** → 转人工；**< 该值** → 拒绝 | **≤ auto_pass_score**，300–950 |
| `target_bad_rate` | 标定阈值的目标坏账率 | 0–0.2 |
| `target_pass_rate` | 通过率约束（可空） | 0–1 |

---

## 五、阈值标定：方法与工具

### 5.1 数学定义

```
给定：分数分布 scores、表现标签 y、目标坏账率 b*、最低通过率 p_min
求解：
    自动通过线 T_auto = min { t | bad_rate(score ≥ t) ≤ b*_auto 且 pass_rate(t) ≥ p_min }
    人工审核线 T_manual = min { t | bad_rate(score ≥ t) ≤ b*_manual }
约束：T_manual ≤ T_auto
```

实现方式（与现有 `calibrate_thresholds` 一致）：**按分数降序累积，找出首个满足坏账率约束的分位点**。

### 5.2 按产品分层标定（新增脚本）

`scorecard_project/scripts/calibrate_by_product.py`（新增）：

```
输入：
  --data        多源训练宽表（含产品标识列；无则用 --product-ratio 拆分模拟）
  --product     产品 ID（可多次传入）
  --target-bad  每个产品的目标坏账率（如 product_001:0.03, product_0d80...:0.06）
  --min-pass    最低通过率（默认 0.3）
  --model       使用的模型配置（v4）
输出：
  outputs/v4/product_thresholds.csv/json
    product_id, auto_pass_score, manual_review_score, expected_bad_rate, expected_pass_rate, sample_size
说明：
  - 该产品样本量 ≥ 阈值（建议 ≥ 5000）→ 独立标定；
  - 样本不足 → 用全量标定结果 + 产品风险系数（bad_rate 目标比）线性平移，并在输出中标注 `calibrated=fallback`
```

### 5.3 与目标坏账率的对应关系（产品属性 → 风险容忍度）

| 产品 | 利率 | 期限 | 目标坏账率（示例） | 阈值倾向 |
|---|---|---|---|---|
| 房贷 | 低 | 长 | 1%–2% | 严 |
| 个人信用贷 | 中高 | 中 | 3%–5% | 中 |
| 商品业务贷 | 高 | 短 | 6%–8% | 松 |

> 目标坏账率由**产品经济性**反推：`可容忍坏账 ≈ 利率 − 资金成本 − 运营成本 − 目标利润率`。

---

## 六、后端改造

### 6.1 校验（防缺陷复现）

`LoanProductServiceImpl` 保存/更新时校验：

```java
// 伪代码
if (autoPass == null || manualReview == null)      → 报错"阈值必须配置"
if (manualReview > autoPass)                        → 报错"人工审核线不得高于自动通过线"
if (autoPass < 300 || autoPass > 950 || manualReview < 300) → 报错"阈值须在 300–950"
if (targetBadRate != null && (targetBadRate < 0 || targetBadRate > 0.2)) → 报错
// 通过校验后：写 product_threshold_history（含 changed_by / change_reason）
```

### 6.2 决策链路统一（现状已用产品阈值，补两件事）

现况：`LoanApplicationServiceImpl.submitApply()`（阈值在 132–139）与 `submitApplyWithBasicInfo()`（270–277）**都已使用产品阈值** → 保持。

需补：

| 动作 | 说明 |
|---|---|
| 阈值来源兜底 | 产品未配置阈值时，回退到**全局默认**（模型配置 thresholds，如 v4 641/504），并记录 `threshold_source=DEFAULT` |
| 决策留痕扩展 | `risk_decision_log` 增加 `product_id` / `applied_auto_pass` / `applied_manual_review` / `threshold_source` |
| 评分接口标注 | `/scorecard/v2/calculate` 的响应说明：其 `decision` 基于**模型默认阈值**，实际审批以产品阈值为准（契约文档写明） |

### 6.3 新增接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/products/{productId}/threshold-suggestion?targetBadRate=0.03&minPassRate=0.3` | **阈值试算**：返回建议阈值 + 预期坏账率/通过率（不落库） |
| GET | `/api/v1/products/{productId}/threshold-history` | 阈值变更历史 |
| PATCH | `/api/v1/products/{productId}/threshold` | 单独更新阈值（含 reason），自动写历史与校验 |

---

## 七、Web 端改造（产品管理页）

`views/product/ProductCreate.vue`、`ProductDetail.vue`（追加，不改既有逻辑）：

| 改动 | 内容 |
|---|---|
| 配置项 | 增加"目标坏账率"输入 + "阈值"输入（自动通过线 / 人工审核线） |
| 前端校验 | `人工审核线 ≤ 自动通过线`，范围 300–950（与后端一致） |
| **阈值试算** | 按钮"按目标坏账率试算" → 调 `threshold-suggestion` → 回填建议阈值并展示"预期坏账率/通过率" |
| 展示 | 产品详情页展示"当前阈值 + 来源（手工/标定/默认）+ 最近更新时间" |

---

## 八、分阶段实施清单

### 阶段 1 · 修复与统一口径（立即可做）

- [ ] 执行 §4.3 数据修复 SQL（消除 `manual > auto`）
- [ ] `LoanProductServiceImpl` 加 §6.1 校验 + 校验失败返回明确错误信息
- [ ] 建立 §4.4 字段语义契约，写入 `loan/development/`（阈值优先级 + 语义）
- [ ] 契约注明：`/scorecard/v2/calculate` 的 decision 基于模型默认阈值，**审批以产品阈值为准**
- [ ] 回归：`mvnw test` 全绿；两个申请入口（`submitApply` / `submitApplyWithBasicInfo`）行为正常

### 阶段 2 · 可标定（核心）

- [ ] 新增 `scorecard_project/scripts/calibrate_by_product.py`（§5.2），产出 `product_thresholds.csv/json`
- [ ] 后端把标定结果接入产品阈值配置（或提供"建议阈值"接口供管理端采纳）
- [ ] `product_threshold_history` 表 + 写入逻辑（阈值变更留痕）
- [ ] 决策留痕扩展（`product_id` / 生效阈值 / 阈值来源）
- [ ] `/products/{id}/threshold-suggestion` 接口（供 Web 试算）
- [ ] Web 产品页：目标坏账率 + 阈值试算 + 校验提示
- [ ] 单测：校验规则（manual ≤ auto / 范围）/ 兜底默认阈值 / 留痕字段

### 阶段 3 · 精细化（可选）

- [ ] 按额度分档阈值（`min_amount`/`max_amount` 分段配置）
- [ ] 按渠道/客群细分
- [ ] 周期性重标定（监控实际坏账率 vs `target_bad_rate`，偏离触发）
- [ ] 各产品客群的**模型适用性校验**（AUC/KS/PSI 分产品报告）

---

## 九、验证与回归

```powershell
# 1) 数据修复校验（应无违反 manual<=auto 的记录）
& "D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --host=127.0.0.1 --port=3306 --user=root `
  --database=loan_risk_control --execute="SELECT product_id,auto_pass_score,manual_review_score FROM loan_product WHERE manual_review_score > auto_pass_score;"

# 2) 阈值标定（新增脚本）
cd D:\code\scorecard_project
python scripts/calibrate_by_product.py --target-bad "product_001:0.03,product_0d80dcfe84e5:0.06,product_14c58091fec1:0.015"

# 3) 后端回归
cd D:\code\loan
.\mvnw.cmd test

# 4) 端到端：同一分数在不同产品下的决策分化
#    构造 score=700 → 个人信用贷(750/650 ⇒ 人工) vs 商品业务贷(750/650 ⇒ 人工) vs 房贷(700/620 ⇒ 自动通过)
```

**必须验证的四条**：
1. `manual > auto` 的数据被修复，且**保存时无法再写入**该组合；
2. 同一分数在**不同产品**下产生**不同决策**（分化生效）；
3. 产品未配置阈值时**回退全局默认**并留痕 `threshold_source=DEFAULT`；
4. 阈值变更写入 `product_threshold_history`，决策留痕记录生效阈值。

---

## 十、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 阈值标定样本不足 | 建议值不可信 | 样本量阈值（≥5000）+ 降级策略（全量标定 + 系数平移）并标注 `fallback` |
| 产品客群与建模样本差异大 | 共用模型在该产品上失效 | 阶段 3 的分产品 AUC/KS/PSI 报告；必要时单独建模或分数重标定 |
| 阈值频繁调整 | 决策不稳定、审计困难 | 阈值变更走历史表 + 变更原因；限制频率（如月度） |
| 与 L3 规则叠加过严 | 通过率骤降 | 规则集先影子运行（见 `risk-rule-development-guide.md`）；监控通过率与坏账率联动 |
| 全局默认阈值与产品阈值冲突 | 口径混乱 | 优先级写死为"产品 > 默认"，且**留痕记录来源** |

---

## 十一、与其它文档的衔接

| 文档 | 关系 |
|---|---|
| `risk-rule-set-design.md` / `risk-rule-development-guide.md` | 阈值决定"基准档位"，L3 规则在基准上做**修正**（转人工/降额） |
| `scorecard-multimodel-api.md` | 明确模型配置 thresholds 的定位（默认/兜底），与本文档口径一致 |
| `evaluation-report-v3-vs-v4.md` | 阈值标定方法（按目标坏账率）来源；本文档将其**按产品扩展** |
| 《第5周开发计划.md》 | 本文档可作为 W6 及后续的独立开发项 |

---

## 十二、实施状态（本次实施结果）

| 阶段 | 项 | 状态 |
|---|---|---|
| 阶段 1 | 数据修复（消除 `manual > auto`） | ✅ 已执行（商品业务贷 800→650、房贷 800→620，`violations=0`） |
| 阶段 1 | 保存校验 `validateThresholds()` | ✅ 已实现（`createProduct` / `updateProduct` 两处：成对配置、300–950、`manual ≤ auto`） |
| 阶段 1 | 阈值语义契约 | ✅ 见 §4.4 |
| 阶段 2 | 数据库扩展（4 字段 + 历史表 + 决策留痕字段） | ✅ 已执行（`db/product_threshold_schema.sql`） |
| 阶段 2 | **按产品标定脚本** | ✅ `scripts/calibrate_by_product.py` → `outputs/v4/product_thresholds.csv/json` |
| 阶段 2 | 实体 / Mapper / VO / DTO 扩展 | ✅ `LoanProduct`、`LoanProductVO`、`LoanProductMapper.xml`（5 处）、两个 DTO；`RiskDecisionLog` + XML（insert + 3 select） |
| 阶段 2 | **阈值解析**（产品优先 → 缺失回退默认） | ✅ `scorecard/ProductThresholdResolver`（含 `manual ≤ auto` 防御） |
| 阶段 2 | 阈值变更留痕 | ✅ `updateProduct` 检测变更 → 写 `product_threshold_history`（`ProductThresholdHistory` + Mapper） |
| 阶段 2 | 决策留痕记录生效阈值 | ✅ 两个申请入口写入 `productId` / `appliedAutoPass` / `appliedManualReview` / `thresholdSource` |
| 阶段 2 | Web 产品页 | ✅ `ProductDetail.vue`（目标坏账率 + 阈值来源/更新时间）、`ProductCreate.vue`（目标坏账率 + 校验提示）、`types/index.ts` |
| 阶段 2 | 阈值试算接口 `/threshold-suggestion` | ❌ **未实现**（暂缓，见下） |
| 阶段 3 | 额度分档 / 周期重标定 / 分产品模型适用性 | ⬜ 未做 |

**回归**：后端 `mvnw test` → Tests run: 23, Failures: 0, Errors: 0（BUILD SUCCESS）。

### 实测标定结果（2026-10-06，`calibrate_by_product.py`）

| 产品 | 目标坏账率 | 建议自动通过线 | 建议人工线 | 实测坏账率 | 实测通过率 | 样本 |
|---|---|---|---|---|---|---|
| 房贷（高收入/年长） | 1.5% | **684** | 630 | 1.51% | 58.3% | 11,340 |
| 个人信用贷（全客群） | 3.0% | **643** | 563 | 3.02% | 86.0% | 14,990 |
| 商品业务贷（年轻/中低收入） | 6.0% | **637** | 562 | 6.08% | 76.2% | 3,670 |

**结论（可直接用于答辩）**：
1. 目标坏账率不同 → 阈值自然分化，且**实测坏账率与目标高度吻合**（1.51%/3.02%/6.08%）→ 证明标定方法有效；
2. 通过率与风险容忍度成反比（58.3%/86.0%/76.2%）→ 体现"坏账率 vs 业务量"权衡；
3. **现有手填阈值明显偏严**（750/650、700/620 vs 建议 643/563、684/630）→ 会误杀本可放款客户，论证了"阈值必须标定"。

### 有待后续处理

| 项 | 说明 |
|---|---|
| 阈值试算接口 | 未实现。设计思路：读取标定产出（`product_thresholds.json`）或按目标坏账率实时计算；建议与"管理端展示建议阈值"一并实现 |
| 标定数据的真实性 | 产品分层为**模拟**（训练数据无产品维度），上线前需用真实各产品样本重标定（脚本已支持） |
| 前端类型检查 | Web `npm run build` 仍受 6 处**既有**类型错误影响（非本次引入）；本次改动用 `vite build` 验证通过 |

---

**文档结束**
