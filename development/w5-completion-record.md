# 第 5 周完成记录（W5 · 接口适配 + 两端展示 + 影子放量/回填）

> 记录日期：2026-10-06
> 对应计划：《第5周开发计划.md》
> 结论：**主线 A / B / C 全部完成并验证**（B6 为计划内可选项，未做，见 §五）。
> 回归：后端 `mvnw test` **23/23 全绿**；Web 打包（`vite build`）通过；用户端 `gradlew test assembleDebug` **BUILD SUCCESSFUL**。

---

## 一、交付清单与证据

### 主线 A · 评分接口适配与影子放量（后端）

| 任务 | 状态 | 证据 |
|---|---|---|
| A0 口径收口 | ✅ | `development/scorecard-multimodel-api.md` §8：模型口径表（v4=21 特征默认 / v4-fusion 影子 / v3 降级）+ **更正 W4 文档的 v4 标注** + **social 不接入**声明 |
| A1 多源特征装配 | ✅ | `FeatureTransformationService.buildMultiSourceFeatures(userId)`：信用档案 6 特征 + 多源宽表快照合并（现有 6 特征方法**零改动**） |
| A2 三层校验强化 | ✅ | `DataValidationService.validateMultiSourceFeatures(features)`：类型 / 区间（18 字段上下限）/ 业务规则（在网 ≤ 实名、缴存基数与收入匹配） |
| A3 兼容性用例 | ✅ | 新增 `MultiSourceValidationTest` 5 例（合法/类型错误/区间越界/业务规则冲突/空特征）；测试总数 18 → **23**；既有 `v3ScoringShouldMatchLegacyEngine` 保留 |
| A4 影子回填 | ✅（方案调整） | **Java 侧**：`ShadowBackfillService(+Impl)` + `POST /api/v1/scorecard/v2/shadow/backfill`；目标模型自动避开主模型（v4↔v3）。**原计划的 Python 离线回填不可行**：Python 3.14 装不上 xgboost 且 v4-fusion 依赖 `xgb_multi_score` 特征（详见 §四.1） |
| A5 影子放量开关 | ✅ | `shadow.rate` 配置（0=默认关闭 / 1=全量 / (0,1) 采样）；实测 `--shadow.rate=1` 生效 |
| A6 监控指标 | ✅ | `ScoreMetrics` + `GET /api/v1/scorecard/v2/metrics`：评分次数 / 降级次数 / 平均耗时 / 版本分布 + 影子进度 |

### 主线 B · 多源画像与两端展示

| 任务 | 状态 | 证据 |
|---|---|---|
| B1 多源画像 | ✅ | `MultiSourceServiceImpl.buildDataBlocks()`：按前缀分组（credit / operator / social / device / behavior）+ 脱敏（`device_fingerprint_hash` → 前 8 位 + `********`） |
| B2 展示接口升级 | ✅ | `GET /multi-source/profile` 返回**真实脱敏摘要**；新增 `GET /api/v1/scorecard/v2/approval-summary?applicationId=`（决策留痕 + 特征贡献 + 多源摘要） |
| B3 Web 审批详情多源区块 | ✅ | 新增组件 `src/components/MultiSourceSummaryCard.vue`；`views/ApplicationDetail.vue` 仅 2 行接入（import + 组件引用） |
| B4 Web 评分卡监控增强 | ✅ | 新增组件 `src/components/ModelRegistryCard.vue`（三模型 AUC/KS/定位 + 引擎指标 + 影子进度）；`views/reports/ScoreCardAnalysis.vue` 仅 2 行接入；`api/index.ts` 追加 5 个接口函数 |
| B5 App 信用档案多源展示 | ✅ | `activity_credit_profile.xml` 新增"多源数据（脱敏）"卡片；`CreditProfileActivity` 追加 `loadMultiSourceSummary()` + `renderMultiSource()`（失败静默） |
| B6 App 采集补齐（可选） | ⬜ 未做 | 计划内可选项（`field_count` 真实统计 / 字段顺序埋点 / 更多页面停留） |

### 主线 C · 联调与收口

| 任务 | 状态 | 证据 |
|---|---|---|
| C1 端到端联调 | ✅ | §二 实测记录（9 步全链路跑通） |
| C2 影子回填与放量演练 | ✅ | §三（回填 done=1/failed=0；影子对照 v4=706 vs v3=665；`shadow.rate` 全量采样下记录生成） |
| C3 三端回归 | ✅ | 后端 23/23；Web `vite build` ✓（`npm run build` 因既有类型错误失败，见 §四.2）；用户端 BUILD SUCCESSFUL |
| C4 文档与 W5 记录 | ✅ | 本文档 + `scorecard-multimodel-api.md` §8 口径收口 |

---

## 二、端到端联调记录（C1）

**环境**：后端 `http://127.0.0.1:8080`（`--shadow.rate=1`）、MySQL `loan_risk_control`、测试账号 `13900001234`
**链路**：登录 → 授权 → 同步 → 特征宽表 → v4 评分 → 决策留痕/审批聚合 → 多源画像 → 影子回填 → 指标

| 步骤 | 实测结果 |
|---|---|
| 1 登录 | `userId=14d7933c2e474b8eb7649746fc4541e1` |
| 2 授权三源 | credit / operator / device → `AUTHORIZED` |
| 3 批量同步 | `users=3 success=3 failed=0 elapsedMs=37` |
| 4 特征宽表 | `featureCount=18` |
| 5 **v4 评分** | `modelKey=v4 score=706 decision=AUTO_APPROVE degraded=false`；`missing` 11 项（6 个基础特征 + social 3 + gps/behavior 2，见 §四.3） |
| 6 **审批详情聚合** | `found=true modelVersion=v4 score=706`；**特征贡献 21 项**、**多源分组 5 个** |
| 7 多源画像（脱敏） | `device_fingerprint_hash=75fb588d********` |
| 8 影子回填 | `done=1 failed=0 pendingLeft=0` |
| 9 影子对照 + 引擎指标 | `APP-W5-E2E-001: primary(v4)=706 vs shadow(v3)=665 (DONE)`；`engine{totalScores:1, degradedCount:0, avgElapsedMs:9, versionDistribution:{v4:1}}`、`shadow{DONE:7,total:7}` |

> 两端展示接口均在同一链路上取数：Web 审批详情用 `approval-summary`、App 信用档案用 `multi-source/profile`（步骤 6/7 即其数据来源）。

---

## 三、影子回填与放量演练（C2）

| 项 | 结果 |
|---|---|
| 采样开关 | `shadow.rate=1`（启动参数）→ 每次评分均产生 `PENDING` 影子记录 |
| 回填 | `POST /shadow/backfill?limit=20` → `done=1 failed=0 pendingLeft=0`（本轮新增 1 条） |
| 对照样例 | `primary v4=706` vs `shadow v3=665`；历史样例 `733 vs 693` |
| 累计进度 | `shadow{DONE:7, total:7}`（全部回填完成） |
| 目标模型选择 | 自动避开主模型：primary=v4 → 影子用 v3（反之用 v4），保证对照有意义 |

---

## 四、已知问题与偏差（如实记录）

1. **A4 方案调整（已采纳）**：原计划"离线 Python 回填 v4-fusion/XGBoost 分数"在本机**不可行**——Python 3.14 无 xgboost 预编译轮子，且 `v4-fusion` 依赖 `xgb_multi_score` 特征（需 XGBoost 推理）。改为 **Java 侧回填 LR 模型（v3/v4）**，零新增依赖且闭环完整。若后续在可用环境安装 xgboost，可另写 Python 回填脚本（当前 Java 端点已够用）。
2. **Web `npm run build` 因既有类型错误失败**（非本次引入，已取证）：
   - `ApplicationDetail.vue:104-105` — `formatDateTime(...)` 传 `string|null`（原有代码）
   - `ApplicationDetail.vue:622-623` — `safeParseJson<T>(..., null)` 泛型实参与默认值不匹配（原有代码）
   - `OverdueStats.vue:220,251` — **未 import `ElMessage`**（该文件本次未改动）
   - 本次 Web 改动已用 `vite build` 验证**打包通过**；建议将上述 6 处作为独立技术债修复后再恢复 `npm run build` 全绿。
3. **`/multi-source/features` 未合并基础特征**（联调发现）：该端点只返回多源特征（18 项），因此直接用其作为评分入参时，6 个基础特征（revolving_util/age/…）会出现在 `missing`。**A1 的 `buildMultiSourceFeatures()` 正是为此设计**（合并信用档案基础特征）——建议后续把该端点或评分前置流程接到 A1 装配，或由调用方合并基础特征。
4. **`social` 源本期不接入**（既定口径，已写入契约 §8.2）：`social` 组在画像中为空，v4 评分 `missing` 含 `social_*` 3 项。
5. **`shadow.rate` 默认 0**：生产默认不产生影子记录；放量演练需显式开启（`--shadow.rate=1` 或改配置）。
6. **B6 未做**（计划内可选项）：App 的 `field_count` 真实统计、字段填写顺序埋点、更多页面停留时长未实施。当前 App 采集字段与 W2 一致。

---

## 五、与 W6（M2 阶段验收）的衔接

| W6 任务 | 本周已具备的基础 |
|---|---|
| 全链路联调（多源 → v4 评分 → 决策 → 两端展示） | §二 已跑通一次；接口与页面均就绪 |
| 高并发压测、决策 P99 延迟达标 | `/metrics` 提供评分次数/耗时；`shadow.rate` 可关闭以避免压测干扰 |
| 多源特征与评分全链路一致性校验 | A1 装配口径 + A2 校验规则 + 决策留痕的特征快照（可回溯比对） |
| M2 验收报告（含影子对照与回填结果） | §三 影子回填与对照数据 |
| 文档与部署收口 | 本文档 + 契约 §8；待办：Web 既有类型错误、`/features` 基础特征合并 |

---

**文档结束**
