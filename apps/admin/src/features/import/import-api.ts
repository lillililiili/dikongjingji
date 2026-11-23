import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-import.yaml 1.0.0（首批资源批量导入，模板 infra/import-templates/）。

export type OpsImportKind =
  | 'ORGANIZATIONS' | 'MERCHANT_ACCOUNTS' | 'MALL' | 'ACTIVITIES' | 'VENUES' | 'SERVICES' | 'CONTENT'
export type OpsImportStatus = 'CHECKED' | 'REJECTED' | 'IMPORTED'
export type OpsImportRefKind = 'ORGANIZATION' | 'OPERATOR' | 'MERCHANT_ACCOUNT' | 'PRODUCT' | 'SKU' | 'OFFER'
export type OpsImportChangeType = 'CREATED' | 'UPDATED'

export const KIND_LABELS: Record<OpsImportKind, string> = {
  ORGANIZATIONS: '经营主体与操作员',
  MERCHANT_ACCOUNTS: '商家收款账户',
  MALL: '商品与供给',
  ACTIVITIES: '活动与场次',
  VENUES: '场馆与项目',
  SERVICES: '巡检和专业服务',
  CONTENT: '发现内容',
}

export const KIND_ORDER: readonly OpsImportKind[] = [
  'ORGANIZATIONS', 'MERCHANT_ACCOUNTS', 'MALL', 'ACTIVITIES', 'VENUES', 'SERVICES', 'CONTENT',
]

export const STATUS_VIEW: Record<OpsImportStatus, { label: string, tag: 'success' | 'danger' | 'info' }> = {
  CHECKED: { label: '预检通过', tag: 'success' },
  REJECTED: { label: '有问题', tag: 'danger' },
  IMPORTED: { label: '已导入', tag: 'info' },
}

export const REF_KIND_LABELS: Record<OpsImportRefKind, string> = {
  ORGANIZATION: '经营主体',
  OPERATOR: '操作员账号',
  MERCHANT_ACCOUNT: '收款账户',
  PRODUCT: '商品',
  SKU: '规格',
  OFFER: '供给',
}

export const CHANGE_LABELS: Record<OpsImportChangeType, string> = {
  CREATED: '新建',
  UPDATED: '更新',
}

/** 契约的上限：一次最多 12 个文件，每个 ≤2 MB，合计 ≤10 MB；问题最多列 500 条。 */
export const IMPORT_LIMITS = {
  files: 12,
  fileBytes: 2 * 1024 * 1024,
  totalBytes: 10 * 1024 * 1024,
  listedIssues: 500,
} as const

export interface OpsImportFileSpec {
  fileName: string
  title: string
  required: boolean
  columns: string[]
}

export interface OpsImportKindSpec {
  kind: OpsImportKind
  title: string
  writable: boolean
  files: OpsImportFileSpec[]
}

export interface OpsImportFile {
  fileName: string
  rowCount: number
}

export interface OpsImportIssue {
  fileName: string
  line: number
  column: string | null
  message: string
}

export interface OpsImportReviewItem {
  fileName: string
  line: number
  reference: string
  label: string
  value: string
}

export interface OpsImportChange {
  refKind: OpsImportRefKind
  reference: string
  targetId: string
  change: OpsImportChangeType
}

export interface OpsImportCreatedAccount {
  organizationCode: string
  loginName: string
  initialPassword: string
}

export interface OpsImportBatch {
  batchId: string
  kind: OpsImportKind
  status: OpsImportStatus
  fileSha256: string
  files: OpsImportFile[]
  rowCount: number
  issueCount: number
  issues: OpsImportIssue[]
  reviewItems: OpsImportReviewItem[]
  changes: OpsImportChange[]
  createdAccounts: OpsImportCreatedAccount[]
  duplicateOfBatchId: string | null
  checkedByName: string
  checkedAt: string
  importedByName: string | null
  importedAt: string | null
  retainedUntil: string
}

export interface OpsImportBatchSummary {
  batchId: string
  kind: OpsImportKind
  status: OpsImportStatus
  fileNames: string[]
  rowCount: number
  issueCount: number
  changeCount: number
  checkedByName: string
  checkedAt: string
  importedByName: string | null
  importedAt: string | null
}

export interface BatchFilters {
  kind: OpsImportKind | ''
}

/** 选好的文件在上传前先挡一次：不是 .csv、太大、太多。返回错误说明，没问题返回 null。 */
export function filesProblem(files: ReadonlyArray<{ name: string, size: number }>): string | null {
  if (files.length === 0) return '请先选择要预检的 CSV 文件'
  if (files.length > IMPORT_LIMITS.files) return `一次最多 ${IMPORT_LIMITS.files} 个文件`
  const notCsv = files.find((file) => !file.name.toLowerCase().endsWith('.csv'))
  if (notCsv) return `“${notCsv.name}”不是 CSV 文件`
  const empty = files.find((file) => file.size <= 0)
  if (empty) return `“${empty.name}”是空文件`
  const big = files.find((file) => file.size > IMPORT_LIMITS.fileBytes)
  if (big) return `“${big.name}”超过 2 MB，请拆成几个文件（文件名后面加序号）`
  const names = new Set<string>()
  for (const file of files) {
    if (names.has(file.name)) return `“${file.name}”选了两次`
    names.add(file.name)
  }
  if (files.reduce((sum, file) => sum + file.size, 0) > IMPORT_LIMITS.totalBytes) return '这次选的文件合计超过 10 MB'
  return null
}

/** 批次能不能导入：预检通过、这一类能写、还没导入。 */
export function canRun(batch: OpsImportBatch, writable: boolean): boolean {
  return batch.status === 'CHECKED' && writable
}

const IMPORTS = '/api/v1/ops/imports'

export function createImportApi(http: ApiClient) {
  const batch = (batchId: string) => `${IMPORTS}/batches/${encodeURIComponent(batchId)}`
  return {
    kinds(): Promise<OpsImportKindSpec[]> {
      return http.get<OpsImportKindSpec[]>(`${IMPORTS}/kinds`)
    },
    /** 预检：每个文件一个 files 字段，文件名就是模板名。 */
    check(kind: OpsImportKind, files: readonly File[]): Promise<OpsImportBatch> {
      const form = new FormData()
      for (const file of files) form.append('files', file, file.name)
      return http.request<OpsImportBatch>('POST', `${IMPORTS}/${encodeURIComponent(kind)}/checks`, { form })
    },
    run(batchId: string): Promise<OpsImportBatch> {
      return http.post<OpsImportBatch>(`${batch(batchId)}/run`)
    },
    batches(filters: BatchFilters, page: number, size: number): Promise<Page<OpsImportBatchSummary>> {
      return http.get<Page<OpsImportBatchSummary>>(`${IMPORTS}/batches`, { kind: filters.kind, page, size })
    },
    batch(batchId: string): Promise<OpsImportBatch> {
      return http.get<OpsImportBatch>(batch(batchId))
    },
  }
}

export type ImportApi = ReturnType<typeof createImportApi>
