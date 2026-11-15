# 药品批次谱系数据质量、召回影响与替代目录语义对齐服务

面向药品质量团队的两套相互独立又共享同一工作台的质量能力：

**能力一：批次谱系数据质量与召回影响核验。** 检验结果更正后，回答监管方的核心问题：

- 截至某个**发布时点（截止时间）**，哪些成品批次可能使用了受影响原料；
- 数量能够确认到什么程度（**确认量下界 / 待核量 / 可能受影响量上界 / 未能归因差额**）；
- 哪些机构或运输节点出现在**可验证的流转证据**中；
- 每一项结论**缺少什么证据、阻断了哪个结论**。

**能力二：替代目录语义对齐与适用性声明质量服务。** 质量分析人员需要解释一份替代目录**为何能够或不能**用于某个已发布批次范围的质量分析。目录中的品名、剂型、浓度、包装、给药途径、适用限制与证据等级来自不同供应商、药典版本与规格主数据；同一药品既可能因单位换算看似相同，也可能因辅料、无菌要求或人群限制而不能视为同类。该能力：

- 把目录拆成**带来源回链的受控属性**，而不是用名称做模糊匹配；
- 以**「可比较 / 仅可在附加条件下比较 / 不可比较 / 待核」**四档记录每条适用性声明的属性级依据与审核状态；
- 用**矛盾审查器**与**规则试验台反例**保证声明不会因名称相似而越界；
- 按批次范围的生效日期输出可解释的发布视图。

技术栈限定：**Vue 3 + Spring Boot 3.5 + PostgreSQL 18**。

> 服务边界：本系统只做数据质量核验、范围发布与替代目录适用性解释，
> **不推荐某家机构使用哪种药品，不计算库存、需求或调拨量，不生成处方或替代推荐，不预留库存，
> 不隔离、销毁、退运、调拨或审批付款，不向机构下达通知。** 批次范围只提供待分析的产品标识与生效日期。

---

## 1. 核心设计

### 1.1 来源断言与发布范围分别版本化（召回域）

系统不覆盖旧事实，而是把“来源断言”和“发布范围”分别版本化，使三类变化可被区分和复现：

| 变化类型 | 识别方式 | 系统行为 |
| --- | --- | --- |
| 检验结论变了 `INSPECTION_CHANGED` | 核验选择的检验更正版本不同 | 生成新发布版本，保留旧版本输入依据与受影响结论 |
| 新物流/机构凭证补到 `NEW_LOGISTICS_EVIDENCE` | 新快照中出现旧快照没有的物流/机构包 | 迟到回报只进入新版本，不改变历史答复 |
| 解析规则修订 `PARSE_RULE_REVISED` | 包哈希有效但解析版本落后且字段语义受影响 | 标记 `PARSE_DIFF`，要求重新审阅关系边，不回填旧范围 |

每个来源包均保留**原始载荷、接收时间、事件时间、来源修订号、文件哈希（SHA-256）、解析规则版本、解析状态**。
每次核验在 `verification_run.input_snapshot` 中锁定截止时间前的来源包清单，后续补录无法混入。

#### 六条强制规则的实现位置

| 业务规则 | 实现 |
| --- | --- |
| 同一内部批号对应两个供应商批号且无可验证凭证 → 保留双候选、不合并、扩大上界 | `IdentityResolutionService` + `QuantityQualityEngine`（缺口 `IDENTITY_AMBIGUITY`） |
| 拆分投料合计超过原料可用量 → 冻结整条分配链确认量，展示下界与超额差额 | `QuantityQualityEngine`（缺口 `OVER_FEEDING`，边状态 `FROZEN`，冻结沿 BFS 传播到全部下游代际） |
| 机构回报与物流签收不一致且双凭证在截止前有效 → 数量冲突，任一来源不得覆盖另一来源 | `QuantityQualityEngine.attachNodeQuantities`（缺口 `QUANTITY_CONFLICT`） |
| 检验更正发生在发布之后 → 新候选版本，旧版本只读保留 | `ReleaseService`（旧版本置 `SUPERSEDED`，记录 `diff_type` 与 `affected_conclusions`） |
| 箱码在两条互不相连运输记录中重复 → 重复载体证据，两条边都不得证明链路完整 | `LineageCompiler`（缺口 `DUPLICATE_BOX`，两条运输边降级 `SUSPECTED`） |
| 哈希通过但解析规则升级导致字段含义变化 → 解析差异，重新审阅关系边，不回填旧范围 | `IngestionService.resolveParseStatus` + `LineageCompiler`（仅降级受影响事实类型的边） |

另有 `MISSING_FEEDING_EVIDENCE`（缺失投料凭证）、`UNMATCHED_BATCH`（无法匹配批号）两类缺口。

### 1.2 替代目录语义对齐：名称从不参与归并（替代目录域）

把目录名称直接模糊匹配到规格主数据，录入和查询会更快，却会把同名异剂型、不同包装层级或隐藏限制误标为可比较。
因此系统坚持**属性级对齐 + 可反驳样例**，由六个组件协作：

| 组件 | 职责 | 主要类 |
| --- | --- | --- |
| 目录受理器 | 保存供应商目录、药典引用、规格变更通知的**原始版本、来源位置与生效期间** | `SubstitutionSeeder` / `catalog_document` |
| 产品语义字典 | 把剂型、浓度单位、包装层级、给药途径、人群限制、无菌等级（及辅料）拆为**受控属性**，每个归一化字段保留**来源回链** | `semantic_attribute` |
| 适用性声明册 | 以四档结论记录声明、**属性级依据**、附加条件、适用范围、证据完整度与审核状态 | `SuitabilityEngine` / `suitability_statement` |
| 矛盾审查器 | 检查单位换算后数值不一致、规格冲突、限制条件缺失、过期药典引用、同名异剂型、包装层级变更 | `SuitabilityEngine.deriveConflicts` / `catalog_conflict` |
| 规则试验台 | 用边界浓度、包装换算、同名异剂型、条件性限制构造**反例**，未证实/反驳反例直接阻断声明 | `SuitabilityEngine.deriveTrials` / `counterexample_trial` |
| 发布视图 | 按批次范围生效日期选择候选版本，输出可用条目、受限原因、证据完整度、未决冲突与全量下钻 | `AnalysisPublicationService` / `analysis_published_item` |

**版本化原则**：同一条目在不同生效期间的包装层级/规格变化分别保留为独立 `catalog_entry_version`，
分析请求按分析日期命中候选版本，**不用当前包装覆盖历史声明**。

#### 五类验收样例（演示数据内置，自动化测试覆盖）

| # | 场景 | 系统行为 |
| --- | --- | --- |
| 1 | **单位不可换算**：两来源对同一产品分别给 `10 mg/mL` 与 `10 mg/支`，且未给包装体积 | 语义字典把 `mg/支` 浓度保留为 `convertible=false`；矛盾审查器登记 `UNIT_INCONVERTIBLE`；声明判 `NOT_COMPARABLE`，边界浓度/包装换算反例标 `UNVERIFIED` 并阻断 |
| 2 | **同名异剂型**：同一商品名“同松”同时用于片剂与注射液 | 建立 `SAME_NAME_DIFF_FORM` 阻断冲突，要求补充给药途径；两个条目都判 `NOT_COMPARABLE`，同名异剂型反例 `FAIL` 反驳声明，禁止按名称归并 |
| 3 | **过期药典**：药典版本已到期，修订通知未给替代依据 | 登记 `EXPIRED_PHARMACOPOEIA`；声明降为 `PENDING_REVIEW`（待核），不沿用旧结论 |
| 4 | **限制条件缺失**：声明要求辅料含量 ≤ 阈值，来源只列成分名称、无含量数据 | 登记 `MISSING_RESTRICTION`；声明为 `CONDITIONALLY_COMPARABLE` 但 `BLOCKED`，条件性限制反例 `UNVERIFIED` 阻断，人工通过被拒绝（HTTP 409） |
| 5 | **包装层级变更**：同一条目历史为“盒/10支”，当前为“箱/100盒” | 两个生效期间版本分别保留；分析日期 2026-10 命中旧版本、2026-11 命中新版本，历史声明不被当前包装覆盖 |

另有一条全证据通过的干净基线（`TGT-01`，证据完整度 100%，声明 `COMPARABLE/APPROVED`），证明“可比较”同样需要属性级依据。

---

## 2. 目录结构

```
.
├── backend/                 # Spring Boot 服务（JDK 25 运行，编译目标 21）
│   ├── src/main/java/com/trace/recall/
│   │   ├── common/          # JSONB 归一化、SHA-256、弹性时间解析
│   │   ├── config/          # CORS、外部 API Key 拦截器、全局异常
│   │   ├── ingestion/       # 接入登记台 + 演示样例 Seeder
│   │   ├── identity/        # 批次身份解析（歧义保留）
│   │   ├── lineage/         # 谱系编译器（有向关系边）
│   │   ├── quality/         # 数量质量引擎 + 证据缺口
│   │   ├── verification/    # 核验编排（快照锁定）
│   │   ├── release/         # 范围发布器（版本化、脱敏）
│   │   ├── substitution/    # 受理/语义字典/声明册/矛盾/反例/发布视图
│   │   └── web/             # 内部 /api、替代目录 /api/substitution、外部 /external 控制器
│   ├── src/main/resources/  # application.yml、schema.sql（含两套域表）
│   └── src/test/            # 端到端规则测试（独立 recall_test schema）
├── frontend/                # Vue 3 + Vite + vue-router
│   └── src/views/           # 核验/接入/详情/钻取/发布/外部 + 替代目录三个工作台
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
- 后端首次启动自动建表并播种：召回域 8 个来源包、13 个批次；替代目录域 7 份文档、7 个条目（8 个版本）、8 条声明、2 个分析请求。

### 方式 B：本地开发模式（使用容器内预装运行时）

前置：PostgreSQL 已在 127.0.0.1:5432 运行，库/用户/密码为 `ccdev`。

```bash
# 1) 后端（终端 1）
cd backend
mvn spring-boot:run
# 启动后自动执行 schema.sql 并播种两套域的演示数据

# 2) 前端（终端 2）
cd frontend
npm install
npm run dev
# 打开 http://localhost:8081 （Vite 已配置 /api 与 /external 代理到 8080）
```

> 若工作目录挂载在 9p/DRVFS（Windows 盘映射）上，`npm install` 抽取大量小文件可能报 `TAR_ENTRY_ERROR`。
> 这是挂载文件系统限制，与工程无关；Docker 构建在标准 Linux  overlay 文件系统上不受影响。
> 本地可把前端复制到 ext4/overlay 目录（如 `/tmp`）再 `npm install && npm run build` 验证构建。

### 测试账号 / 凭证

| 用途 | 凭证 |
| --- | --- |
| 内部质量工作台（含替代目录工作台） | 无需登录（内网工作台），默认发起人 `质量分析员-张某`、审核人 `质量审核员-李某` |
| 外部处置系统 API Key（请求头 `X-API-Key`） | `ext-trace-key-2026`（可用环境变量 `EXTERNAL_API_KEY` 覆盖） |
| 数据库 | 库/用户/密码 `ccdev` |

---

## 4. 演示数据与操作路径

### 4.1 召回域时间线（2026-11，UTC+8）

| 日期 | 事件 |
| --- | --- |
| 11-01 | 主数据；RM-A02 同时被供应商批号 SUP-LOT-22 / SUP-LOT-23 观测，无装箱凭证（**身份歧义**） |
| 11-02 | 检验 V1 `QC-REV-1`：RM-A03、RM-A04 不合格 |
| 11-03 | 投料/拆分/混批/放行：RM-A04 拆分合计 75kg > 可用 60kg（**超额冻结**）；RM-A02→IM-B30 缺投料凭证 |
| 11-05 | 解析规则升级 PARSE-2.0（影响 LOGISTICS、BLEND 字段语义） |
| 11-06 | 物流：箱码 BOX-9 在两条互不相连运输记录重复（**重复载体**）；PL-UNKNOWN-9 无法匹配；PKG-LOG-2 仍按 PARSE-1.9 解析（**解析差异**） |
| 11-07 | 机构回报 R1/R2：HOSP-乙 回报 46kg vs 物流签收 50kg（**数量冲突**） |
| **11-10 18:00** | **截止时间 #1**，检验版本 QC-REV-1（快照 6 个包） |
| 11-12 | 检验更正 V2 `QC-REV-2`：RM-A01 改判不合格、RM-A03 复检合格 |
| 11-13 | 迟到机构回报 R3（HOSP-丙，FG-C03） |
| **11-14 18:00** | **截止时间 #2**，检验版本 QC-REV-2（快照 8 个包） |

操作：在「范围核验台」分别发起两次核验 → 查看数量区间/谱系图/身份候选/证据缺口/快照 →
对批次「展开证据」下钻原始载荷 → 分别发布，在「发布版本管理」查看 `SUPERSEDED` 保留与三类差异 →
在「外部接口（脱敏）」验证无 Key 返回 401。

### 4.2 替代目录域操作

1. 打开「替代目录受理/语义字典」：查看 7 份受理文档的来源位置与生效期间，逐条展开 6 类受控属性及其来源回链；
2. 打开「适用性声明/反例审核」：核对四档结论、属性级依据、矛盾审查器与规则试验台反例；
   对被阻断声明点「审核通过」会被拒绝（409），干净基线可正常通过/驳回；
3. 打开「替代目录分析发布」：内置 2026-10-15 与 2026-11-15 两个分析请求，
   可切换日期重新发起；每个发布条目「展开证据」可下钻到
   **① 原始目录位置 ② 受控属性 ③ 适用性声明与审核决定 ④ 反例试验 ⑤ 按日期命中的候选版本**。

---

## 5. HTTP 接口摘要

### 内部工作台（`/api`，无 Key，可查看原始载荷）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/source-packages` / `/{code}` | 来源包清单与原始载荷（含哈希、解析状态） |
| GET | `/api/meta/inspection-versions` / `/api/meta/parse-rules` | 检验版本与解析规则 |
| GET | `/api/batches` | 批次主数据 |
| POST | `/api/runs` | 发起核验，body：`inspectionCorrectionVersion`、`cutoffAt`、`createdBy` |
| GET | `/api/runs` / `/api/runs/{id}` | 核验列表与详情（快照、身份、谱系边、数量、缺口） |
| GET | `/api/runs/{id}/batches/{batchCode}/trace` | 批次全链路证据钻取 |
| POST | `/api/runs/{id}/publish` | 发布范围新版本 |
| GET | `/api/releases` | 发布版本（含依据、差异、发布项） |

### 替代目录工作台（`/api/substitution`，无 Key，仅质量分析用途）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/substitution/scopes` | 批次范围与目标产品（仅产品标识与生效日期） |
| GET | `/api/substitution/documents` | 受理文档（原始版本、来源位置、生效期间、哈希、原始载荷） |
| GET | `/api/substitution/entries` | 目录条目、版本与受控属性（含来源回链、可换算标记） |
| GET | `/api/substitution/statements` | 适用性声明册（四档结论、属性级依据、条件、矛盾、反例） |
| GET | `/api/substitution/conflicts` | 矛盾审查器登记的冲突 |
| GET | `/api/substitution/requests` / `/{id}` | 分析请求列表与发布视图（含全量下钻） |
| POST | `/api/substitution/requests` | 按 `scopeCode` + `analysisDate` 生成发布视图 |
| POST | `/api/substitution/statements/{id}/review` | 人工审核 `APPROVE`/`REJECT`；有阻断反例或未决阻断矛盾时返回 409 |

### 外部处置系统（`/external`，必须 `X-API-Key`，仅脱敏摘要）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/external/releases` | 可访问发布版本列表 |
| GET | `/external/releases/{code}` | 范围摘要：批次标识、节点、三类数量、区间、证据等级、追溯链接 |

外部响应字段白名单：`batchCode, nodeName, confirmedQty, pendingQty, possibleQty, lowerBound, upperBound, unit, evidenceGrade, traceLink, conflictFlag`；
**原始 payload、患者/联系人（sensitiveContacts/contactPerson/phone）在任何情况下都不会出现**。替代目录数据不通过外部接口输出。

---

## 6. 测试与验证方式

### 自动化测试

```bash
cd backend
mvn test
```

- `RecallServiceE2ETest`（独立 schema `recall_test`）断言七条：
  1. 身份歧义保留 ≥2 候选且谱系中不存在供应商批号合并边；
  2. RM-A04 超额 15kg（确认下界 60、未归因差 15），冻结边 ≥3 且孙代 FG-C03 确认量为 0；
  3. HOSP-乙 数量冲突节点同时保留 50（物流）与 46（机构）两个数字；
  4. BOX-9 两条运输边均降级为重复载体证据；
  5. PARSE_DIFF 只降级 LOGISTICS 边，MES 的 FEEDING 边保持 CONFIRMED；
  6. 两次核验快照分别为 6 / 8 个包；发布后旧版本 `SUPERSEDED`，新版本 diff 同时含三类变化；
  7. 外部接口无 Key/错 Key 返回 401，正确 Key 的响应不含敏感字段且仅含白名单字段。
- `SubstitutionServiceE2ETest` 断言七项：干净基线可比较且依据带回链；单位不可换算判不可比较并阻断反例；
  同名异剂型两个条目均冲突且反例反驳；过期药典降待核且无替代依据；辅料含量缺失阻断条件声明且人工通过返回 409；
  包装层级变更按分析日期分别命中历史/当前版本；每个发布条目下钻五要素齐全且属性均有回链。

### 已实际执行的验证（本工作区）

- `mvn test`：**Tests run: 14, Failures: 0, Errors: 0 — BUILD SUCCESS**；
- 后端 `mvn spring-boot:run` 启动成功并完成两套域播种（8 来源包/13 批次；7 文档/8 声明/2 分析请求）；
- 召回域两次核验（QC-REV-1/11-10、QC-REV-2/11-14）数量区间满足 `确认下界 + 待核 = 可能上界`，超额批次另列未归因差额；
  两次发布后 REL-001 标记 `SUPERSEDED`，REL-002 `diff_type=MIXED:INSPECTION_CHANGED+NEW_LOGISTICS_EVIDENCE+PARSE_RULE_REVISED`，
  RM-A01 新进范围、RM-A03 及其下游移出范围；
- 替代目录域：TGT-02 单位不可换算、TGT-03 同名异剂型、TGT-04 过期药典降待核、TGT-05 限制缺失阻断（审核 409）、
  TGT-06 两个分析日期分别命中“盒/10支”与“箱/100盒”；干净基线 TGT-01 审核通过 200；
- 外部接口：无 Key 与错 Key 均 401；正确 Key 响应经断言不含 `payload/sensitiveContacts/contactPerson/phone` 且字段全部在白名单内；
- 前端 `npm run build` 成功（36 个模块，含三个替代目录工作台）；`npm run dev` 启动后页面与 `/api`、`/external` 代理联通。

> 说明：当前容器未安装 docker 二进制，`docker-compose.yml` 与两个 Dockerfile 已按标准规范编写但未在本容器内实际构建；
> 本地开发模式与全部自动化测试均已实际执行通过。前端 `npm install` 在本容器的 9p 挂载盘上会因文件系统限制失败，
> 已在 overlay 文件系统上验证安装与构建，Docker 构建不受该限制影响。
