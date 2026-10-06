# 第 4 周完成记录（W4 · 多模型引擎 + 设备指纹源 + M1 中期评审材料）

> 记录日期：2026-10-06
> 对应计划：《第4周开发计划.md》
> 结论：**主线 A、B 全部完成并通过测试；主线 C 的评审材料与端到端演示已完成并留档**（本文档即 C2/C4 记录）。
> 回归：后端 `mvnw test` → **Tests run: 18, Failures: 0, Errors: 0（BUILD SUCCESS, exit=0）**

---

## 一、交付清单与证据

### 主线 A · 评分卡引擎多模型化

| 任务 | 状态 | 证据 |
|---|---|---|
| A1 多模型配置目录 | ✅ | `src/main/resources/models/`：`model_config_v3.json`、`model_config_v4.json`、`model_config_v4_fusion.json`、`xgb_multi_source.json`（578 KB） |
| A2 模型注册表 | ✅ | `com.loan.scorecard.ModelRegistry`、`ModelScorer`、`model/ScorecardModel`；单个模型加载失败不影响其它（`log.error("模型加载失败（不影响其它模型）")`） |
| A3 按版本路由 | ✅ | `POST /api/v1/scorecard/v2/calculate`（`MultiModelScoreRequest`：`features/modelVersion/applicationId/userId`）、`GET /models`、`GET /models/{modelKey}` |
| A4 加载失败自动降级 | ✅ | `ModelRegistry.FALLBACK_KEY = "v3"`；请求未注册/加载失败版本时自动降级并在响应返回 `degraded=true` + 告警日志（演示见 §二.4） |
| A5 决策留痕扩展 | ✅ | `risk_decision_log` 新增 `model_version`(varchar64)、`feature_snapshot`(text)，Mapper XML 同步；演示留痕见 §二.6 |
| A6 XGBoost 影子模式 | ✅ | `ShadowScoreService` + `shadow_score_log` 表 + `GET /api/v1/scorecard/v2/shadow`；**离线回填**策略（Java 侧不引入 xgboost4j），记录落库为 `PENDING` |
| A7 引擎验证 | ✅ | `ScorecardEngineTest` 5 用例：模型加载 / 未知版本降级 / v3 与既有引擎一致 / v4 多源评分 / 缺失特征上报；实跑记录见 §二 |

### 主线 B · 设备指纹源接入 + 统一管理与调度

| 任务 | 状态 | 证据 |
|---|---|---|
| B1 `MockDeviceProvider` 填数 | ✅ | 产出 10 个设备字段（指纹 hash、模拟器标记、黑名单即时命中、近 30 天关联申请数、首现时长、IP 风险等） |
| B2 `DataSourceRegistry` 接表 | ✅ | 读取 `data_source_registry` 的 `enabled / priority / circuit_status`；表内无记录时写入默认配置（enabled=1、priority=100、CLOSED） |
| B3 熔断与健康检查 | ✅ | `circuit_status`（CLOSED/OPEN/HALF_OPEN）维护与恢复探测；`GET /multi-source/registry` 返回 health |
| B4 批量 + 增量定时同步 | ✅ | `MultiSourceSyncService`；`POST /multi-source/sync/batch`、`POST /multi-source/sync/incremental`（参数：`sourceType` / `staleHours` / `limit`） |
| B5 可观测性 | ✅ | `GET /multi-source/sync/stats`（最近一次同步统计）、`GET /multi-source/registry` |

### 主线 C · M1 中期评审

| 任务 | 状态 | 证据 |
|---|---|---|
| C1 评审材料 | ✅ | `scorecard_project/outputs/v4/`：`ablation.csv/json`、`contrib_*.json`、`eval_*.png`（5 张）、`metrics_*.json`；报告 `scorecard_project/development/evaluation-report-v3-vs-v4.md`（含 AUC/消融/PSI/OOT/SHAP） |
| C2 三类数据源端到端演示 | ✅ | 本文档 §二（2026-10-06 实跑） |
| C3 回归验证 | ✅ | 后端 `mvnw test` 18/18 全绿；用户端 W4 未改动（此前 `gradlew test assembleDebug` 通过） |
| C4 文档更新 | ✅ | `loan/development/scorecard-multimodel-api.md`（契约，含降级/影子说明）+ 本文档 |

---

## 二、端到端演示记录（C2 / A7）

**环境**：后端 `http://127.0.0.1:8080`（Spring Boot 启动 3.859s）、MySQL `loan_risk_control`、测试账号 `13900001234`
**链路**：授权 → 同步（credit/operator/device）→ 特征宽表 → 多模型评分 → 降级 → 影子留痕 → 决策留痕

### 1. 数据源授权（3 个源）

| sourceType | 结果 |
|---|---|
| credit | AUTHORIZED / 已授权 |
| operator | AUTHORIZED / 已授权 |
| device | AUTHORIZED / 已授权 |

### 2. 批量同步 `POST /api/v1/multi-source/sync/batch?limit=50`

```json
{ "code": 200, "data": { "mode": "BATCH", "users": 3, "success": 3, "failed": 0, "elapsedMs": 23,
  "perSource": [
    { "sourceType": "credit",   "users": 1, "success": 1, "failed": 0 },
    { "sourceType": "device",   "users": 1, "success": 1, "failed": 0 },
    { "sourceType": "operator", "users": 1, "success": 1, "failed": 0 },
    { "sourceType": "social",   "users": 0, "success": 0, "failed": 0 } ],
  "circuit": [ { "sourceType": "credit", "provider": "MockCreditProvider", "enabled": true, "priority": 100, "circuitStatus": "CLOSED", "available": true }, "…device/operator/social 同构" ] } }
```

> `social` 为 0 是预期：`MockSocialProvider` 仍为占位（W4 计划未要求接入社保源）。

### 3. 多源特征宽表 `GET /api/v1/multi-source/features`

```
featureCount = 18
credit_query_cnt_3m=0, credit_query_cnt_6m=1, debt_to_income=0.42, hist_overdue_cnt_m1=0, hist_overdue_cnt_m3=1,
device_fingerprint_hash=75fb588d345842bb, device_blacklist_hit_cnt=1, device_related_app_cnt_30d=2,
device_first_seen_months=33, ip_proxy_risk=0.23, operator_realname_months=85, operator_tenure_months=74,
operator_pay_stability=36, …
sources: credit/device/operator → fetchStatus=SUCCESS, dataVersion=v1
```

### 4. 模型注册表 `GET /api/v1/scorecard/v2/models`

| modelKey | 特征数 | AUC | KS | 阈值（manual/auto） | 默认 |
|---|---|---|---|---|---|
| v3 | 6 | 0.7630 | 0.4124 | 600 / 650 | false |
| **v4** | **21** | **0.8556** | **0.5578** | 504 / 641 | **true（线上主模型）** |
| v4-fusion | 22 | 0.8639 | 0.5742 | 536 / 639 | false |

### 5. 版本路由与自动降级 `POST /api/v1/scorecard/v2/calculate`

| 请求 | 结果 |
|---|---|
| `modelVersion=v4` + 多源特征 | `totalScore=733`、`AUTO_APPROVE`、`degraded=false`、`requiredFeatureCount=21`、`missingFeatures=[hist_overdue_cnt_m2, social_contrib_months, social_base, social_employer_cnt]`（social 未接入所致） |
| `modelVersion=v3`（同特征） | `totalScore=693`、`AUTO_APPROVE`、`requiredFeatureCount=6`、`missingFeatures=[]` —— 与 v4 分数差异体现多源特征贡献 |
| **`modelVersion=v99`（未注册）** | `degraded=true`、实际使用 `modelKey=v4`、`totalScore=733` ✅ **自动降级生效** |

### 6. 影子记录 `GET /api/v1/scorecard/v2/shadow?limit=3`

```json
{ "stats": { "PENDING": 3, "total": 3 },
  "recent": [ { "applicationId": "APP-W4-DEMO-003", "primaryModelKey": "v4", "primaryScore": 733,
                "primaryDecision": "AUTO_APPROVE", "shadowModelKey": "v4-fusion",
                "shadowStatus": "PENDING", "shadowScore": null, "featuresJson": "{…19 个特征…}" }, "…共 3 条" ] }
```

> `PENDING` 为设计预期：影子分数由**离线批量回填**（Java 侧不跑 XGBoost）。

### 7. 决策留痕落库校验

```
risk_decision_log:
application_id     | scorecard_score | scorecard_decision | model_version | feature_snapshot
APP-W4-DEMO-003    | 733             | AUTO_APPROVE       | v4            | {"behavior_anomaly_score":0.2,…}
APP-W4-DEMO-002    | 693             | AUTO_APPROVE       | v3            | {"behavior_anomaly_score":0.2,…}
APP-W4-DEMO-001    | 733             | AUTO_APPROVE       | v4            | {"behavior_anomaly_score":0.2,…}

计数：shadow_score_log=3、user_multi_source_data=3、data_source_registry=4
```

---

## 三、与 M1 验收标准对照

| 验收项 | 标准 | 实测 | 结论 |
|---|---|---|---|
| 模型指标 | v4 离线 AUC ≥ 0.80，KS 较 v3 显著提升；消融证据齐备 | v4 AUC **0.8556** / KS **0.5578**（v3 为 0.7630 / 0.4124）；v4-fusion 0.8639 / 0.5742；消融表与图表齐备 | ✅ |
| 引擎能力 | ≥2 模型版本并存、可切换、加载失败自动降级、影子可查 | 3 个版本并存；版本路由实测；`v99→degraded=true` 降级实测；影子记录可查（PENDING 3 条） | ✅ |
| 数据源 | 三类数据源可开关运行，端到端演示通过；同步可控可观测 | credit/operator/device 同步各 1 用户成功（23ms）；特征宽表 18 字段；`/registry` 显示 4 源 enabled=true | ✅ |
| 无回归 | 后端测试全绿、用户端构建通过 | `mvnw test` 18/18；用户端未改动 | ✅ |

---

## 四、已知问题与偏差（如实记录）

1. **计划文档中的版本标注需更正**：W4 计划"起点"写 `model_config_v4.json`（fusion，AUC 0.8639）——实际 `model_config_v4.json` 是 **21 特征 multi 版（AUC 0.8556）**，22 特征的 fusion 版是 `model_config_v4_fusion.json`（AUC 0.8639）。两者均 ≥ 0.80，不影响验收，但文档口径需统一（线上默认模型为 `v4`）。
2. **`MockSocialProvider` 仍为占位**：社保/公积金源未接入 → 该源同步 `users=0`，v4 评分缺 `social_*` 3 个特征（另有 `hist_overdue_cnt_m2` 未在上游生成）。属 W4 计划范围外，需在后续周次补齐或明确"本期不接入"。
3. **影子分数为 `PENDING`**：`shadow_score_log` 落库后待**离线批量回填**（v4-fusion / XGBoost 分数）；回填脚本属 W5 影子放量阶段工作。
4. **代码尚未提交 git**：W2–W4 共 51 个文件改动仍在工作区（按你的选择暂未提交）。
5. **社保源与 `v4-fusion` 的线上启用次序**：`v4-fusion` 依赖 `xgb_multi_score` 特征，需 Java 侧 XGBoost 推理（native 依赖），本期以影子/离线方式存在，**不参与线上决策**（设计如此）。

---

## 五、与 W5 的衔接

| W5 任务 | 本周已具备的基础 |
|---|---|
| 评分接口全面适配新模型与新特征（兼容旧版调用） | 多模型路由 + 特征宽表 + 缺失特征上报机制 |
| 影子模式按比例放量、监控指标上报 | 影子记录与 `PENDING` 队列（待离线回填）+ 决策留痕 `model_version`/`feature_snapshot` |
| 信用画像升级与两端脱敏展示 | 三类数据源同步 + 特征宽表快照接口 |
| M2 阶段验收（W6 末） | 本周 M1 结论与本文档"已知问题"清单 |

---

**文档结束**
