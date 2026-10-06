# 评分卡多模型模块 — 接口文档（W4 · 主线 A）

> 版本：V1.0（W4 新增）
> 关联：`multi-source-api.md`（多源模块）、`multi-source-reservation-guide.md`（字段预留）
> 实现代码：`com.loan.scorecard`（`ModelRegistry` / `ModelScorer`）、
> `MultiModelScorecardService`、`MultiModelScorecardController`、`ShadowScoreService`

---

## 概述

- **定位**：在**不改动**现网 `/api/v1/scorecard` 4 个端点与其行为的前提下，新增"多模型"能力——
  按模型版本评分、**自动降级**、决策留痕（含模型版本）、**影子对照**。
- **鉴权**：JWT（`LoginInterceptor` 覆盖 `/api/v1/**`）。
- **返回包装**：统一 `Result<T>`。

### 已注册模型（启动时扫描 `resources/models/model_config_*.json`）

| 注册键 | 版本 | 类型 | 特征数 | AUC（测试集） | 来源 |
|---|---|---|---|---|---|
| `v3` | 3.0 | lr | 6 | 0.763 | 现网 `model_config.json` 副本 |
| `v4` | v4-candidate | lr | 21 | **0.8556** | W3 `model_config_multi.json`（多源融合，**默认模型**） |
| `v4-fusion` | v4-candidate | lr+xgb | 22 | **0.8639** | W3 `model_config_fusion.json`（含 XGBoost 风险分特征） |
| — | — | — | — | — | `xgb_multi_source.json`（XGBoost 离线资产，影子对照用，不参与 Java 侧推理） |

> 默认模型由配置项 `scorecard.default-model-version` 控制（默认 `v4`）。

### 设计取舍（重要）

| 取舍 | 原因 |
|---|---|
| **线上主模型用 `v4`（21 特征）而非 `v4-fusion`（22 特征）** | `v4-fusion` 依赖 `xgb_multi_score` 特征值，需在 Java 侧运行 XGBoost 推理（引入 native 依赖）；`v4` 完全可由"传统特征 + 多源特征"计算，**零额外依赖**且已满足 AUC ≥ 0.80 |
| **XGBoost 采用"离线回填"影子模式** | Java 侧不引入 `xgboost4j`（避免 native 库/部署负担）；影子记录落库（PENDING）后由离线批量打分回填，天然满足"异步不阻塞" |
| 既有端点保持原样 | 现网 `/scorecard/calculate` 等仍走 v3 单模型逻辑，**零影响** |

---

## 1. 多模型评分

```
POST /api/v1/scorecard/v2/calculate
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `features` | object | 是 | 特征键值（键名与模型配置一致；可含多源特征） |
| `modelVersion` | string | 否 | `v3` / `v4` / `v4-fusion`；为空用默认模型 |
| `applicationId` | string | 否 | 申请 ID（提供时写入决策留痕） |
| `userId` | string | 否 | 用户 ID（与 applicationId 任一存在时记录影子任务） |

```json
{
  "modelVersion": "v4",
  "applicationId": "APP20260920001",
  "userId": "14d7933c2e474b8eb7649746fc4541e1",
  "features": {
    "revolving_util": 0.20, "age": 45, "debt_ratio": 0.25,
    "monthly_income": 12000, "credit_lines": 10, "dependents": 1,
    "credit_query_cnt_3m": 1, "credit_query_cnt_6m": 3,
    "hist_overdue_cnt_m1": 0, "operator_realname_months": 96,
    "operator_tenure_months": 80, "operator_pay_stability": 30,
    "social_contrib_months": 120, "social_base": 9000, "social_employer_cnt": 1,
    "device_related_app_cnt_30d": 1, "device_first_seen_months": 24,
    "gps_deviation_km_longterm": 12.0, "behavior_anomaly_score": 0.2
  }
}
```

**响应**（示例结构）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "modelKey": "v4",
    "modelVersion": "v4-candidate",
    "modelType": "lr",
    "requestedVersion": "v4",
    "degraded": false,
    "requiredFeatureCount": 21,
    "missingFeatures": ["dependents"],
    "totalScore": 712,
    "scoreLevel": "良好",
    "decision": "AUTO_APPROVE",
    "decisionDesc": "自动通过",
    "featureScores": { "revolving_util": 17.45, "age": -6.08, "…": "…" },
    "scoredAt": "2026-09-22T12:00:00.000Z"
  }
}
```

| 字段 | 说明 |
|---|---|
| `modelKey` / `modelVersion` | **实际使用**的模型键与版本 |
| `requestedVersion` / `degraded` | 请求的版本；`degraded=true` 表示发生了自动降级 |
| `requiredFeatureCount` / `missingFeatures` | 模型要求的特征数；本次**未提供**的特征（如未同步多源数据时） |
| `featureScores` | 各特征分箱得分明细（可解释性） |

---

## 2. 模型注册表

```
GET /api/v1/scorecard/v2/models
```

**响应**（示例）

```json
{
  "code": 200, "msg": "操作成功",
  "data": [
    { "modelKey": "v3", "version": "3.0", "type": "lr", "status": "LOADED",
      "featureCount": 6, "thresholds": { "autoApprove": 650, "manualReview": 600 },
      "metrics": { "auc": 0.763001, "ks": 0.412406 }, "defaultModel": false },
    { "modelKey": "v4", "version": "v4-candidate", "type": "lr", "status": "LOADED",
      "featureCount": 21, "metrics": { "auc": 0.855616, "ks": 0.557800 }, "defaultModel": true },
    { "modelKey": "v4-fusion", "version": "v4-candidate", "type": "lr", "status": "LOADED",
      "featureCount": 22, "metrics": { "auc": 0.863930, "ks": 0.574231 }, "defaultModel": false }
  ]
}
```

```
GET /api/v1/scorecard/v2/models/{modelKey}     # 单个模型详情；不存在返回 404
```

---

## 3. 影子记录查询

```
GET /api/v1/scorecard/v2/shadow?limit=20
```

**响应结构**

```json
{
  "code": 200, "msg": "操作成功",
  "data": {
    "stats": { "PENDING": 12, "DONE": 88, "total": 100 },
    "recent": [ { "id": 100, "applicationId": "APP…", "primaryModelKey": "v4",
                  "primaryScore": 712, "shadowStatus": "DONE",
                  "shadowModelKey": "v4-fusion", "shadowScore": 705,
                  "createdAt": "…", "scoredAt": "…" } ],
    "pending": [ { "id": 101, "primaryModelKey": "v4", "primaryScore": 690,
                   "featuresJson": "{\"revolving_util\":0.2,…}", "shadowStatus": "PENDING" } ]
  }
}
```

> **影子模式说明**：主模型出分后，系统异步落一条 `shadow_score_log`（含特征快照）；
> 离线侧（Python）批量拉取 `PENDING` 记录、用 `v4-fusion`/XGBoost 打分后回填
> （`shadow_status: PENDING → DONE`），从而在**不影响线上结果、不增加部署依赖**的前提下完成新旧模型对照。

---

## 4. 自动降级与容错（A4）

| 场景 | 行为 |
|---|---|
| 请求的版本未注册（如 `v99`） | 自动降级到默认模型，响应 `degraded=true`，并输出告警日志 |
| 请求的版本存在但**加载失败**（配置损坏） | 同上降级；该模型在注册表中状态为 `FAILED` 并带 `errorMessage`（可通过 `/models` 查询） |
| 默认模型不可用 | 回退 `v3`；仍不可用则取任一可用模型 |
| 全部模型不可用 | 返回业务错误（500），不抛栈给前端 |
| 特征缺失 | **不报错**，按 0 分计入并在 `missingFeatures` 中列出（提示调用方需先同步多源数据） |

---

## 5. 决策留痕扩展（A5）

`risk_decision_log` 新增两列（可空，向后兼容）：

| 列 | 说明 |
|---|---|
| `model_version` | 本次决策使用的模型注册键（如 `v4`） |
| `feature_snapshot` | 评分所用特征快照（JSON），支持"这笔决策用了什么特征"回溯 |

> 仅当请求携带 `applicationId` 时写入留痕，避免污染无关数据。

---

## 6. 单测覆盖（A7）

| 测试 | 内容 |
|---|---|
| `registryShouldLoadModels` | 注册表加载 v3/v4，特征数正确（v3=6、v4>6），默认模型为 v4 |
| `unknownVersionShouldFallback` | 请求不存在的版本 → 自动降级到默认模型 |
| `v3ScoringShouldMatchLegacyEngine` | **v3 打分与现网引擎完全一致**（算法口径不变的回归保障） |
| `v4ShouldScoreWithMultiSourceFeatures` | v4 能消费多源特征并给出决策，分数在 300–950 |
| `missingMultiSourceFeaturesShouldBeReported` | 未传多源特征时报告缺失而非报错，且不误判降级 |

---

## 7. 与后续版本的衔接

| 阶段 | 变化 |
|---|---|
| W5 | 影子模式按比例放量（灰度）；监控指标上报（ScoreCardAnalysis 页扩展）；线上决策逐步切到 v4 |
| W6 | 全链路联调（多源 → v4 评分 → 决策留痕 → 两端展示），接口保持向后兼容 |
| 远期 | 若影子数据证明 XGBoost 独立模型更优，可将其升级为主模型（V2.0 路线） |

---

**文档结束**
