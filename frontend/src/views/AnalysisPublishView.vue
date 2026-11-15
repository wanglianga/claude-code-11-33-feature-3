<template>
  <div>
    <h2 class="page-title">分析发布视图（按批次范围生效日期）</h2>
    <p class="page-desc">
      按分析请求日期选择生效中的目录条目版本（包装层级变更按日期命中历史版本），输出可用目录条目、受限原因、证据完整度与未决冲突。
      本视图只用于质量分析引用，<b>不是处方或调拨指令</b>，不含库存、需求或替代推荐。
    </p>

    <div class="card">
      <h3>发起替代目录适用性分析</h3>
      <div class="form-row">
        <label class="field">批次范围
          <select v-model="form.scopeCode">
            <option v-for="s in scopes" :key="s.scopeCode" :value="s.scopeCode">{{ s.scopeCode }}（{{ s.scopeName }}）</option>
          </select>
        </label>
        <label class="field">分析日期（决定候选条目版本）
          <input v-model="form.analysisDate" style="width:260px" placeholder="2026-11-15T10:00:00+08:00" />
        </label>
        <button class="btn" :disabled="loading" @click="create">{{ loading ? '生成中…' : '生成发布视图' }}</button>
      </div>
      <p v-if="error" class="small" style="color:var(--red);margin-top:10px">{{ error }}</p>
    </div>

    <div class="card">
      <h3>分析请求（{{ requests.length }}）</h3>
      <table>
        <thead><tr><th>编号</th><th>批次范围</th><th>分析日期</th><th>发布条目</th><th>发起人</th><th></th></tr></thead>
        <tbody>
          <tr v-for="r in requests" :key="r.id">
            <td class="mono">{{ r.requestCode }}</td>
            <td class="mono">{{ r.scopeCode }}</td>
            <td class="nowrap">{{ fmt(r.analysisDate) }}</td>
            <td>{{ r.itemCount }}</td>
            <td>{{ r.createdBy }}</td>
            <td><a href="#" @click.prevent="open(r.id)">{{ activeId === r.id ? '收起' : '打开发布视图' }}</a></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="card">
      <h3>发布视图 · <span class="mono">{{ detail.request.requestCode }}</span>
        <span class="tag gray" style="margin-left:8px">分析日期 {{ fmt(detail.request.analysisDate) }}</span>
      </h3>
      <table>
        <thead><tr>
          <th>目标产品</th><th>命中目录条目</th><th>有效结论</th><th>证据完整度</th><th>未决冲突</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="it in detail.items" :key="it.targetProductCode">
            <td class="mono">{{ it.targetProductCode }}</td>
            <td class="mono">{{ it.entryCode || '—' }}</td>
            <td><span class="tag" :class="verdictClass(it.effectiveVerdict)">{{ verdictLabel(it.effectiveVerdict) }}</span></td>
            <td>{{ it.evidenceCompleteness }}%</td>
            <td>
              <span v-if="!it.unresolvedConflicts.length" class="muted">无</span>
              <span v-for="(c, i) in it.unresolvedConflicts" :key="i" class="tag"
                    :class="c.severity==='BLOCKER'?'red':'amber'" style="margin:2px">
                {{ conflictLabel(c.conflictType) }}
              </span>
            </td>
            <td><a href="#" @click.prevent="toggle(it.targetProductCode)">
              {{ expanded === it.targetProductCode ? '收起下钻' : '展开证据 →' }}
            </a></td>
          </tr>
        </tbody>
      </table>

      <div v-for="it in detail.items" :key="'d'+it.targetProductCode">
        <div v-if="expanded === it.targetProductCode" class="card" style="background:var(--panel-2);margin-top:12px">
          <h4>受限原因</h4>
          <ul class="small" style="margin:4px 0 12px 18px">
            <li v-for="(r, i) in it.restrictionReasons" :key="i">{{ r }}</li>
            <li v-if="!it.restrictionReasons.length" class="muted">无受限原因</li>
          </ul>

          <h4>① 原始目录位置（来源文档）</h4>
          <table>
            <thead><tr><th>文档</th><th>类型</th><th>来源</th><th>来源位置</th><th>生效期间</th><th>哈希</th></tr></thead>
            <tbody>
              <tr v-for="d in it.drilldown.sourceDocuments" :key="d.docCode">
                <td class="mono">{{ d.docCode }}</td>
                <td><span class="tag cyan">{{ d.docType }}</span></td>
                <td>{{ d.sourceName }}</td>
                <td class="small mono">{{ d.sourceLocation }}</td>
                <td class="small nowrap">{{ fmt(d.effectiveFrom) }} ~ {{ fmt(d.effectiveTo) || '长期' }}</td>
                <td class="mono small">{{ d.payloadHash.slice(0,14) }}…</td>
              </tr>
            </tbody>
          </table>

          <h4 style="margin-top:14px">② 受控属性（语义字典）</h4>
          <table>
            <thead><tr><th>属性</th><th>归一化值</th><th>原始值</th><th>可换算</th><th>来源回链</th></tr></thead>
            <tbody>
              <tr v-for="(a, i) in it.drilldown.controlledAttributes" :key="i">
                <td><span class="tag cyan">{{ attrLabel(a.attrName) }}</span></td>
                <td class="mono small">{{ a.normalizedValue }}</td>
                <td class="small muted">{{ a.rawValue }}</td>
                <td><span :class="['tag', a.convertible ? 'green' : 'red']">{{ a.convertible ? '是' : '否' }}</span></td>
                <td class="small mono">{{ a.sourceDocCode }} · {{ a.sourceLocation }}</td>
              </tr>
            </tbody>
          </table>

          <h4 style="margin-top:14px">③ 适用性声明与审核决定</h4>
          <div class="small" v-if="it.drilldown.statement">
            <span class="mono">{{ it.drilldown.statement.statementCode }}</span>
            <span class="tag" :class="verdictClass(it.drilldown.statement.verdict)" style="margin:0 6px">
              {{ verdictLabel(it.drilldown.statement.verdict) }}
            </span>
            <span class="tag" :class="it.drilldown.statement.reviewStatus==='APPROVED'?'green':'amber'">
              {{ reviewLabel(it.drilldown.statement.reviewStatus) }}
            </span>
            <span style="margin-left:8px">审核人 {{ it.drilldown.statement.reviewer || '—' }}：{{ it.drilldown.statement.reviewNote || '—' }}</span>
          </div>

          <h4 style="margin-top:14px">④ 反例试验（可反驳样例）</h4>
          <table>
            <thead><tr><th>场景</th><th>输入摘要</th><th>预期边界</th><th>结果</th><th>阻断</th></tr></thead>
            <tbody>
              <tr v-for="(t, i) in it.drilldown.counterexampleTrials" :key="i">
                <td><span class="tag cyan">{{ trialLabel(t.scenarioType) }}</span></td>
                <td class="small">{{ t.inputSummary }}</td>
                <td class="small muted">{{ t.expectedBoundary }}</td>
                <td><span :class="['tag', t.result==='PASS'?'green':t.result==='FAIL'?'red':'amber']">{{ trialResult(t.result) }}</span></td>
                <td><span v-if="t.blocksApproval" class="tag red">阻断</span><span v-else class="muted">—</span></td>
              </tr>
            </tbody>
          </table>

          <h4 style="margin-top:14px">⑤ 候选版本（按分析日期命中）</h4>
          <div class="small">
            <span v-for="(c, i) in it.drilldown.candidateVersions" :key="i" class="tag gray" style="margin:2px">
              {{ c.entryCode }} V{{ c.versionNo }} · {{ verdictLabel(c.verdict) }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, VERDICT_LABELS, REVIEW_LABELS, CONFLICT_LABELS, TRIAL_LABELS, ATTR_LABELS } from '../api.js'

const scopes = ref([])
const requests = ref([])
const detail = ref(null)
const activeId = ref(null)
const expanded = ref(null)
const loading = ref(false)
const error = ref('')
const form = ref({ scopeCode: 'SCOPE-2026-11', analysisDate: '2026-11-15T10:00:00+08:00' })

const fmt = (t) => t ? String(t).replace('T', ' ').slice(0, 15) : ''
const verdictLabel = (v) => VERDICT_LABELS[v] || v
const reviewLabel = (v) => REVIEW_LABELS[v] || v
const conflictLabel = (v) => CONFLICT_LABELS[v] || v
const trialLabel = (v) => TRIAL_LABELS[v] || v
const attrLabel = (v) => ATTR_LABELS[v] || v
const trialResult = (r) => ({ PASS: '通过', FAIL: '失败（反驳）', UNVERIFIED: '未证实' }[r] || r)
const verdictClass = (v) => v === 'COMPARABLE' ? 'green' : v === 'NOT_COMPARABLE' ? 'red' : v === 'PENDING_REVIEW' ? 'amber' : 'purple'

async function create() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.post('/api/substitution/requests', { ...form.value, createdBy: '质量分析员-张某' })
    requests.value = await api.get('/api/substitution/requests')
    await open(res.requestId)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function open(id) {
  if (activeId.value === id) {
    activeId.value = null
    detail.value = null
    return
  }
  activeId.value = id
  expanded.value = null
  detail.value = await api.get(`/api/substitution/requests/${id}`)
}

function toggle(code) {
  expanded.value = expanded.value === code ? null : code
}

onMounted(async () => {
  scopes.value = await api.get('/api/substitution/scopes')
  requests.value = await api.get('/api/substitution/requests')
})
</script>
