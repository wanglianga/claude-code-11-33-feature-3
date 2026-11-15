-- 药品批次谱系数据质量与召回影响服务 · 审计级 schema
-- 所有事实均保留：原始载荷、接收时间、事件时间、来源修订号、文件哈希、解析版本

CREATE TABLE IF NOT EXISTS source_package (
    id                       BIGSERIAL,
    package_code             TEXT NOT NULL UNIQUE,
    source_system            TEXT NOT NULL,            -- QC_LAB / MES / WMS / LOGISTICS / INSTITUTION / MDM
    source_revision          TEXT NOT NULL,            -- 来源修订号
    payload_hash             TEXT NOT NULL,            -- 原始载荷 SHA-256
    parse_rule_version       TEXT NOT NULL,            -- 接收时解析规则版本
    parse_status             TEXT NOT NULL,            -- PARSED / PARSE_DIFF / FAILED
    received_at              TIMESTAMPTZ NOT NULL,     -- 接收时间
    event_time               TIMESTAMPTZ NOT NULL,     -- 事件时间
    supersedes_package_code  TEXT,
    payload                  JSONB NOT NULL,           -- 原始载荷（内部可见，含敏感字段）
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS parse_rule (
    version        TEXT PRIMARY KEY,
    effective_at   TIMESTAMPTZ NOT NULL,
    description    TEXT NOT NULL,
    affected_types TEXT NOT NULL                       -- 受影响事实类型，逗号分隔
);

CREATE TABLE IF NOT EXISTS raw_fact (
    id            BIGSERIAL PRIMARY KEY,
    package_id    BIGINT NOT NULL REFERENCES source_package(id),
    fact_type     TEXT NOT NULL,                       -- INSPECTION/FEEDING/RELEASE/SPLIT/BLEND/LOGISTICS/INSTITUTION_REPORT/MASTER_DATA
    fact_code     TEXT NOT NULL UNIQUE,
    event_time    TIMESTAMPTZ NOT NULL,
    payload       JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS batch_master (
    batch_code    TEXT PRIMARY KEY,                    -- 内部批号
    batch_type    TEXT NOT NULL,                       -- RAW / INTERMEDIATE / FINISHED
    product_code  TEXT,
    product_name  TEXT,
    unit          TEXT NOT NULL DEFAULT 'kg',
    quantity      NUMERIC(14,3),
    package_id    BIGINT REFERENCES source_package(id),
    attributes    JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 核验请求（一次范围核验）：锁定检验更正版本 + 截止时间 + 输入快照
CREATE TABLE IF NOT EXISTS verification_run (
    id                           BIGSERIAL PRIMARY KEY,
    run_code                     TEXT NOT NULL UNIQUE,
    inspection_correction_version TEXT NOT NULL,       -- 选定的检验更正版本
    cutoff_at                    TIMESTAMPTZ NOT NULL, -- 截止时间
    rule_version                 TEXT NOT NULL,
    status                       TEXT NOT NULL,        -- LOCKED / PUBLISHED / SUPERSEDED
    input_snapshot               JSONB NOT NULL,       -- 锁定的来源包、哈希、解析状态
    created_by                   TEXT NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 身份观测（供应商批号/内部批号/箱码/生产批号）
CREATE TABLE IF NOT EXISTS identity_record (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    code_type            TEXT NOT NULL,                -- SUPPLIER_LOT / INTERNAL_LOT / BOX_CODE / PRODUCTION_LOT
    code_value           TEXT NOT NULL,
    observed_batch_code  TEXT,                         -- 观测指向的内部批次（NULL=未匹配）
    match_status         TEXT NOT NULL,                -- MATCHED / AMBIGUOUS / UNMATCHED
    UNIQUE (run_id, code_type, code_value)
);

CREATE TABLE IF NOT EXISTS identity_candidate (
    id                   BIGSERIAL PRIMARY KEY,
    identity_record_id   BIGINT NOT NULL REFERENCES identity_record(id) ON DELETE CASCADE,
    candidate_batch_code TEXT NOT NULL,
    confidence           TEXT NOT NULL,                -- HIGH / MEDIUM / LOW
    ambiguous            BOOLEAN NOT NULL DEFAULT false,
    evidence             JSONB NOT NULL DEFAULT '[]'::jsonb
);

-- 谱系边（每次核验独立编译，不可变快照）
CREATE TABLE IF NOT EXISTS lineage_edge (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    edge_code            TEXT NOT NULL,
    upstream_batch       TEXT,                          -- 未匹配运输事实允许为空
    downstream_batch     TEXT NOT NULL,
    relation_type        TEXT NOT NULL,                -- FEEDING/SPLIT/BLEND/RELEASE/SHIPMENT
    source_fact_code     TEXT NOT NULL,
    source_package_code  TEXT NOT NULL,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    confidence_status    TEXT NOT NULL,                -- CONFIRMED / SUSPECTED / FROZEN
    review_required      BOOLEAN NOT NULL DEFAULT false,
    block_reason         TEXT,
    parse_rule_version   TEXT NOT NULL,
    quantity             NUMERIC(14,3),
    unit                 TEXT,
    edge_detail          JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- 批次数量质量状态（每批次每核验）
CREATE TABLE IF NOT EXISTS quantity_state (
    run_id              BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    batch_code          TEXT NOT NULL,
    affected_flag       BOOLEAN NOT NULL DEFAULT false,
    confirmed_lower     NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 确认量下界
    pending_qty         NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 待核量
    possible_upper      NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 可能受影响量上界
    unattributed_delta  NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 未能归因的数量差
    unit                TEXT NOT NULL,
    detail              JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (run_id, batch_code)
);

-- 证据缺口工作台
CREATE TABLE IF NOT EXISTS evidence_gap (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES verification_run(id) ON DELETE CASCADE,
    gap_type           TEXT NOT NULL,  -- MISSING_FEEDING_EVIDENCE/DUPLICATE_BOX/QUANTITY_CONFLICT/UNMATCHED_BATCH/IDENTITY_AMBIGUITY/OVER_FEEDING/PARSE_DIFF
    severity           TEXT NOT NULL,  -- BLOCKER / WARNING
    subject_code       TEXT NOT NULL,
    node_name          TEXT,
    description        TEXT NOT NULL,
    blocks_conclusion  TEXT NOT NULL,  -- 阻断了哪个谱系结论
    evidence           JSONB NOT NULL DEFAULT '[]'::jsonb,
    status             TEXT NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 发布范围版本（来源断言与发布范围分别版本化）
CREATE TABLE IF NOT EXISTS release_version (
    id                        BIGSERIAL PRIMARY KEY,
    release_code              TEXT NOT NULL UNIQUE,
    version_no                INTEGER NOT NULL,
    run_id                    BIGINT NOT NULL REFERENCES verification_run(id),
    status                    TEXT NOT NULL,           -- PUBLISHED / SUPERSEDED
    predecessor_release_code  TEXT,
    diff_type                 TEXT,                    -- NONE/INSPECTION_CHANGED/NEW_LOGISTICS_EVIDENCE/PARSE_RULE_REVISED/MIXED
    diff_detail               JSONB NOT NULL DEFAULT '[]'::jsonb,
    basis                     JSONB NOT NULL,          -- 输入依据：包/哈希/解析版本/规则版本
    affected_conclusions      JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 对外发布项（仅批次标识、机构/节点、数量区间、证据等级、可追溯链接）
CREATE TABLE IF NOT EXISTS release_item (
    id              BIGSERIAL PRIMARY KEY,
    release_id      BIGINT NOT NULL REFERENCES release_version(id) ON DELETE CASCADE,
    batch_code      TEXT NOT NULL,
    node_name       TEXT,
    confirmed_qty   NUMERIC(14,3) NOT NULL DEFAULT 0,
    pending_qty     NUMERIC(14,3) NOT NULL DEFAULT 0,
    possible_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,
    lower_bound     NUMERIC(14,3) NOT NULL DEFAULT 0,
    upper_bound     NUMERIC(14,3) NOT NULL DEFAULT 0,
    unit            TEXT NOT NULL,
    evidence_grade  TEXT NOT NULL,                     -- CONFIRMED / PENDING / POSSIBLE
    trace_link      TEXT NOT NULL,
    conflict_flag   BOOLEAN NOT NULL DEFAULT false
);

-- 外部处置系统凭证
CREATE TABLE IF NOT EXISTS api_client (
    api_key   TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    enabled   BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_fact_package ON raw_fact(package_id);
CREATE INDEX IF NOT EXISTS idx_edge_run ON lineage_edge(run_id);
CREATE INDEX IF NOT EXISTS idx_gap_run ON evidence_gap(run_id);
CREATE INDEX IF NOT EXISTS idx_candidate_record ON identity_candidate(identity_record_id);

-- ============================================================================
-- 替代目录语义对齐与适用性声明质量服务
-- 边界：只解释替代目录“能否用于”已发布批次范围的质量分析；
-- 不推荐机构用药、不计算库存/需求/调拨量、不生成处方或调拨指令。
-- ============================================================================

-- 已发布批次范围：仅提供待分析的产品标识与生效日期
CREATE TABLE IF NOT EXISTS batch_scope (
    id              BIGSERIAL PRIMARY KEY,
    scope_code      TEXT NOT NULL UNIQUE,
    scope_name      TEXT NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ,
    remark          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS scope_target (
    id                   BIGSERIAL PRIMARY KEY,
    scope_id             BIGINT NOT NULL REFERENCES batch_scope(id) ON DELETE CASCADE,
    target_product_code  TEXT NOT NULL,
    target_product_name  TEXT NOT NULL,
    target_spec          JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 目标产品受控规格（剂型/浓度/途径/无菌/人群）
    UNIQUE (scope_id, target_product_code)
);

-- 目录受理器：供应商目录、药典引用、规格变更通知的原始版本、来源位置与生效期间
CREATE TABLE IF NOT EXISTS catalog_document (
    id                    BIGSERIAL PRIMARY KEY,
    doc_code              TEXT NOT NULL UNIQUE,
    doc_type              TEXT NOT NULL,   -- SUPPLIER_CATALOG / PHARMACOPOEIA_REFERENCE / SPEC_CHANGE_NOTICE
    source_name           TEXT NOT NULL,
    source_revision       TEXT NOT NULL,
    source_location       TEXT NOT NULL,                   -- 来源位置（文件/章节/页码/链接）
    payload_hash          TEXT NOT NULL,
    raw_payload           JSONB NOT NULL,                  -- 原始载荷（内部可见）
    received_at           TIMESTAMPTZ NOT NULL,
    effective_from        TIMESTAMPTZ NOT NULL,
    effective_to          TIMESTAMPTZ,                     -- 药典版本到期日；NULL 表示长期有效
    supersedes_doc_code   TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 目录逻辑条目（同名异剂型保留为不同条目，禁止按名称归并）
CREATE TABLE IF NOT EXISTS catalog_entry (
    id                   BIGSERIAL PRIMARY KEY,
    entry_code           TEXT NOT NULL UNIQUE,
    product_name         TEXT NOT NULL,
    manufacturer         TEXT,
    target_product_code  TEXT,                              -- 被比对的目标产品
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 条目版本：包装层级/规格变化分别保留，分析请求按日期选择候选版本，不用当前包装覆盖历史
CREATE TABLE IF NOT EXISTS catalog_entry_version (
    id               BIGSERIAL PRIMARY KEY,
    entry_id         BIGINT NOT NULL REFERENCES catalog_entry(id) ON DELETE CASCADE,
    version_no       INTEGER NOT NULL,
    doc_id           BIGINT NOT NULL REFERENCES catalog_document(id),
    source_location  TEXT NOT NULL,
    effective_from   TIMESTAMPTZ NOT NULL,
    effective_to     TIMESTAMPTZ,
    raw_record       JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (entry_id, version_no)
);

-- 产品语义字典：剂型/浓度单位/包装层级/给药途径/人群限制/无菌等级拆为受控属性，每个归一化字段保留来源回链
CREATE TABLE IF NOT EXISTS semantic_attribute (
    id                 BIGSERIAL PRIMARY KEY,
    entry_version_id   BIGINT NOT NULL REFERENCES catalog_entry_version(id) ON DELETE CASCADE,
    attr_name          TEXT NOT NULL,  -- DOSAGE_FORM/CONCENTRATION/PACKAGE_LEVEL/PACKAGE_VOLUME/ROUTE/POPULATION_RESTRICTION/STERILITY_GRADE/EXCIPIENT
    normalized_value   TEXT,
    raw_value          TEXT,
    attr_detail        JSONB NOT NULL DEFAULT '{}'::jsonb, -- 数值/单位/量纲/可换算标记
    convertible        BOOLEAN NOT NULL DEFAULT true,      -- FALSE=单位不可换算（如 mg/mL 与 mg/支且缺包装体积）
    source_doc_id      BIGINT NOT NULL REFERENCES catalog_document(id),
    source_location    TEXT NOT NULL,
    UNIQUE (entry_version_id, attr_name, source_doc_id)
);

-- 适用性声明册：可比较 / 仅可在附加条件下比较 / 不可比较（含待核降级态）
CREATE TABLE IF NOT EXISTS suitability_statement (
    id                     BIGSERIAL PRIMARY KEY,
    statement_code         TEXT NOT NULL UNIQUE,
    entry_version_id       BIGINT NOT NULL REFERENCES catalog_entry_version(id) ON DELETE CASCADE,
    target_product_code    TEXT NOT NULL,
    verdict                TEXT NOT NULL,   -- COMPARABLE / CONDITIONALLY_COMPARABLE / NOT_COMPARABLE / PENDING_REVIEW
    review_status          TEXT NOT NULL,   -- DRAFT / PENDING_REVIEW / BLOCKED / APPROVED / REJECTED
    basis                  JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 属性级依据（逐条带回链）
    conditions             JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 附加条件（如辅料阈值、人群限制）
    applicability_scope    TEXT,
    evidence_completeness  NUMERIC(5,2) NOT NULL DEFAULT 0,     -- 证据完整度 0~100
    reviewer               TEXT,
    review_note            TEXT,
    reviewed_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 矛盾审查器：规格冲突 / 单位换算不一致 / 限制缺失 / 过期药典 / 同名异剂型 / 包装层级变更
CREATE TABLE IF NOT EXISTS catalog_conflict (
    id                BIGSERIAL PRIMARY KEY,
    conflict_code     TEXT NOT NULL UNIQUE,
    conflict_type     TEXT NOT NULL, -- UNIT_INCONVERTIBLE/SPEC_CONFLICT/SAME_NAME_DIFF_FORM/MISSING_RESTRICTION/EXPIRED_PHARMACOPOEIA/PACKAGE_LEVEL_CHANGED
    severity          TEXT NOT NULL,  -- BLOCKER / WARNING
    entry_id          BIGINT REFERENCES catalog_entry(id) ON DELETE CASCADE,
    entry_version_id  BIGINT REFERENCES catalog_entry_version(id) ON DELETE CASCADE,
    subject_attr      TEXT,
    description       TEXT NOT NULL,
    source_refs       JSONB NOT NULL DEFAULT '[]'::jsonb,
    status            TEXT NOT NULL DEFAULT 'OPEN', -- OPEN / RESOLVED
    resolution        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ
);

-- 规则试验台：边界浓度/包装换算/同名异剂型/条件性限制反例
CREATE TABLE IF NOT EXISTS counterexample_trial (
    id                BIGSERIAL PRIMARY KEY,
    trial_code        TEXT NOT NULL UNIQUE,
    statement_id      BIGINT NOT NULL REFERENCES suitability_statement(id) ON DELETE CASCADE,
    scenario_type     TEXT NOT NULL, -- BOUNDARY_CONCENTRATION/PACKAGE_CONVERSION/SAME_NAME_DIFF_FORM/CONDITIONAL_RESTRICTION
    input_summary     TEXT NOT NULL,
    expected_boundary TEXT,
    result            TEXT NOT NULL, -- PASS / FAIL / UNVERIFIED
    refutes_statement BOOLEAN NOT NULL DEFAULT false,
    blocks_approval   BOOLEAN NOT NULL DEFAULT false,
    evidence          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 分析请求：批次范围 + 分析日期；发布视图以不可变快照保存
CREATE TABLE IF NOT EXISTS analysis_request (
    id            BIGSERIAL PRIMARY KEY,
    request_code  TEXT NOT NULL UNIQUE,
    scope_id      BIGINT NOT NULL REFERENCES batch_scope(id),
    analysis_date TIMESTAMPTZ NOT NULL,
    status        TEXT NOT NULL DEFAULT 'PUBLISHED',
    created_by    TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 发布视图条目：可用目录条目、受限原因、证据完整度、未决冲突、全量下钻
CREATE TABLE IF NOT EXISTS analysis_published_item (
    id                   BIGSERIAL PRIMARY KEY,
    request_id           BIGINT NOT NULL REFERENCES analysis_request(id) ON DELETE CASCADE,
    target_product_code  TEXT NOT NULL,
    entry_code           TEXT,
    entry_version_id     BIGINT,
    statement_id         BIGINT,
    effective_verdict    TEXT NOT NULL,  -- COMPARABLE / CONDITIONALLY_COMPARABLE / NOT_COMPARABLE / PENDING_REVIEW
    restriction_reasons  JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_completeness NUMERIC(5,2) NOT NULL DEFAULT 0,
    unresolved_conflicts JSONB NOT NULL DEFAULT '[]'::jsonb,
    drilldown            JSONB NOT NULL DEFAULT '{}'::jsonb, -- 原始位置/受控属性/声明/反例/审核决定
    UNIQUE (request_id, target_product_code)
);

CREATE INDEX IF NOT EXISTS idx_entry_version_entry ON catalog_entry_version(entry_id);
CREATE INDEX IF NOT EXISTS idx_attr_version ON semantic_attribute(entry_version_id);
CREATE INDEX IF NOT EXISTS idx_statement_version ON suitability_statement(entry_version_id);
CREATE INDEX IF NOT EXISTS idx_conflict_entry ON catalog_conflict(entry_id);
CREATE INDEX IF NOT EXISTS idx_trial_statement ON counterexample_trial(statement_id);
CREATE INDEX IF NOT EXISTS idx_published_item_request ON analysis_published_item(request_id);
