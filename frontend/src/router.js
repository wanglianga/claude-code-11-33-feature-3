import { createRouter, createWebHashHistory } from 'vue-router'
import IngestionView from './views/IngestionView.vue'
import RunsView from './views/RunsView.vue'
import RunDetailView from './views/RunDetailView.vue'
import BatchTraceView from './views/BatchTraceView.vue'
import ReleasesView from './views/ReleasesView.vue'
import ExternalView from './views/ExternalView.vue'
import CatalogIntakeView from './views/CatalogIntakeView.vue'
import StatementReviewView from './views/StatementReviewView.vue'
import AnalysisPublishView from './views/AnalysisPublishView.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/runs' },
    { path: '/ingestion', component: IngestionView, name: 'ingestion' },
    { path: '/runs', component: RunsView, name: 'runs' },
    { path: '/runs/:id', component: RunDetailView, name: 'run-detail', props: true },
    { path: '/runs/:id/batches/:batchCode', component: BatchTraceView, name: 'batch-trace', props: true },
    { path: '/releases', component: ReleasesView, name: 'releases' },
    { path: '/external', component: ExternalView, name: 'external' },
    { path: '/substitution/catalog', component: CatalogIntakeView, name: 'sub-catalog' },
    { path: '/substitution/statements', component: StatementReviewView, name: 'sub-statements' },
    { path: '/substitution/publish', component: AnalysisPublishView, name: 'sub-publish' }
  ]
})
