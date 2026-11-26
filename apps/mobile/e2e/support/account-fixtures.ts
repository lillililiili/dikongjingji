import { randomInt, randomUUID } from 'node:crypto'

import { expect, type APIRequestContext, type Page } from '@playwright/test'

import { API, CITY, psql } from './trade-fixtures'

export const randomPhone = () => `199${String(randomInt(0, 1e8)).padStart(8, '0')}`
export const mask = (phone: string) => `${phone.slice(0, 3)}****${phone.slice(7)}`

export function latestCode(phone: string): string {
  expect(phone).toMatch(/^199[0-9]{8}$/)
  const content = psql(`SELECT content FROM identity_dev_sms_outbox WHERE phone='${phone}' ORDER BY sent_at DESC LIMIT 1;`)
  const match = /验证码 (\d{6})/.exec(content)
  if (!match) throw new Error(`no code sent to ${phone}`)
  return match[1]
}

// Codes of a number sent earlier in the run move to yesterday, so the 60 s and 10-a-day limits stay out of the way.
export function forgetSentCodes(phone: string): void {
  expect(phone).toMatch(/^199[0-9]{8}$/)
  psql(`UPDATE identity_sms_code SET sent_at = sent_at - interval '1 day' WHERE phone='${phone}';`)
}

export async function signUpByApi(request: APIRequestContext, phone: string, deviceName = 'iPhone 15') {
  forgetSentCodes(phone)
  expect((await request.post(`${API}/api/v1/auth/sms-codes`, { data: { phone } })).status()).toBe(202)
  const login = await request.post(`${API}/api/v1/auth/phone-login`, {
    data: { phone, code: latestCode(phone), device: { deviceKey: randomUUID(), deviceName, platform: 'IOS', cityCode: CITY } },
  })
  expect(login.ok()).toBeTruthy()
  const { accessToken, refreshToken } = await login.json()
  return { token: accessToken as string, refreshToken: refreshToken as string, headers: { Authorization: `Bearer ${accessToken}` } }
}

// A fresh document each time (a changed query before # reloads; a hash-only change would keep the page).
export async function openSignedIn(page: Page, token: string, route: string): Promise<void> {
  await page.goto(`/?e2e=${Date.now()}#/pages/mine/about`)
  await page.evaluate((value) => localStorage.setItem('inspection.accessToken', value), token)
  await page.goto(`/?e2e=${Date.now()}#${route}`)
}

// uni puts the test id on its input wrapper; the native input is inside.
export const field = (page: Page, testId: string) => page.getByTestId(testId).locator('input')
