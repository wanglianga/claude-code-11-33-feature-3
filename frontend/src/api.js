const BASE = ''

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new Error(data?.message || `请求失败 ${res.status}`)
  }
  return data
}

export const api = {
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body: JSON.stringify(body || {}) }),
  externalGet: (path, apiKey) => request(path, { headers: { 'X-API-Key': apiKey } })
}

export const GAP_LABELS = {
  MISSING_FEEDING_EVIDENCE: '缺失投料凭证',
  DUPLICATE_BOX: '重复箱码（重复载体证据）',
  QUANTITY_CONFLICT: '物流/机构数量冲突',
  UNMATCHED_BATCH: '无法匹配的批号',
  IDENTITY_AMBIGUITY: '身份歧义',
  OVER_FEEDING: '超额投料（分配链冻结）',
  PARSE_DIFF: '解析规则差异'
}

export const CONFIDENCE_LABELS = {
  CONFIRMED: '已确认',
  SUSPECTED: '待核/疑似',
  FROZEN: '冻结'
}

export const GRADE_LABELS = {
  CONFIRMED: '确认',
  PENDING: '待核',
  POSSIBLE: '可能受影响'
}

export const VERDICT_LABELS = {
  COMPARABLE: '可比较',
  CONDITIONALLY_COMPARABLE: '仅可在附加条件下比较',
  NOT_COMPARABLE: '不可比较',
  PENDING_REVIEW: '待核（降级）'
}

export const REVIEW_LABELS = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  BLOCKED: '已阻断',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export const CONFLICT_LABELS = {
  UNIT_INCONVERTIBLE: '单位不可换算',
  SPEC_CONFLICT: '规格冲突',
  SAME_NAME_DIFF_FORM: '同名异剂型',
  MISSING_RESTRICTION: '限制条件缺失',
  EXPIRED_PHARMACOPOEIA: '过期药典引用',
  PACKAGE_LEVEL_CHANGED: '包装层级变更'
}

export const TRIAL_LABELS = {
  BOUNDARY_CONCENTRATION: '边界浓度反例',
  PACKAGE_CONVERSION: '包装换算反例',
  SAME_NAME_DIFF_FORM: '同名异剂型反例',
  CONDITIONAL_RESTRICTION: '条件性限制反例'
}

export const ATTR_LABELS = {
  DOSAGE_FORM: '剂型',
  CONCENTRATION: '浓度/单位',
  PACKAGE_LEVEL: '包装层级',
  ROUTE: '给药途径',
  POPULATION_RESTRICTION: '人群限制',
  STERILITY_GRADE: '无菌等级',
  EXCIPIENT: '辅料'
}
