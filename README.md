# 药品批次谱系数据质量与召回影响服务

面向药品质量团队的**批次谱系数据质量与召回影响核验系统**。检验结果更正后，系统回答监管方的核心问题：

- 截至某个**发布时点（截止时间）**，哪些成品批次可能使用了受影响原料；
- 数量能够确认到什么程度（**确认量下界 / 待核量 / 可能受影响量上界 / 未能归因差额**）；
- 哪些机构或运输节点出现在**可验证的流转证据**中；
- 每一项结论**缺少什么证据、阻断了哪个结论**。

技术栈限定：**Vue 3 + Spring Boot 3.5 + PostgreSQL 18**。

> 服务边界：本系统只做数据质量核验与范围发布，**不隔离、销毁、退运或调拨药品，不向机构下达通知，不生成付款、补偿或审批决定**。

---

## 1. 核心设计：来源断言与发布范围分别版本化

系统不覆盖旧事实，而是把“来源断言”和“发布范围”分别版本化，使三类变化可被区分和复现：

| 变化类型 | 识别方式 | 系统行为 |
| --- | --- | --- |
| 检验结论变了 `INSPECTION_CHANGED` | 核验选择的检验更正版本不同 | 生成新发布版本，保留旧版本输入依据与受影响结论 |
| 新物流/机构凭证补到 `NEW_LOGISTICS_EVIDENCE` | 新快照中出现旧快照没有的物流/机构包 | 迟到回报只进入新版本，不改变历史答复 |
| 解析规则修订 `PARSE_RULE_REVISED` | 包哈希有效但解析版本落后且字段语义受影响 | 标记 `PARSE_DIFF`，要求重新审阅关系边，不回填旧范围 |

每个来源包均保留**原始载荷、接收时间、事件时间、来源修订号、文件哈希（SHA-256）、解析规则版本、解析状态**。
每次核验在 `verification_run.input_snapshot` 中锁定截止时间前的来源包清单，后续补录无法混入。

### 六条强制规则的实现位置

| 业务规则 | 实现 |
| --- | --- |
| 同一内部批号对应两个供应商批号且无可验证凭证 → 保留双候选、不合并、扩大上界 | `IdentityResolutionService` + `QuantityQualityEngine`（缺口 `IDENTITY_AMBIGUITY`） |
| 拆分投料合计超过原料可用量 → 冻结整条分配链确认量，展示下界与超额差额 | `QuantityQualityEngine`（缺口 `OVER_FEEDING`，边状态 `FROZEN`，冻结沿 BFS 传播到全部下游代际） |
| 机构回报与物流签收不一致且双凭证在截止前有效 → 数量冲突，任一来源不得覆盖另一来源 | `QuantityQualityEngine.attachNodeQuantities`（缺口 `QUANTITY_CONFLICT`） |
| 检验更正发生在发布之后 → 新候选版本，旧版本只读保留 | `ReleaseService`（旧版本置 `SUPERSEDED`，记录 `diff_type` 与 `affected_conclusions`） |
| 箱码在两条互不相连运输记录中重复 → 重复载体证据，两条边都不得证明链路完整 | `LineageCompiler`（缺口 `DUPLICATE_BOX`，两条运输边降级 `SUSPECTED`） |
| 哈希通过但解析规则升级导致字段含义变化 → 解析差异，重新审阅关系边，不回填旧范围 | `IngestionService.resolveParseStatus` + `LineageCompiler`（仅降级受影响事实类型的边） |

另有 `MISSING_FEEDING_EVIDENCE`（缺失投料凭证）、`UNMATCHED_BATCH`（无法匹配批号）两类缺口。

---

## 2. 目录结构

```
.
├── backend/                 # Spring Boot 服务（JDK 25 编译目标 21）
│   ├── src/main/java/com/trace/recall/
│   │   ├── ingestion/       # 接入登记台 + 演示样例数据 Seeder
│   │   ├── identity/        # 批次身份解析（歧义保留）
│   │   ├── lineage/         # 谱系编译器（有向关系边）
│   │   ├── quality/         # 数量质量引擎 + 证据缺口工作台
│   │   ├── verification/    # 核验编排（快照锁定）
│   │   ├── release/         # 范围发布器（版本化、脱敏）
│   │   ├── config/          # CORS、外部 API Key 拦截器、JSONB 归一化
│   │   └── web/             # 内部 /api 与外部 /external 控制器
│   ├── src/main/resources/  # application.yml、schema.sql
│   └── src/test/            # 端到端规则测试（独立 recall_test schema）
├── frontend/                # Vue 3 + Vite + vue-router
│   └── src/views/           # 核验台/接入台/核验详情/批次钻取/发布版本/外部接口
├── docker-compose.yml       # 一键启动 postgres + backend + frontend
└── README.md
```

---

## 3. 本地启动

### 方式 A：Docker Compose 一键启动（推荐交付方式）

```bash
docker compose up --build
```

- 前端（Nginx）：http://localhost:8081
- 后端 API：http://localhost:8080
- PostgreSQL：localhost:5432（库/用户/密码 `ccdev`）
- 后端首次启动自动建表并播种演示数据。

### 方式 B：本地开发模式（使用容器内预装运行时）

前置：PostgreSQL 已在 127.0.0.1:5432 运行，库/用户/密码为 `ccdev`。

```bash
# 1) 后端（终端 1）
cd backend
mvn spring-boot:run
# 启动后自动执行 schema.sql 并播种 8 个来源包、13 个批次主数据

# 2) 前端（终端 2）
cd frontend
npm install
npm run dev
# 打开 http://localhost:8081 （Vite 已配置 /api 与 /external 代理到 8080）
```

### 测试账号 / 凭证

| 用途 | 凭证 |
| --- | --- |
| 内部质量工作台 | 无需登录（内网工作台），默认发起人 `质量分析员-张某` |
| 外部处置系统 API Key（请求头 `X-API-Key`） | `ext-trace-key-2026`（可用环境变量 `EXTERNAL_API_KEY` 覆盖） |
| 数据库 | 库/用户/密码 `ccdev` |

---

## 4. 演示数据与两次核验场景

时间线（2026-11，UTC+8）：

| 日期 | 事件 |
| --- | --- |
| 11-01 | 主数据；RM-A02 同时被供应商批号 SUP-LOT-22 / SUP-LOT-23 观测，无装箱凭证（**身份歧义**） |
| 11-02 | 检验 V1 `QC-REV-1`：RM-A03、RM-A04 不合格 |
| 11-03 | 投料/拆分/混批/放行：RM-A04 拆分合计 75kg > 可用 60kg（**超额冻结**）；RM-A02→IM-B30 缺投料凭证 |
| 11-05 | 解析规则升级 PARSE-2.0（影响 LOGISTICS、BLEND 字段语义） |
| 11-06 | 物流：箱码 BOX-9 在两条互不相连运输记录重复（**重复载体**）；生产批号 PL-UNKNOWN-9 无法匹配；LOG-PKG-2 仍按 PARSE-1.9 解析（**解析差异**） |
| 11-07 | 机构回报 R1/R2：HOSP-乙 回报 46kg vs 物流签收 50kg（**数量冲突**） |
| **11-10 18:00** | **截止时间 #1**，检验版本 QC-REV-1（快照 6 个包） |
| 11-12 | 检验更正 V2 `QC-REV-2`：RM-A01 改判不合格、RM-A03 复检合格 |
| 11-13 | 迟到机构回报 R3（HOSP-丙，FG-C03） |
| **11-14 18:00** | **截止时间 #2**，检验版本 QC-REV-2（快照 8 个包） |

### 在页面上操作

1. 打开「范围核验台」，选择 `QC-REV-1` + 截止 #1 → **锁定快照并核验**；
2. 在核验详情查看数量区间、谱系图（红虚线=冻结、黄虚线=待核）、身份候选、证据缺口、输入快照；
3. 点击任意批次「展开证据」→ 可下钻到**原始载荷、身份候选、谱系边、数量区间、冲突/缺口、规则版本**；
4. 回到核验台再发起 `QC-REV-2` + 截止 #2；
5. 分别对两次核验点击「发布范围新版本」，在「发布版本管理」查看旧版本 `SUPERSEDED` 保留与三类差异；
6. 「外部接口（脱敏）」页用 API Key 查看外部处置系统能拿到的摘要，并可验证无 Key 返回 401。

---

## 5. HTTP 接口摘要

### 内部工作台（`/api`，无 Key，可查看原始载荷）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/source-packages` / `/api/source-packages/{code}` | 来源包清单与原始载荷（含哈希、解析状态） |
| GET | `/api/meta/inspection-versions` / `/api/meta/parse-rules` | 检验版本与解析规则 |
| POST | `/api/runs` | 发起核验，body：`inspectionCorrectionVersion`、`cutoffAt`、`createdBy` |
| GET | `/api/runs/{id}` | 核验详情：快照、身份、谱系边、数量、缺口 |
| GET | `/api/runs/{id}/batches/{batchCode}/trace` | 批次全链路证据钻取 |
| POST | `/api/runs/{id}/publish` | 发布范围新版本 |
| GET | `/api/releases` | 发布版本（含依据、差异、发布项） |

### 外部处置系统（`/external`，必须 `X-API-Key`，仅脱敏摘要）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/external/releases` | 可访问发布版本列表 |
| GET | `/external/releases/{code}` | 范围摘要：批次标识、机构/节点、确认/待核/可能三类数量、数量区间、证据等级、追溯链接 |

外部响应字段白名单：`batchCode, nodeName, confirmedQty, pendingQty, possibleQty, lowerBound, upperBound, unit, evidenceGrade, traceLink, conflictFlag`；
**原始 payload、患者/联系人（sensitiveContacts/contactPerson/phone）在任何情况下都不会出现**。

---

## 6. 测试与验证方式

### 自动化测试

```bash
cd backend
mvn test
```

`RecallServiceE2ETest` 在独立 schema `recall_test` 中执行完整场景，断言：

1. 身份歧义保留 ≥2 候选且谱系中不存在供应商批号合并边；
2. RM-A04 超额 15kg（确认下界 60、未归因差 15），冻结边 ≥3 且孙代 FG-C03 确认量为 0；
3. HOSP-乙 数量冲突节点同时保留 50（物流）与 46（机构）两个数字；
4. BOX-9 两条运输边均降级为重复载体证据；
5. PARSE_DIFF 只降级 LOGISTICS 边，MES 的 FEEDING 边保持 CONFIRMED；
6. 两次核验快照分别为 6 / 8 个包；发布后旧版本 `SUPERSEDED` 保留，新版本 diff 同时包含三类变化；
7. 外部接口无 Key/错 Key 返回 401，正确 Key 的响应不含任何敏感字段且仅含白名单字段。

### 已实际执行的验证（本工作区）

- `mvn test`：**Tests run: 1, Failures: 0, Errors: 0 — BUILD SUCCESS**；
- 后端启动后两次核验（RUN1=QC-REV-1/11-10、RUN2=QC-REV-2/11-14）数量区间全部满足 `确认下界 + 待核 = 可能上界`，超额批次另列未归因差额；
- 两次发布：REL-001 被标记 `SUPERSEDED`，REL-002 的 `diff_type=MIXED:INSPECTION_CHANGED+NEW_LOGISTICS_EVIDENCE+PARSE_RULE_REVISED`，结论变化包含 RM-A01 新进范围、RM-A03/IM-B10/IM-B11/FG-C02 移出范围；
- 外部接口：无 Key 与错 Key 均返回 401；正确 Key 响应经程序断言不含 `payload/sensitiveContacts/contactPerson/phone`；
- 前端 `npm run build` 成功，`npm run dev` 启动后页面与 `/api`、`/external` 代理联通。

> 说明：当前容器未安装 docker 二进制，`docker-compose.yml` 已按标准 Compose 规范编写但未在本容器内实际构建；本地开发模式与自动化测试均已实际执行通过。
