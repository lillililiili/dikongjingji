import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'

import { recordingClient } from '@/core/http/recording-client'

import { canRun, createImportApi, filesProblem, type OpsImportBatch } from './import-api'
import ImportBatchResult from './ImportBatchResult.vue'

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

const BATCH = '5a000000-0000-4000-8000-000000000001'

function batch(overrides: Partial<OpsImportBatch> = {}): OpsImportBatch {
  return {
    batchId: BATCH,
    kind: 'MALL',
    status: 'REJECTED',
    fileSha256: 'f'.repeat(64),
    files: [{ fileName: '03-mall-products.csv', rowCount: 2 }],
    rowCount: 2,
    issueCount: 1,
    issues: [{ fileName: '03-mall-products.csv', line: 3, column: 'sale_mode', message: '只能填 BUY、RENT、INQUIRY' }],
    reviewItems: [],
    changes: [],
    createdAccounts: [],
    duplicateOfBatchId: null,
    checkedByName: '平台运营（示例）',
    checkedAt: '2026-11-23T02:00:00Z',
    importedByName: null,
    importedAt: null,
    retainedUntil: '2027-05-22T02:00:00Z',
    ...overrides,
  }
}

describe('批量导入接口', () => {
  it('预检把每个文件作为一个 files 字段；导入、记录、批次的路径', async () => {
    const { http, requests } = recordingClient(batch())
    const api = createImportApi(http)
    const files = [
      new File(['a'], '03-mall-products.csv', { type: 'text/csv' }),
      new File(['b'], '03d-mall-skus.csv', { type: 'text/csv' }),
    ]
    await api.check('MALL', files)
    await api.run(BATCH)
    await api.batches({ kind: 'ORGANIZATIONS' }, 2, 10)
    await api.batches({ kind: '' }, 1, 10)
    await api.batch(BATCH)
    await api.kinds()

    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual([
      'POST /api/v1/ops/imports/MALL/checks',
      `POST /api/v1/ops/imports/batches/${BATCH}/run`,
      'GET /api/v1/ops/imports/batches?kind=ORGANIZATIONS&page=2&size=10',
      'GET /api/v1/ops/imports/batches?page=1&size=10',
      `GET /api/v1/ops/imports/batches/${BATCH}`,
      'GET /api/v1/ops/imports/kinds',
    ])
    const form = requests[0]?.body as FormData
    expect((form.getAll('files') as File[]).map((file) => file.name)).toEqual(['03-mall-products.csv', '03d-mall-skus.csv'])
    expect(requests[1]?.body).toBeUndefined()
  })

  it('上传前挡住：没选、不是 CSV、空文件、太大、重名、太多', () => {
    const csv = (name: string, size = 10) => ({ name, size })
    expect(filesProblem([])).toBe('请先选择要预检的 CSV 文件')
    expect(filesProblem([csv('01-organizations.csv')])).toBeNull()
    expect(filesProblem([csv('a.xlsx')])).toBe('“a.xlsx”不是 CSV 文件')
    expect(filesProblem([csv('a.csv', 0)])).toBe('“a.csv”是空文件')
    expect(filesProblem([csv('a.csv', 2 * 1024 * 1024 + 1)])).toContain('超过 2 MB')
    expect(filesProblem([csv('a.csv'), csv('a.csv')])).toBe('“a.csv”选了两次')
    expect(filesProblem(Array.from({ length: 13 }, (_, i) => csv(`${i}.csv`)))).toBe('一次最多 12 个文件')
  })

  it('只有预检通过、这一类能写的批次能导入', () => {
    expect(canRun(batch({ status: 'CHECKED' }), true)).toBe(true)
    expect(canRun(batch({ status: 'CHECKED' }), false)).toBe(false)
    expect(canRun(batch({ status: 'REJECTED' }), true)).toBe(false)
    expect(canRun(batch({ status: 'IMPORTED' }), true)).toBe(false)
  })
})

describe('预检结果', () => {
  it('有问题时列出问题表，没有导入按钮', async () => {
    const wrapper = mount(ImportBatchResult, {
      props: { batch: batch(), runnable: false, running: false, checkOnly: false },
      global: { plugins: [ElementPlus], stubs: { RouterLink: true } },
      attachTo: document.body,
    })
    await settle()
    expect(wrapper.find('[data-testid="import-status"]').text()).toBe('有问题')
    const issues = wrapper.find('[data-testid="import-issues"]')
    expect(issues.exists()).toBe(true)
    expect(issues.text()).toContain('sale_mode')
    expect(issues.text()).toContain('只能填 BUY、RENT、INQUIRY')
    expect(wrapper.find('[data-testid="import-run"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('预检通过时能导入；和以前导入过的相同时提示不会重复写', async () => {
    const wrapper = mount(ImportBatchResult, {
      props: {
        batch: batch({ status: 'IMPORTED', issueCount: 0, issues: [], duplicateOfBatchId: 'other-batch',
          changes: [{ refKind: 'PRODUCT', reference: 'KIT', targetId: 'x', change: 'UPDATED' }] }),
        runnable: false,
        running: false,
        checkOnly: false,
      },
      global: { plugins: [ElementPlus], stubs: { RouterLink: true } },
      attachTo: document.body,
    })
    await settle()
    expect(wrapper.find('[data-testid="import-duplicate"]').text()).toContain('不会重复写入')
    expect(wrapper.find('[data-testid="import-changes"]').text()).toContain('更新')

    await wrapper.setProps({ batch: batch({ status: 'CHECKED', issueCount: 0, issues: [] }), runnable: true })
    await wrapper.find('[data-testid="import-run"]').trigger('click')
    expect(wrapper.emitted('run')).toHaveLength(1)
    wrapper.unmount()
  })
})
