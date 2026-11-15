<template>
  <div>
    <h2 class="page-title">适用性声明册 · 矛盾审查器 · 规则试验台</h2>
    <p class="page-desc">
      每条声明以<b>「可比较 / 仅可在附加条件下比较 / 不可比较 / 待核」</b>记录结论、属性级依据、适用范围与审核状态；
      存在阻断反例或未决阻断矛盾时，人工通过会被拒绝。名称从不参与归并判断。
    </p>

    <div class="card">
      <h3>矛盾审查器（{{ conflicts.length }}）</h3>
      <table>
        <thead><tr><th>编号</th><th>类型</th><th>级别</th><th>条目</th><th>说明</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="c in conflicts" :key="c.conflictCode">
            <td class="mono">{{ c.conflictCode }}</td>
            <td><span class="tag" :class="c.severity==='BLOCKER'?'red':'amber'">{{ conflictLabel(c.conflictType) }}</span></td>
            <td>{{ c.severity==='BLOCKER' ? '阻断' : '警告' }}</td>
            <td class="mono">{{ c.entryCode }}</td>
            <td class="small">{{ c.description }}</td>
            <td><span class="tag gray">{{ c.status }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card" v-for="s in statements" :key="s.id">
      <h3>
        <span class="mono">{{ s.statementCode }}</span> · {{ s.productName }}
        <span class="tag purple" style="margin:0 6px">目标 {{ s.targetProductCode }}</span>
        <span class="tag" :class="verdictClass(s.verdict)">{{ verdictLabel(s.verdict) }}</span>
        <span class="tag" :class="reviewClass(s.reviewStatus)" style="margin-left:6px">{{ reviewLabel(s.reviewStatus) }}</span>
        <span class="tag gray" style="margin-left:6px">证据完整度 {{ s.evidenceCompleteness }}%</span>
      </h3>
      <p class="small" style="margin:4px 0 10px">
        条目 <span class="mono">{{ s.entryCode }}</span> · 适用范围：{{ s.applicabilityScope }}
        · 审核人：{{ s.reviewer || '—' }} · 审核意见：{{ s.reviewNote || '—' }}
      </p>

      <div class="grid-2">
        <div>
          <h4>属性级依据（每条带来源回链）</h4>
          <table>
            <thead><tr><th>属性</th><th>归一化值</th><th>可换算</th><th>来源位置</th></tr></thead>
            <tbody>
              <tr v-for="(b, i) in s.basis" :key="i">
                <td class="small">{{ attrLabel(b.attrName) }}</td>
                <td class="mono small">{{ b.normalizedValue }}</td>
                <td><span :class="['tag', b.convertible ? 'green' : 'red']">{{ b.convertible ? '是' : '否' }}</span></td>
                <td class="small mono">{{ b.sourceDocCode }}<br/>{{ b.sourceLocation }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div>
          <h4>附加条件 / 矛盾</h4>
          <table v-if="s.conditions.length">
            <thead><tr><th>条件</th><th>证据是否齐备</th></tr></thead>
            <tbody>
              <tr v-for="(c, i) in s.conditions" :key="i">
                <td class="small">{{ describeCondition(c) }}</td>
                <td><span :class="['tag', c.evidenceProvided ? 'green' : 'red']">{{ c.evidenceProvided ? '已证实' : '未证实' }}</span></td>
              </tr>
            </tbody>
          </table>
          <p v-else class="muted small">无附加条件</p>
          <div v-for="c in s.conflicts" :key="c.conflictCode" class="gap-item"
               :class="c.severity==='WARNING' ? 'warning' : ''" style="margin-top:8px">
            <b>{{ conflictLabel(c.conflictType) }}</b>
            <div class="small" style="margin-top:4px">{{ c.description }}</div>
          </div>
        </div>
      </div>

      <h4>规则试验台反例（{{ s.trials.length }}）</h4>
      <table>
        <thead><tr>
          <th>编号</th><th>场景</th><th>输入摘要</th><th>预期边界</th><th>结果</th><th>反驳</th><th>阻断</th>
        </tr></thead>
        <tbody>
          <tr v-for="t in s.trials" :key="t.trialCode">
            <td class="mono small">{{ t.trialCode }}</td>
            <td><span class="tag cyan">{{ trialLabel(t.scenarioType) }}</span></td>
            <td class="small">{{ t.inputSummary }}</td>
            <td class="small muted">{{ t.expectedBoundary }}</td>
            <td><span :class="['tag', t.result==='PASS'?'green':t.result==='FAIL'?'red':'amber']">{{ trialResult(t.result) }}</span></td>
            <td>{{ t.refutesStatement ? '是' : '否' }}</td>
            <td><span v-if="t.blocksApproval" class="tag red">阻断声明</span><span v-else class="muted">—</span></td>
          </tr>
        </tbody>
      </table>

      <div style="margin-top:12px">
        <button class="btn" :disabled="busy===s.id" @click="review(s.id, 'APPROVE')">审核通过</button>
        <button class="btn secondary" style="margin-left:8px" :disabled="busy===s.id" @click="review(s.id, 'REJECT')">驳回</button>
        <span v-if="feedback[s.id]" class="small" style="margin-left:12px" :style="{color: feedbackOk[s.id] ? 'var(--green)' : 'var(--red)'}">{{ feedback[s.id] }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, VERDICT_LABELS, REVIEW_LABELS, CONFLICT_LABELS, TRIAL_LABELS, ATTR_LABELS } from '../api.js'

const statements = ref([])
const conflicts = ref([])
const busy = ref(null)
const feedback = ref({})
const feedbackOk = ref({})

const verdictLabel = (v) => VERDICT_LABELS[v] || v
const reviewLabel = (v) => REVIEW_LABELS[v] || v
const conflictLabel = (v) => CONFLICT_LABELS[v] || v
const trialLabel = (v) => TRIAL_LABELS[v] || v
const attrLabel = (v) => ATTR_LABELS[v] || v
const trialResult = (r) => ({ PASS: '通过', FAIL: '失败（反驳声明）', UNVERIFIED: '未证实' }[r] || r)
const verdictClass = (v) => v === 'COMPARABLE' ? 'green' : v === 'NOT_COMPARABLE' ? 'red' : v === 'PENDING_REVIEW' ? 'amber' : 'purple'
const reviewClass = (v) => v === 'APPROVED' ? 'green' : v === 'REJECTED' ? 'red' : v === 'BLOCKED' ? 'red' : 'amber'

function describeCondition(c) {
  if (c.type === 'EXCIPIENT_LIMIT') {
    return `辅料「${c.subject}」含量 ≤ ${c.maxPercent}%`
  }
  if (c.type === 'POPULATION_RESTRICTION') {
    return `人群限制：${c.restriction}`
  }
  return JSON.stringify(c)
}

async function review(id, decision) {
  busy.value = id
  feedback.value[id] = ''
  try {
    await api.post(`/api/substitution/statements/${id}/review`, {
      decision, reviewer: '质量审核员-李某', reviewNote: decision === 'APPROVE' ? '工作台人工通过' : '工作台人工驳回'
    })
    feedbackOk.value[id] = true
    feedback.value[id] = decision === 'APPROVE' ? '已通过' : '已驳回'
    statements.value = await api.get('/api/substitution/statements')
  } catch (e) {
    feedbackOk.value[id] = false
    feedback.value[id] = '操作被拒绝：' + e.message
  } finally {
    busy.value = null
  }
}

onMounted(async () => {
  statements.value = await api.get('/api/substitution/statements')
  conflicts.value = await api.get('/api/substitution/conflicts')
})
</script>
