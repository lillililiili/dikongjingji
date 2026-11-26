import { expect, test } from '@playwright/test'
import { BUYER, devPassword } from './support/trade-fixtures'

test.skip(process.env.E2E_SETTINGS_ONLY_BUILD !== '1', '使用仅开放 SETTINGS 的生产构建')

test('单独开放设置时可正常进入和退出，地址和客服关闭时不读取也不展示', async ({ page }) => {
  const hiddenRequests: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (/^\/api\/v1\/(account\/addresses|conversations)(\/|$)/.test(path)) hiddenRequests.push(path)
  })
  await page.goto('/#/pages/mine/index')
  await page.getByTestId('mine-login').click()
  await page.getByTestId('login-password-name').locator('input').fill(BUYER)
  await page.getByTestId('login-password-value').locator('input').fill(devPassword())
  await page.getByTestId('login-agreement').click()
  await page.getByTestId('login-password-submit').click()
  await page.getByTestId('mine-settings').click()
  await expect(page.getByTestId('settings-page')).toBeVisible()
  await expect(page.getByTestId('settings-addresses')).toHaveCount(0)
  await expect(page.getByTestId('settings-closure')).toHaveCount(0)
  await expect(page.getByTestId('settings-help')).toHaveCount(0)
  await expect(page.getByTestId('settings-about')).toBeVisible()
  await page.getByTestId('settings-logout').click()
  await page.locator('.uni-modal__btn_primary').click()
  await expect(page.getByTestId('mine-login')).toBeVisible()
  expect(hiddenRequests).toEqual([])
})
