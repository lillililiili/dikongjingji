<script setup lang="ts">
/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）：全平台的客户、机构、成交、转化、来源、城市、
 * 平台资源和待处理。数字都是接口现算的，没有估算；待处理只给当前账号进得去的页面加链接。
 */
import { Refresh } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'

import { useSessionStore } from '@/core/auth/session'
import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'

import { SOURCE_LABELS } from '../lookup/lookup-api'
import {
  changeRatio,
  createDashboardApi,
  DASHBOARD_DAYS,
  formatBigMoney,
  formatCount,
  percent,
  type Dashboard,
  type DashboardApi,
  type DashboardDays,
} from './dashboard-api'
import ShareBars, { type ShareRow } from './ShareBars.vue'
import TrendChart from './TrendChart.vue'

const props = defineProps<{
  api?: DashboardApi
}>()

const api = props.api ?? createDashboardApi(http)
const router = useRouter()
const session = useSessionStore()
const days = ref<DashboardDays>(30)
const data = shallowRef<Dashboard | null>(null)
const loading = ref(false)
const error = ref('')
let controller: AbortController | null = null

async function load(): Promise<void> {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loading.value = true
  error.value = ''
  if (data.value && data.value.days !== days.value) data.value = null
  try {
    const result = await api.dashboard(days.value, current.signal)
    if (controller === current) data.value = result
  } catch (failure) {
    if (controller === current && !current.signal.aborted) error.value = errorMessage(failure)
  } finally {
    if (controller === current) loading.value = false
  }
}

onMounted(() => {
  void load()
})

onBeforeUnmount(() => controller?.abort())

interface Kpi {
  key: string
  label: string
  value: string
  change: number | null
  hint: string
}

const kpis = computed<Kpi[]>(() => {
  const d = data.value
  if (!d) return []
  return [
    {
      key: 'net',
      label: '净成交额',
      value: formatBigMoney(d.trade.netPaidMinor),
      change: changeRatio(d.trade.netPaidMinor, d.trade.previousNetPaidMinor),
      hint: `实付 ${formatBigMoney(d.trade.paidMinor)}，已退 ${formatBigMoney(d.trade.refundedMinor)}`,
    },
    {
      key: 'orders',
      label: '付款订单',
      value: formatCount(d.trade.paidOrders),
      change: changeRatio(d.trade.paidOrders, d.trade.previousPaidOrders),
      hint: '按付款时间统计',
    },
    {
      key: 'buyers',
      label: '付款客户',
      value: formatCount(d.trade.payingCustomers),
      change: changeRatio(d.trade.payingCustomers, d.trade.previousPayingCustomers),
      hint: '按客户账号去重',
    },
    {
      key: 'average',
      label: '客单价',
      value: formatBigMoney(d.trade.averageOrderMinor),
      change: null,
      hint: '净成交额 ÷ 付款订单',
    },
  ]
})

const people = computed<Kpi[]>(() => {
  const d = data.value
  if (!d) return []
  return [
    {
      key: 'customers',
      label: '注册客户',
      value: formatCount(d.customers.total),
      change: null,
      hint: `企业组织 ${formatCount(d.customers.enterprises)} 个`,
    },
    {
      key: 'new',
      label: '新增客户',
      value: formatCount(d.customers.newInPeriod),
      change: changeRatio(d.customers.newInPeriod, d.customers.newPreviousPeriod),
      hint: '按注册时间',
    },
    {
      key: 'active',
      label: '活跃客户',
      value: formatCount(d.customers.activeInPeriod),
      change: null,
      hint: '统计期内用过 App',
    },
    {
      key: 'providers',
      label: '入驻机构',
      value: formatCount(d.organizations.providers),
      change: null,
      hint: `资质已审核 ${formatCount(d.organizations.qualificationReviewed)}，本期新增 ${formatCount(d.organizations.newInPeriod)}`,
    },
    {
      key: 'merchants',
      label: '能收款的商家',
      value: formatCount(d.organizations.collectingMerchants),
      change: null,
      hint: `商城在售商家 ${formatCount(d.organizations.sellingMerchants)}`,
    },
  ]
})

const funnelRows = computed<ShareRow[]>(() => {
  const d = data.value
  if (!d) return []
  const rate = (part: number) => {
    const value = percent(part, d.funnel.submitted)
    return value === null ? undefined : `${value}%`
  }
  return [
    { key: 'submitted', label: '提交订单', value: d.funnel.submitted, valueText: formatCount(d.funnel.submitted) },
    {
      key: 'paid',
      label: '完成付款',
      value: d.funnel.paid,
      valueText: formatCount(d.funnel.paid),
      note: rate(d.funnel.paid),
    },
    {
      key: 'kept',
      label: '没有全额退款',
      value: d.funnel.kept,
      valueText: formatCount(d.funnel.kept),
      note: rate(d.funnel.kept),
    },
  ]
})

const sourceRows = computed<ShareRow[]>(() => {
  const d = data.value
  if (!d) return []
  return d.bySource.map((share) => ({
    key: share.source,
    label: SOURCE_LABELS[share.source] ?? share.source,
    value: share.netPaidMinor,
    valueText: formatBigMoney(share.netPaidMinor),
    note: `${formatCount(share.paidOrders)} 单`,
  }))
})

const cityRows = computed<ShareRow[]>(() => {
  const d = data.value
  if (!d) return []
  return d.byCity.map((share) => ({
    key: share.cityCode,
    label: share.cityName,
    value: share.netPaidMinor,
    valueText: formatBigMoney(share.netPaidMinor),
    note: `${formatCount(share.paidOrders)} 单`,
  }))
})

const supply = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    { key: 'cities', label: '开通城市', value: d.supply.openCities },
    { key: 'products', label: '在售商品', value: d.supply.products },
    { key: 'inspection', label: '巡检服务', value: d.supply.inspectionServices },
    { key: 'pro', label: '专业服务', value: d.supply.proServices },
    { key: 'activities', label: '活动', value: d.supply.activities },
    { key: 'venues', label: '场馆', value: d.supply.venues },
    { key: 'courses', label: '课程', value: d.supply.courses },
  ]
})

const engagement = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    { key: 'checkin', label: '到场核销人次', value: d.engagement.checkinPeople },
    { key: 'enroll', label: '活动报名', value: d.engagement.activityEnrollments },
    { key: 'venue', label: '到馆预约', value: d.engagement.venueBookings },
    { key: 'inspection', label: '巡检预约', value: d.engagement.inspectionReservations },
  ]
})

interface Todo {
  key: string
  label: string
  count: number
  to: string | { path: string, query: Record<string, string> }
}

function reachable(to: Todo['to']): boolean {
  const resolved = router.resolve(to)
  if (resolved.name === 'not-found') return false
  const roles = resolved.meta.roles as readonly string[] | undefined
  return session.can(roles)
}

const todos = computed(() => {
  const d = data.value
  if (!d) return []
  const items: Todo[] = [
    { key: 'qualification', label: '机构资质待审核', count: d.todo.qualificationReviews, to: '/organizations' },
    { key: 'merchant', label: '商家资料待审核', count: d.todo.merchantProfileReviews, to: '/merchant-profiles' },
    { key: 'provider', label: '服务商资料待审核', count: d.todo.providerProfileReviews, to: '/provider-profiles' },
    { key: 'feedback', label: '举报与反馈待处理', count: d.todo.pendingFeedback, to: '/feedback' },
    {
      key: 'conversation',
      label: '客服会话待回复',
      count: d.todo.conversationsAwaitingReply,
      to: '/support/conversations',
    },
    {
      key: 'refund',
      label: '退款异常的订单',
      count: d.todo.refundFailedOrders,
      to: { path: '/lookup/orders', query: { status: 'REFUND_FAILED' } },
    },
  ]
  return items.map((item) => ({ ...item, link: reachable(item.to) }))
})

const todoTotal = computed(() => todos.value.reduce((sum, item) => sum + item.count, 0))

function changeText(change: number | null): string {
  if (change === null) return ''
  if (change === 0) return '持平'
  return `${change > 0 ? '↑' : '↓'} ${Math.abs(change)}%`
}

function changeClass(change: number | null): string {
  if (change === null || change === 0) return 'kpi__change--flat'
  return change > 0 ? 'kpi__change--up' : 'kpi__change--down'
}
</script>

<template>
  <section class="dashboard" data-testid="ops-dashboard">
    <div class="dashboard__top-card">
      <header class="dashboard__head">
        <div>
          <h1 class="dashboard__title">经营概览</h1>
          <p v-if="data" class="dashboard__meta">
            全平台 · {{ data.fromDate }} 至 {{ data.toDate }}（北京时间）· 数据更新于 {{ formatDateTime(data.generatedAt) }}
          </p>
        </div>
        <div class="dashboard__actions">
          <el-radio-group v-model="days" size="default" data-testid="dashboard-days" @change="load()">
            <el-radio-button v-for="value in DASHBOARD_DAYS" :key="value" :value="value">近 {{ value }} 天</el-radio-button>
          </el-radio-group>
          <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
        </div>
      </header>

      <div v-if="data" class="hero" data-testid="dashboard-hero">
        <div class="hero__primary">
          <span class="hero__label">平台累计净成交</span>
          <strong class="hero__value">{{ formatBigMoney(data.trade.lifetimeNetPaidMinor) }}</strong>
          <span class="hero__note">累计实付减已退，不分期</span>
        </div>
        <div class="hero__metrics">
          <div class="hero__metric">
            <span>本期实付总额</span>
            <strong>{{ formatBigMoney(data.trade.paidMinor) }}</strong>
          </div>
          <div class="hero__metric">
            <span>本期累计退款</span>
            <strong>{{ formatBigMoney(data.trade.refundedMinor) }}</strong>
          </div>
          <div class="hero__metric">
            <span>开通城市</span>
            <strong>{{ formatCount(data.supply.openCities) }}</strong>
          </div>
        </div>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="!data" v-loading="true" class="dashboard__placeholder" />

    <template v-else>
      <div v-loading="loading" class="kpis kpis--trade">
        <article v-for="item in kpis" :key="item.key" class="kpi kpi--strong" :data-testid="`kpi-${item.key}`">
          <span class="kpi__label">{{ item.label }}</span>
          <strong class="kpi__value">{{ item.value }}</strong>
          <span class="kpi__foot">
            <span v-if="item.change !== null" class="kpi__change" :class="changeClass(item.change)">
              {{ changeText(item.change) }}<small> 较上期</small>
            </span>
            <span class="kpi__hint">{{ item.hint }}</span>
          </span>
        </article>
      </div>

      <div class="kpis kpis--people">
        <article v-for="item in people" :key="item.key" class="kpi" :data-testid="`kpi-${item.key}`">
          <span class="kpi__label">{{ item.label }}</span>
          <strong class="kpi__value">{{ item.value }}</strong>
          <span class="kpi__foot">
            <span v-if="item.change !== null" class="kpi__change" :class="changeClass(item.change)">
              {{ changeText(item.change) }}<small> 较上期</small>
            </span>
            <span class="kpi__hint">{{ item.hint }}</span>
          </span>
        </article>
      </div>

      <div class="grid grid--wide">
        <article class="panel">
          <h2 class="panel__title">每日趋势</h2>
          <TrendChart :daily="data.daily" />
        </article>
        <article class="panel">
          <h2 class="panel__title">下单转化</h2>
          <p class="panel__note">统计期内提交的订单走到哪一步</p>
          <ShareBars :rows="funnelRows" empty-text="统计期内没有订单" />
        </article>
      </div>

      <div class="grid">
        <article class="panel">
          <h2 class="panel__title">成交来源</h2>
          <p class="panel__note">按净成交额排序</p>
          <ShareBars :rows="sourceRows" ranked empty-text="统计期内没有付款订单" />
        </article>
        <article class="panel">
          <h2 class="panel__title">城市分布</h2>
          <p class="panel__note">净成交额前 8 的城市</p>
          <ShareBars :rows="cityRows" ranked empty-text="统计期内没有付款订单" />
        </article>
      </div>

      <div class="grid grid--three">
        <article class="panel">
          <h2 class="panel__title">平台资源</h2>
          <p class="panel__note">当前在架的数量</p>
          <dl class="counts">
            <div v-for="item in supply" :key="item.key" class="counts__cell">
              <dt>{{ item.label }}</dt>
              <dd>{{ formatCount(item.value) }}</dd>
            </div>
          </dl>
        </article>

        <article class="panel">
          <h2 class="panel__title">到场与预约</h2>
          <p class="panel__note">统计期内</p>
          <dl class="counts">
            <div v-for="item in engagement" :key="item.key" class="counts__cell">
              <dt>{{ item.label }}</dt>
              <dd>{{ formatCount(item.value) }}</dd>
            </div>
          </dl>
        </article>

        <article class="panel" data-testid="dashboard-todo">
          <h2 class="panel__title">
            待处理
            <span v-if="todoTotal > 0" class="panel__badge">{{ todoTotal }}</span>
          </h2>
          <p class="panel__note">当前还没处理的事项</p>
          <ul class="todos">
            <li v-for="item in todos" :key="item.key" :class="{ 'todos__item--done': item.count === 0 }">
              <router-link v-if="item.link && item.count > 0" :to="item.to">{{ item.label }}</router-link>
              <span v-else>{{ item.label }}</span>
              <strong>{{ formatCount(item.count) }}</strong>
            </li>
          </ul>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dashboard__top-card {
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
}

.dashboard__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.dashboard__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--admin-heading);
}

.dashboard__meta {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: #8c8c8c;
}

.dashboard__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.dashboard__actions :deep(.el-radio-group) {
  flex-wrap: nowrap;
}

.dashboard__placeholder {
  min-height: 280px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #fff;
}

.hero {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  background: #fafafa;
}

.hero__primary {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 12px;
}

.hero__label {
  font-size: 14px;
  font-weight: 500;
  color: #595959;
}

.hero__value {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--el-color-primary);
}

.hero__note {
  font-size: 12px;
  color: #8c8c8c;
}

.hero__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
}

.hero__metric {
  padding-left: 20px;
  border-left: 1px solid var(--admin-border);
}

.hero__metric span {
  display: block;
  font-size: 12px;
  color: #8c8c8c;
}

.hero__metric strong {
  display: block;
  margin-top: 3px;
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #1f2329;
}

.kpis {
  display: grid;
  gap: 14px;
}

.kpis--trade {
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
}

.kpis--people {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #fff;
}

.kpi__label {
  font-size: 13px;
  color: #595959;
}

.kpi__value {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #1f2329;
}

.kpi--strong .kpi__value {
  font-size: 26px;
}

.kpi__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  padding-top: 8px;
  border-top: 1px solid #f5f5f5;
  font-size: 12px;
}

.kpi__change {
  font-weight: 600;
}

.kpi__change small {
  font-weight: 400;
  color: #8c8c8c;
}

.kpi__change--up {
  color: #009a29;
}

.kpi__change--down {
  color: #d9363e;
}

.kpi__change--flat {
  color: #8c8c8c;
}

.kpi__hint {
  color: #8c8c8c;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 14px;
}

.grid--wide {
  grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr);
}

.grid--three {
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
}

@media (max-width: 1100px) {
  .grid--wide {
    grid-template-columns: minmax(0, 1fr);
  }
}

.panel {
  min-width: 0;
  padding: 16px 20px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #fff;
}

.panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1f2329;
}

.panel__badge {
  min-width: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: #fff1f0;
  border: 1px solid #ffa39e;
  color: #cf1322;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}

.panel__note {
  margin: 4px 0 14px;
  font-size: 12px;
  color: #8c8c8c;
}

.counts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
  margin: 0;
}

.counts__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #fafafa;
}

.counts dt {
  font-size: 12px;
  color: #8c8c8c;
}

.counts dd {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #1f2329;
}

.todos {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 13.5px;
}

.todos li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid #f5f5f5;
}

.todos li:last-child {
  border-bottom: 0;
}

.todos a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.todos a:hover {
  text-decoration: underline;
}

.todos strong {
  font-variant-numeric: tabular-nums;
}

.todos__item--done {
  color: #8c8c8c;
}
</style>
