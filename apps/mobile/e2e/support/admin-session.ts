import { expect, type Page } from '@playwright/test'
import { testAdmin as ADMIN } from './test-stack'
import { devPassword } from './trade-fixtures'

export async function adminSignIn(page: Page, loginName: string, target: string): Promise<void> {
  await page.goto(`${ADMIN}${target}`)
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.locator('input[autocomplete="username"]').fill(loginName)
  await page.locator('input[autocomplete="current-password"]').fill(devPassword())
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  // 按路径比：登录页的地址 /login?redirect=<target> 结尾也是 target，用“以 target 结尾”会在登录请求回来前就算通过。
  await expect(page).toHaveURL((url) => url.pathname === target)
}
