import { randomUUID } from 'node:crypto'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { randomPhone, signUpByApi } from './support/account-fixtures'
import { API, psql } from './support/trade-fixtures'

async function createInbox(request: APIRequestContext) {
  const buyer = await signUpByApi(request, randomPhone())
  const recipient: string = JSON.parse(Buffer.from(buyer.token.split('.')[1], 'base64url').toString()).sub
  expect(recipient).toMatch(/^[a-f0-9-]{36}$/)
  // Place ordinary messages on both sides of the seed announcement in the inbox sort order.
  for (const date of ["now()", "'2020-01-01'"]) {
    const id = randomUUID()
    psql(`INSERT INTO notification_message(recipient_id, category, event_code, title, body, target_type, target_id, source_key, occurred_at)
      VALUES ('${recipient}','SERVICE_PROGRESS','TEST_INBOX','巡检进度回归','进度消息','INSPECTION_PLAN','${id}','inbox-e2e:${id}',${date});`)
  }
  return buyer
}

test('已投递的系统公告按构建限制列表分页、未读数及全部已读', async ({ request }) => {
  const { headers } = await createInbox(request)
  const inbox = API + '/api/v1/notifications'
  const get = async (path: string) => {
    const response = await request.get(inbox + path, { headers })
    expect(response.status()).toBe(200)
    return response.json()
  }
  const mark = async (query: string) => {
    const response = await request.post(inbox + '/read-all' + query, { headers })
    expect(response.status()).toBe(200)
    return response.json()
  }
  const all = await get('?systemNotices=true&cityCode=370500')
  const announcements = all.items.filter((item: { category: string }) => item.category === 'SYSTEM_NOTICE').length
  expect(announcements).toBeGreaterThan(0)
  expect(all.total).toBe(2 + announcements)
  for (const [page, more] of [[1, true], [2, false]] as const) {
    const result = await get(`?page=${page}&size=1`)
    expect(result).toMatchObject({ total: 2, unreadCount: 2, hasMore: more, items: [{ category: 'SERVICE_PROGRESS' }] })
  }
  expect(await get('/unread-count')).toEqual({ count: 2 })
  expect(await mark('?systemNotices=false')).toEqual({ updated: 2 })
  expect(await mark('?systemNotices=false')).toEqual({ updated: 0 })
  expect(await get('/unread-count?systemNotices=true')).toEqual({ count: announcements })
  expect(await mark('')).toEqual({ updated: announcements })
  expect(await get('/unread-count?systemNotices=true')).toEqual({ count: 0 })
})

test('同一账号在预览看公告、公开版只读进度，再回预览仍有未读公告', async ({ request, browser, baseURL }) => {
  const closed = process.env.E2E_NOTICE_CLOSED_URL
  test.skip(!closed, '需要同一 API 的两种实际构建')
  expect(['127.0.0.1', 'localhost']).toContain(new URL(closed!).hostname)
  const { token } = await createInbox(request)
  const context = await browser.newContext()
  try {
    await context.addInitScript((value) => localStorage.setItem('inspection.accessToken', value), token)
    const page = await context.newPage()
    const previewUrl = new URL('/#/pages/message/index', baseURL).href
    await page.goto(previewUrl)
    const notice = page.locator('.message-center__item[data-category="SYSTEM_NOTICE"]').first()
    await expect(notice).toHaveAttribute('data-read', 'false')
    await page.goto(new URL('/#/pages/message/index', closed).href)
    await expect(page.locator('.message-center__item[data-category="SERVICE_PROGRESS"]')).toHaveCount(2)
    await expect(notice).toHaveCount(0)
    const response = page.waitForResponse((r) => r.url().endsWith('/notifications/read-all?systemNotices=false'))
    await page.getByTestId('message-read-all').click()
    expect((await response).ok()).toBe(true)
    await page.goto(previewUrl)
    await expect(notice).toHaveAttribute('data-read', 'false')
    await expect(page.locator('.message-center__item[data-category="SERVICE_PROGRESS"][data-read="true"]')).toHaveCount(2)
  } finally {
    await context.close()
  }
})
