<template>
  <div>
    <h2 class="page-title">目录受理台 · 产品语义字典</h2>
    <p class="page-desc">
      受理器保存供应商目录、药典引用与规格变更通知的<b>原始版本、来源位置与生效期间</b>；
      语义字典把剂型、浓度单位、包装层级、给药途径、人群限制、无菌等级拆成受控属性，每个归一化字段都保留来源回链。
    </p>

    <div class="card" v-for="scope in scopes" :key="scope.scopeCode">
      <h3>批次范围 <span class="mono">{{ scope.scopeCode }}</span>
        <span class="tag gray" style="margin-left:8px">{{ scope.scopeName }}</span>
      </h3>
      <p class="small">生效期间 {{ fmt(scope.effectiveFrom) }} ~ {{ fmt(scope.effectiveTo) || '长期' }}；{{ scope.remark }}</p>
      <table>
        <thead><tr><th>目标产品</th><th>名称</th><th>目标受控规格</th></tr></thead>
        <tbody>
          <tr v-for="t in scope.targets" :key="t.targetProductCode">
            <td class="mono">{{ t.targetProductCode }}</td>
            <td>{{ t.targetProductName }}</td>
            <td class="small">{{ JSON.stringify(t.targetSpec) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>受理文档（{{ documents.length }}）· 原始版本 / 来源位置 / 生效期间</h3>
      <table>
        <thead><tr>
          <th>文档编号</th><th>类型</th><th>来源</th><th>修订号</th><th>来源位置</th>
          <th>生效期间</th><th>哈希</th><th></th>
        </tr></thead>
        <tbody>
          <tr v-for="d in documents" :key="d.docCode">
            <td class="mono">{{ d.docCode }}</td>
            <td><span class="tag cyan">{{ docTypeLabel(d.docType) }}</span></td>
            <td>{{ d.sourceName }}</td>
            <td class="mono">{{ d.sourceRevision }}</td>
            <td class="small mono">{{ d.sourceLocation }}</td>
            <td class="nowrap small">{{ fmt(d.effectiveFrom) }}<br/>~ {{ fmt(d.effectiveTo) || '长期有效' }}</td>
            <td class="mono small">{{ d.payloadHash.slice(0,14) }}…</td>
            <td><a href="#" @click.prevent="openDoc(d)">原始载荷</a></td>
          </tr>
        </tbody>
      </table>
      <pre v-if="currentDoc" class="payload">{{ JSON.stringify(currentDoc.rawPayload, null, 2) }}</pre>
    </div>

    <div class="card">
      <h3>目录条目与语义属性（{{ entries.length }}）· 同名异剂型保留为独立条目，禁止按名称归并</h3>
      <div v-for="e in entries" :key="e.entryCode" class="gap-item" style="border-left-color:var(--cyan)">
        <div>
          <b class="mono">{{ e.entryCode }}</b> · {{ e.productName }}
          <span class="tag gray" style="margin:0 6px">{{ e.manufacturer }}</span>
          <span class="tag purple">目标 {{ e.targetProductCode }}</span>
        </div>
        <div v-for="v in e.versions" :key="v.versionId" style="margin:10px 0 4px 14px">
          <div class="small">
            V{{ v.versionNo }} · 生效 {{ fmt(v.effectiveFrom) }} ~ {{ fmt(v.effectiveTo) || '长期' }}
            · 来源 <span class="mono">{{ v.docCode }}</span> · <span class="mono">{{ v.sourceLocation }}</span>
          </div>
          <table style="margin-top:6px">
            <thead><tr><th>受控属性</th><th>归一化值</th><th>原始值</th><th>可换算</th><th>来源回链</th></tr></thead>
            <tbody>
              <tr v-for="a in v.attributes" :key="a.attrName + a.sourceDocCode">
                <td><span class="tag cyan">{{ attrLabel(a.attrName) }}</span></td>
                <td class="mono">{{ a.normalizedValue }}</td>
                <td class="small muted">{{ a.rawValue }}</td>
                <td>
                  <span :class="['tag', a.convertible ? 'green' : 'red']">{{ a.convertible ? '可换算' : '不可换算' }}</span>
                </td>
                <td class="small mono">{{ a.sourceDocCode }} · {{ a.sourceLocation }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, ATTR_LABELS } from '../api.js'

const scopes = ref([])
const documents = ref([])
const entries = ref([])
const currentDoc = ref(null)

const fmt = (t) => t ? String(t).replace('T', ' ').slice(0, 16) : ''
const attrLabel = (t) => ATTR_LABELS[t] || t
const docTypeLabel = (t) => ({
  SUPPLIER_CATALOG: '供应商目录',
  PHARMACOPOEIA_REFERENCE: '药典引用',
  SPEC_CHANGE_NOTICE: '规格变更通知'
}[t] || t)

function openDoc(d) {
  currentDoc.value = currentDoc.value && currentDoc.value.docCode === d.docCode ? null : d
}

onMounted(async () => {
  scopes.value = await api.get('/api/substitution/scopes')
  documents.value = await api.get('/api/substitution/documents')
  entries.value = await api.get('/api/substitution/entries')
})
</script>
