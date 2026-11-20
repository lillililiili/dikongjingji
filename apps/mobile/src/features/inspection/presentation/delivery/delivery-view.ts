import type {
  InspectionDelivery,
  InspectionDeliveryFileType,
  InspectionServiceOrder,
  InspectionServiceVisit,
} from '../../data/service-flow/inspection-plan-api'
import { formatPlanPrice } from '../../domain/service-flow/plan'
import {
  formatClock,
  formatDistance,
  formatFileSize,
  formatMonthDayTime,
} from '../shared/service-flow-format'
import { formatVisitDate } from '../shared/service-visit-view'

type DeliveryOrder = Pick<
  InspectionServiceOrder,
  'provider' | 'customerParty' | 'paymentNodes' | 'serviceSnapshot'
>
type DeliveryVisit = Pick<InspectionServiceVisit, 'visitNo' | 'plannedDate' | 'plannedStartAt'>

export interface DeliveryResultTile {
  key: string
  label: string
  valueText: string
  isIssue: boolean
}

export interface DeliveryIssueRow {
  key: string
  name: string
  countText: string
}

export interface DeliveryFileRow {
  fileId: string
  type: InspectionDeliveryFileType
  /** The one-line row title of board Delivery: what the file is and its format, “巡检报告（PDF）”. */
  title: string
  name: string
  detailText: string
  linkText: string
  iconUrl: string
  canPreview: boolean
}

export interface AcceptanceSheetView {
  title: string
  items: Array<{ key: string; name: string }>
  providerName: string
  acceptorText: string
  feeText: string
}

const FILE_PRESENTATION: Record<InspectionDeliveryFileType, {
  iconUrl: string
  canPreview: boolean
  itemUnit: string
}> = {
  INSPECTION_REPORT: {
    iconUrl: '/static/images/inspection/delivery-file-report.svg',
    canPreview: true,
    itemUnit: '页',
  },
  DEFECT_LIST: {
    iconUrl: '/static/images/inspection/delivery-file-list.svg',
    canPreview: true,
    itemUnit: '项',
  },
  IMAGE_ARCHIVE: {
    iconUrl: '/static/images/inspection/delivery-file-images.svg',
    canPreview: false,
    itemUnit: '张',
  },
  POINT_KML: {
    iconUrl: '/static/images/inspection/delivery-file-points.svg',
    canPreview: false,
    itemUnit: '个',
  },
}

type DeliveryFile = InspectionDelivery['files'][number]

// The format in brackets: PDF, 表格, KML or the extension of the file name.
function fileFormat(file: DeliveryFile): string {
  const type = file.contentType.toLowerCase()
  if (type.includes('pdf')) return 'PDF'
  if (type.includes('sheet') || type.includes('excel') || type.includes('csv')) return '表格'
  if (type.includes('kml')) return 'KML'
  const extension = /\.([a-z0-9]+)$/i.exec(file.displayName)?.[1]
  return extension ? extension.toUpperCase() : ''
}

function fileRowTitle(file: DeliveryFile): string {
  const format = fileFormat(file)
  const withFormat = (label: string, detail: string): string => (detail ? `${label}（${detail}）` : label)
  switch (file.type) {
    case 'INSPECTION_REPORT':
      return withFormat('巡检报告', format)
    case 'DEFECT_LIST':
      return withFormat('缺陷清单', format)
    case 'POINT_KML':
      return withFormat('点位标注', ['KML', file.pointCount == null ? '' : `${file.pointCount} 个点位`].filter(Boolean).join(' · '))
    case 'IMAGE_ARCHIVE':
      return withFormat('高清巡检影像', file.itemCount == null ? format : `${file.itemCount} 张`)
    default:
      return file.displayName
  }
}

export function describeDeliveryHeader(
  order: DeliveryOrder,
  visit: DeliveryVisit,
  delivery: InspectionDelivery,
): {
  contextText: string
  title: string
  status: { label: string; isAccepted: boolean }
  deliveredText: string
} {
  const isAccepted = delivery.status === 'ACCEPTED'
  return {
    contextText: [order.serviceSnapshot.serviceTitle, order.provider?.name]
      .filter(Boolean)
      .join(' · '),
    title: `第 ${visit.visitNo} 次巡检 · ${formatVisitDate(visit)}`,
    status: { label: isAccepted ? '已验收' : '待验收', isAccepted },
    deliveredText: `报告 ${formatMonthDayTime(delivery.publishedAt)} 已交付`,
  }
}

export function describeAcceptanceBanner(
  visit: DeliveryVisit,
  delivery: InspectionDelivery,
): { title: string; text: string } | null {
  if (delivery.status !== 'ACCEPTED') return null
  return {
    title: `第 ${visit.visitNo} 次已验收`,
    text: [
      formatMonthDayTime(delivery.acceptedAt),
      delivery.acceptedByName,
      '验收记录已留存',
    ].filter(Boolean).join(' · '),
  }
}

function formatMetricValue(value: number): string {
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 })
}

export function toResultTiles(delivery: InspectionDelivery): DeliveryResultTile[] {
  return [
    ...(delivery.towerCount == null
      ? []
      : [{
          key: 'TOWER_COUNT',
          label: '巡检杆塔',
          valueText: `${delivery.towerCount} 基`,
          isIssue: false,
        }]),
    ...(delivery.inspectedLengthMeters == null
      ? []
      : [{
          key: 'INSPECTED_LENGTH',
          label: '巡检长度',
          valueText: formatDistance(delivery.inspectedLengthMeters),
          isIssue: false,
        }]),
    ...delivery.metrics.map((metric) => ({
      key: metric.code,
      label: metric.label,
      valueText: `${formatMetricValue(metric.value)} ${metric.unit}`,
      isIssue: false,
    })),
    {
      key: 'ISSUES',
      label: '发现隐患',
      valueText: `${delivery.issueCount} 项`,
      isIssue: true,
    },
  ]
}

export function toIssueRows(delivery: InspectionDelivery): DeliveryIssueRow[] {
  return delivery.issueSummary.map((group) => ({
    key: group.groupCode,
    name: group.groupName,
    countText: `${group.count} 项`,
  }))
}

export function toFileRows(
  delivery: InspectionDelivery,
  linkExpiries: Record<string, string>,
): DeliveryFileRow[] {
  return delivery.files.map((file) => {
    const presentation = FILE_PRESENTATION[file.type]
    const expiresAt = linkExpiries[file.fileId]
    return {
      fileId: file.fileId,
      type: file.type,
      title: fileRowTitle(file),
      name: file.itemCount == null
        ? file.displayName
        : `${file.displayName}（${file.itemCount} ${presentation.itemUnit}）`,
      detailText: [
        file.pointCount == null ? '' : `${file.pointCount} 个点位`,
        formatFileSize(file.sizeBytes),
      ].filter(Boolean).join(' · '),
      linkText: expiresAt ? `下载链接已生成，${formatClock(expiresAt)} 前有效` : '',
      iconUrl: presentation.iconUrl,
      canPreview: presentation.canPreview,
    }
  })
}

export function describeAcceptanceSheet(
  order: DeliveryOrder,
  visit: DeliveryVisit,
  delivery: InspectionDelivery,
): AcceptanceSheetView {
  const party = order.customerParty
  const fee = order.paymentNodes?.find((node) =>
    node.nodeType === 'PER_VISIT' && node.visitNo === visit.visitNo)
  return {
    title: `确认第 ${visit.visitNo} 次验收`,
    items: toFileRows(delivery, {}).map((row) => ({ key: row.fileId, name: row.name })),
    providerName: order.provider?.name ?? '',
    acceptorText: party
      ? [party.operatorName, party.organizationName && `${party.organizationName} 经办人`]
          .filter(Boolean)
          .join(' · ')
      : '',
    feeText: fee ? `${formatPlanPrice(fee.amountMinor)} · 验收后支付` : '',
  }
}
