import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { API, CITY, MERCHANT, devPassword, psql } from './support/trade-fixtures'
import { testAdmin } from './support/test-stack'

test.use({ trace: 'off', screenshot: 'off', viewport: { width: 1366, height: 860 } })

test('商家在窄屏编辑自己的草稿，放弃及保存修改后可确认删除并留痕', async ({ page, request }, testInfo) => {
  test.setTimeout(90_000)
  const title = '商家草稿验收' + randomUUID().slice(0, 8)
  const catalog = '/api/v1/provider/catalog/products'
  await page.goto(testAdmin + '/login')
  await page.getByLabel('用户名').fill(MERCHANT)
  await page.locator('input[autocomplete=current-password]').fill(devPassword())
  const signedIn = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/auth/login')
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  const login = await signedIn
  expect(login.status()).toBe(200)
  const headers = { Authorization: 'Bearer ' + (await login.json()).accessToken }
  await page.locator('.home .card[href="/workbench/products"]').click()
  const aside = page.locator('.layout__aside')
  const assertWidth = async (width: number) => {
    await expect.poll(() => aside.evaluate(el => Math.round(el.getBoundingClientRect().width))).toBe(width)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  }
  const confirm = (name: string) => page.locator('.el-message-box').getByRole('button', { name, exact: true }).click()
  await assertWidth(216)
  await page.getByTitle('折叠菜单').click()
  await assertWidth(64)
  await page.setViewportSize({ width: 375, height: 844 })
  await assertWidth(64)
  await page.setViewportSize({ width: 1366, height: 860 })
  await assertWidth(64)
  await page.getByTitle('展开菜单').click()
  await assertWidth(216)
  await page.setViewportSize({ width: 375, height: 844 })
  await assertWidth(64)
  await page.getByRole('button', { name: '新建商品' }).click()
  const dialog = page.getByRole('dialog', { name: '新建商品' })
  const bounds = await dialog.boundingBox()
  expect(bounds).toBeTruthy()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(375)
  await dialog.getByPlaceholder('App 上显示的名称').fill(title)
  await dialog.getByRole('button', { name: '新建', exact: true }).click()
  const created = page.waitForResponse(res => new URL(res.url()).pathname === catalog && res.request().method() === 'POST')
  await confirm('新建')
  const creation = await created
  expect(creation.status()).toBe(201)
  const { product } = await creation.json()
  expect(product.productId).toMatch(/^[a-f0-9-]{36}$/)
  const path = catalog + '/' + product.productId
  try {
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    await assertWidth(64)
    await page.getByRole('button', { name: '返回列表' }).click()
    await page.getByPlaceholder('商品名称').fill(title)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const row = page.getByRole('row').filter({ hasText: title })
    await row.getByTestId('product-remove').click()
    await confirm('取消')
    await row.getByTestId('product-edit').click()
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    const summary = page.getByRole('textbox', { name: '一句话介绍' })
    const remove = page.getByTestId('product-remove')
    await summary.fill('未保存的介绍')
    await expect(remove).toBeDisabled()
    await page.getByRole('tab', { name: /^规格/ }).click()
    await expect(page.getByRole('tab', { name: '基本资料' })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('button', { name: '放弃修改' }).click()
    await confirm('放弃修改')
    await expect(summary).toHaveValue('')
    await expect(remove).toBeEnabled()
    await summary.fill('保存在隔离测试库的商品介绍')
    await page.getByRole('button', { name: '保存资料' }).click()
    const saved = page.waitForResponse(res => new URL(res.url()).pathname === path && res.request().method() === 'PUT')
    await confirm('保存')
    const save = await saved
    expect(save.status()).toBe(200)
    const updated = await save.json()
    expect(updated.product.version).toBeGreaterThan(product.version)
    const staleDeletion = await request.delete(API + path + '?version=' + product.version, { headers })
    expect(staleDeletion.status()).toBe(409)
    await page.reload()
    await expect(summary).toHaveValue('保存在隔离测试库的商品介绍')
    for (const width of [320, 430, 375]) {
      await page.setViewportSize({ width, height: 844 })
      await assertWidth(64)
      await remove.scrollIntoViewIfNeeded()
      const control = await remove.boundingBox()
      expect(control).toBeTruthy()
      expect(control!.x).toBeGreaterThanOrEqual(0)
      expect(control!.x + control!.width).toBeLessThanOrEqual(width)
      const breadcrumb = page.locator('.layout__breadcrumb .el-breadcrumb__item:visible')
      await expect(breadcrumb).toHaveCount(1)
      await expect(breadcrumb).toContainText('商品详情')
      expect((await summary.boundingBox())!.width).toBeGreaterThan(145)
      await page.screenshot({ path: testInfo.outputPath('merchant-edit-' + width + '.png') })
    }
    await remove.click()
    const deleted = page.waitForResponse(res => new URL(res.url()).pathname === path && res.request().method() === 'DELETE')
    await confirm('删除')
    const deletion = await deleted
    expect(deletion.status()).toBe(204)
    expect(new URL(deletion.url()).searchParams.get('version')).toBe(String(updated.product.version))
    await expect(page).toHaveURL(testAdmin + '/workbench/products')
    await expect(row).toHaveCount(0)
    expect((await request.get(API + path, { headers })).status()).toBe(404)
    expect((await request.get(API + '/api/v1/mall/products/' + product.productId + '?cityCode=' + CITY)).status()).toBe(404)
    expect(psql(`SELECT status FROM mall_product WHERE id='${product.productId}'`)).toBe('DELETED')
    expect(psql(`SELECT count(*) FROM mall_catalog_operation WHERE product_id='${product.productId}' AND action='PRODUCT_DELETE'`)).toBe('1')
  } finally {
    const residual = await request.get(API + path, { headers })
    if (residual.ok()) {
      const draft = (await residual.json()).product
      expect(draft.status).toBe('DRAFT')
      expect((await request.delete(API + path + '?version=' + draft.version, { headers })).status()).toBe(204)
    } else {
      expect(residual.status()).toBe(404)
    }
  }
})
