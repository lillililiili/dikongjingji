import type {
  InspectionServiceOrder,
  InspectionServiceVisit,
} from '../../data/service-flow/inspection-plan-api'
import {
  findNextVisit,
  findPendingAcceptanceVisit,
  isVisitFinished,
} from '../../domain/service-flow/service-visit'
import {
  formatClock,
  formatDateRange,
  formatMonthDayTime,
} from '../shared/service-flow-format'
import {
  SERVICE_ORDER_STATUS_LABELS,
  formatVisitDate,
} from '../shared/service-visit-view'

type Assignment = InspectionServiceOrder['assignments'][number]

export type VisitRowTone = 'delivered' | 'accepted' | 'active' | 'scheduled' | 'pending'

export interface FulfillmentProgressView {
  statusLabel: string
  isFinished: boolean
  countText: string
  segments: Array<{ id: string; done: boolean }>
  scheduleText: string
}

export interface FulfillmentArrangementView {
  lead: { name: string; mobileText: string } | null
  rows: Array<{ label: string; value: string }>
}

export interface VisitRow {
  id: string
  visitNo: number
  title: string
  statusText: string
  tone: VisitRowTone
  badge: string
  events: Array<{ id: string; time: string; text: string }>
  deliveryLabel: string
  isExpanded: boolean
  isNext: boolean
}

const FIXED_EVENT_TEXT: Record<string, string> = {
  SERVICE_STARTED: '开始作业',
  SERVICE_COMPLETED: '完成作业',
}

const inVisitOrder = (visits: InspectionServiceVisit[]): InspectionServiceVisit[] =>
  [...visits].sort((left, right) => left.visitNo - right.visitNo)

export function describeFulfillmentProgress(
  order: Pick<InspectionServiceOrder, 'status' | 'visits'>,
): FulfillmentProgressView {
  const visits = inVisitOrder(order.visits)
  const finished = visits.filter(isVisitFinished).length
  const next = findNextVisit(visits)
  const period = formatDateRange(visits.map((visit) => visit.plannedStartAt ?? visit.plannedDate))
  return {
    statusLabel: SERVICE_ORDER_STATUS_LABELS[order.status],
    isFinished: order.status === 'ACCEPTED',
    countText: `${visits.length} 次中已完成 ${finished} 次`,
    segments: visits.map((visit) => ({ id: visit.id, done: isVisitFinished(visit) })),
    scheduleText: next
      ? `下次巡检 ${formatVisitDate(next)} · 周期 ${period}`
      : `周期 ${period}`,
  }
}

function countBy(values: string[]): Map<string, number> {
  const counts = new Map<string, number>()
  values.forEach((value) => counts.set(value, (counts.get(value) ?? 0) + 1))
  return counts
}

export function describeArrangement(assignments: Assignment[]): FulfillmentArrangementView {
  const team = assignments.filter((item) => item.type === 'TEAM_MEMBER')
  const devices = assignments.filter((item) => item.type === 'DEVICE')
  const lead = team.find((item) => item.roleLabel?.includes('负责人'))
    ?? team.find((item) => item.contactMobileMasked)
    ?? null
  const crew = countBy(team
    .filter((item) => item !== lead)
    .map((item) => item.roleLabel || '作业人员'))
  const deviceCounts = countBy(devices.map((item) => item.displayName))
  return {
    lead: lead
      ? { name: lead.displayName, mobileText: lead.contactMobileMasked ?? '' }
      : null,
    rows: [
      ...[...crew].map(([label, count]) => ({ label, value: `${count} 名` })),
      ...(deviceCounts.size
        ? [{
            label: '设备',
            value: [...deviceCounts]
              .map(([name, count]) => `${name} ×${count}`)
              .join('、'),
          }]
        : []),
    ],
  }
}

function deliveredWithin(visit: InspectionServiceVisit, reportDueHours: number): boolean {
  const completed = visit.progress.find((event) => event.eventCode === 'SERVICE_COMPLETED')
  if (!completed || !visit.deliveredAt) return false
  const elapsedHours = (Date.parse(visit.deliveredAt) - Date.parse(completed.occurredAt)) / 3_600_000
  return elapsedHours <= reportDueHours
}

function toVisitEvents(
  visit: InspectionServiceVisit,
  reportDueHours: number | null,
): VisitRow['events'] {
  const events = visit.progress.map((event) => ({
    id: event.id,
    time: formatMonthDayTime(event.occurredAt),
    text: FIXED_EVENT_TEXT[event.eventCode] ?? event.message,
  }))
  if (!visit.deliveredAt) return events
  const promise = reportDueHours && deliveredWithin(visit, reportDueHours)
    ? `（${reportDueHours} 小时内）`
    : ''
  return [...events, {
    id: 'delivered',
    time: formatMonthDayTime(visit.deliveredAt),
    text: `报告已交付${promise}`,
  }]
}

function describeVisitStatus(visit: InspectionServiceVisit): { statusText: string; tone: VisitRowTone } {
  switch (visit.status) {
    case 'ACCEPTED':
      return {
        statusText: visit.acceptedAt ? `已验收 · ${formatMonthDayTime(visit.acceptedAt)}` : '已验收',
        tone: 'accepted',
      }
    case 'DELIVERED':
      return { statusText: '已完成 · 报告已交付', tone: 'delivered' }
    case 'IN_PROGRESS':
      return {
        statusText: visit.progress.some((event) => event.eventCode === 'SERVICE_COMPLETED')
          ? '现场已完成 · 报告整理中'
          : '作业中',
        tone: 'active',
      }
    case 'SCHEDULED':
      return {
        statusText: visit.plannedStartAt ? `已排期 · ${formatClock(visit.plannedStartAt)}` : '已排期',
        tone: 'scheduled',
      }
    case 'PENDING_SCHEDULE':
      return { statusText: '待排期', tone: 'pending' }
  }
}

export function toVisitRows(
  visits: InspectionServiceVisit[],
  reportDueHours: number | null,
): VisitRow[] {
  const ordered = inVisitOrder(visits)
  const next = findNextVisit(ordered)
  return ordered.map((visit) => ({
    id: visit.id,
    visitNo: visit.visitNo,
    title: `第 ${visit.visitNo} 次 · ${formatVisitDate(visit)}`,
    ...describeVisitStatus(visit),
    badge: visit.status === 'DELIVERED' ? '待验收' : '',
    events: toVisitEvents(visit, reportDueHours),
    deliveryLabel: isVisitFinished(visit) ? `查看第 ${visit.visitNo} 次交付` : '',
    isExpanded: visit.status === 'DELIVERED' || visit.status === 'IN_PROGRESS',
    isNext: visit === next,
  }))
}

export function describePendingAcceptance(
  visits: InspectionServiceVisit[],
): { visitId: string; title: string; text: string } | null {
  const visit = findPendingAcceptanceVisit(visits)
  if (!visit) return null
  return {
    visitId: visit.id,
    title: `第 ${visit.visitNo} 次报告待验收`,
    text: visit.deliveredAt
      ? `${formatMonthDayTime(visit.deliveredAt)} 已交付，请核对后验收`
      : '报告已交付，请核对后验收',
  }
}
