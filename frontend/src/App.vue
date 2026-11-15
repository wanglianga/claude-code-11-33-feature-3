<template>
  <div class="layout">
    <aside class="sidebar">
      <h1>药品批次谱系<br/>数据质量与召回影响服务</h1>
      <div class="sub">来源断言 / 发布范围分别版本化 · 截止时间可复现</div>
      <nav>
        <router-link to="/runs">范围核验台</router-link>
        <router-link to="/ingestion">接入登记台</router-link>
        <router-link to="/releases">发布版本管理</router-link>
        <router-link to="/external">外部接口（脱敏）</router-link>
        <div style="height:1px;background:var(--line);margin:12px 8px"></div>
        <router-link to="/substitution/catalog">替代目录受理/语义字典</router-link>
        <router-link to="/substitution/statements">适用性声明/反例审核</router-link>
        <router-link to="/substitution/publish">替代目录分析发布</router-link>
      </nav>
      <div class="small" style="margin-top:26px;line-height:1.7">
        技术栈：Vue 3 + Spring Boot + PostgreSQL<br/>
        本系统不隔离、销毁、退运或调拨药品，<br/>不下达机构通知，不生成付款/审批决定；<br/>替代目录只做语义对齐与适用性声明，<br/>不推荐用药、不计算库存/需求/调拨。
      </div>
    </aside>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
