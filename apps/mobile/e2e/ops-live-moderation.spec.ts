import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'

import { openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import { adminSignIn } from './support/admin-session'
import { testAdmin, testApi, testMobile } from './support/test-stack'
import { apiLogin, CITY, MERCHANT } from './support/trade-fixtures'

test.use({ viewport: { width: 1366, height: 900 }, trace: 'off' })

async function liveChannel(page: Page) {
  await page.goto('/?run=' + randomUUID() + '#/pages/discover/index')
  await page.getByTestId('discover-channel-live').click()
  await expect(page.getByTestId('live-channel')).toHaveAttribute('data-state', 'ready')
}

test('App 举报弹幕，客服核查并隐藏，运营强制结束直播、发布和撤下回看', async ({ page, browser, request }) => {
  test.skip(process.env.E2E_OPS_CONTENT_TEST !== '1' || !process.env.DEV_OPERATOR_PASSWORD, '需要隔离运营测试栈')
  test.setTimeout(150_000)
  const host = await apiLogin(request, MERCHANT)
  expect(host).not.toBeNull()
  const title = '直播联调' + randomUUID().slice(0, 8)
  const room = await request.post(testApi + '/api/v1/live-host/rooms', {
    headers: { ...host!.headers, 'Idempotency-Key': randomUUID() },
    data: { title, cityCode: CITY, scheduledAt: new Date(Date.now() + 600_000).toISOString(),
      speakerName: '联调讲师', topics: ['体验活动'] },
  })
  expect(room.status()).toBe(201)
  const roomId = (await room.json()).room.roomId
  const stream = testApi + '/api/v1/dev/live/rooms/' + roomId + '/stream-events'
  const mobile = await browser.newContext({ baseURL: testMobile, viewport: { width: 390, height: 844 } })
  const operator = await browser.newContext({ viewport: { width: 1366, height: 900 } })
  try {
    expect((await request.post(stream, { headers: host!.headers, data: { type: 'STARTED' } })).status()).toBe(200)
    const viewer = await mobile.newPage()
    const author = await signUpByApi(request, randomPhone())
    const reporter = await signUpByApi(request, randomPhone())
    const route = '/pages/live/room?roomId=' + roomId + '&cityCode=' + CITY
    await openSignedIn(viewer, author.token, route)
    await expect(viewer.getByTestId('live-room')).toHaveAttribute('data-status', 'LIVE')
    const comment = '什么时候可以体验 ' + randomUUID().slice(0, 8)
    await viewer.getByTestId('live-room-input').locator('input').fill(comment)
    await expect(viewer.getByTestId('live-room-send')).not.toHaveAttribute('disabled', 'true')
    await viewer.getByTestId('live-room-send').click()
    await expect(viewer.getByTestId('live-room-chat')).toContainText('我：' + comment)

    await openSignedIn(viewer, reporter.token, route)
    const line = viewer.getByTestId('live-room-chat').locator('.room__line').filter({ hasText: comment })
    await line.click()
    await viewer.getByText('举报这条弹幕', { exact: true }).click()
    await expect(viewer.locator('uni-toast')).toContainText('已收到举报')
    await adminSignIn(page, 'dev-support', '/feedback')
    const feedback = page.locator('.el-table__row').filter({ hasText: comment })
    await expect(feedback).toContainText('被举报原文：' + comment)
    await feedback.getByRole('button', { name: '隐藏弹幕' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '隐藏弹幕' }).click()
    await expect(feedback).toContainText('已隐藏')
    await expect(line).toHaveCount(0, { timeout: 15_000 })
    await page.reload()
    await expect(feedback).toContainText('已隐藏')
    await feedback.getByRole('button', { name: '处理' }).click()
    const handled = page.getByRole('dialog', { name: '标为已处理' })
    await expect(handled.locator('textarea')).toHaveValue('已隐藏弹幕。')
    await handled.getByRole('button', { name: '标为已处理' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '标为已处理' }).click()
    await expect(feedback).toHaveCount(0)

    const ops = await operator.newPage()
    await ops.goto(testAdmin + '/live')
    await ops.locator('input[autocomplete=username]').fill('dev-operator')
    await ops.locator('input[autocomplete=current-password]').fill(process.env.DEV_OPERATOR_PASSWORD!)
    await ops.locator('form').getByRole('button', { name: '登录' }).click()
    await ops.getByPlaceholder('标题或主办机构').fill(title)
    await ops.getByRole('button', { name: '查询' }).click()
    const row = ops.locator('.el-table__row').filter({ hasText: title })
    await expect(row.getByRole('button', { name: '回看' })).toBeDisabled()
    await row.getByRole('button', { name: '强制结束' }).click()
    const ending = ops.getByRole('dialog', { name: '强制结束直播间' })
    await ending.getByRole('button', { name: '强制结束' }).click()
    await expect(ending).toContainText('请写强制结束的原因')
    const reason = '直播联调结束，主办方要求关闭'
    await ending.locator('textarea').fill(reason)
    await ending.getByRole('button', { name: '强制结束' }).click()
    await ops.locator('.el-message-box').getByRole('button', { name: '强制结束' }).click()
    await expect(row).toContainText('平台强制结束')
    await expect(row.getByRole('button', { name: '强制结束' })).toBeDisabled()
    await expect(viewer.getByTestId('live-room-over')).toContainText('直播已结束', { timeout: 30_000 })
    await expect(viewer.getByTestId('live-room-video')).toHaveCount(0)
    await expect(viewer.getByTestId('live-room-send')).toHaveAttribute('disabled', 'true')
    await viewer.getByTestId('live-room-notice').click()
    await expect(viewer.getByTestId('live-notice-tag')).toHaveText('已结束')
    await expect(viewer.getByTestId('live-notice-action')).toHaveCount(0)
    await liveChannel(viewer)
    const card = viewer.getByTestId('live-replay-' + roomId)
    await expect(card).toHaveCount(0)

    await row.getByRole('button', { name: '回看' }).click()
    const replay = ops.getByRole('dialog', { name: '回看链接' })
    const url = 'https://replay.example.invalid/' + roomId
    await replay.getByPlaceholder('以 https 开头的受控地址').fill(url)
    await replay.getByRole('button', { name: '保存' }).click()
    await expect(replay).toContainText('回看地址和平台名称要一起填')
    await replay.getByPlaceholder('比如：视频号').fill('联调合作平台')
    await replay.getByRole('button', { name: '保存' }).click()
    await ops.locator('.el-message-box').getByRole('button', { name: '保存' }).click()
    await expect(row).toContainText(url)
    await liveChannel(viewer)
    await card.click()
    await viewer.getByTestId('live-notice-action').click()
    await expect(viewer.getByTestId('live-notice-external')).toContainText('联调合作平台')
    // 仅拦截未接入的外部合作方，业务页面、写入和查询全走真实服务。
    await mobile.route(url, route => route.fulfill({ status: 200, contentType: 'text/plain', body: '回看测试' }))
    const opened = mobile.waitForEvent('page')
    await viewer.getByTestId('live-notice-external-go').click()
    const external = await opened
    await expect(external).toHaveURL(url)
    await external.close()

    await row.getByRole('button', { name: '回看' }).click()
    await replay.getByPlaceholder('以 https 开头的受控地址').fill('')
    await replay.getByPlaceholder('比如：视频号').fill('')
    await replay.getByRole('button', { name: '保存' }).click()
    await ops.locator('.el-message-box').getByRole('button', { name: '保存' }).click()
    await expect(row).not.toContainText(url)
    await liveChannel(viewer)
    await expect(card).toHaveCount(0)
    await row.getByRole('button', { name: '改动记录' }).click()
    const history = ops.getByRole('dialog', { name: '改动记录 · ' + title })
    for (const update of [reason, '设回看', '去掉回看']) await expect(history).toContainText(update)
  } finally {
    // 测试中途退出也要关闭自己新建的房间，不影响平台内的其他直播和回看。
    const state = await request.get(testApi + '/api/v1/live/rooms/' + roomId + '?cityCode=' + CITY)
    if (state.ok() && (await state.json()).status !== 'ENDED') {
      await request.post(stream, { headers: host!.headers, data: { type: 'ENDED' } })
    }
    await request.put(testApi + '/api/v1/live-host/rooms/' + roomId + '/replay', {
      headers: host!.headers, data: { replayUrl: null, replayPlatformName: null },
    })
    await mobile.close()
    await operator.close()
  }
})
