import { expect, type Page } from '@playwright/test'

import type { AgreementDoc, PublishedAgreement } from '../../src/features/settings/domain/agreement'
import { testApi } from './test-stack'

export async function expectCurrentAgreement(page: Page, doc: AgreementDoc, fallbackTitle: string): Promise<void> {
  const response = await page.request.get(`${testApi}/api/v1/platform/agreements/${doc}`)
  await expect(page.getByTestId('agreement-page')).toHaveAttribute('data-doc', doc)
  if (response.status() === 404) {
    await expect(page.getByTestId('agreement-unpublished')).toHaveText('协议正文尚未发布')
    await expect(page.getByTestId('agreement-title')).toHaveText(fallbackTitle)
    return
  }
  expect(response.status()).toBe(200)
  const content: PublishedAgreement = await response.json()
  await expect(page.getByTestId('agreement-title')).toHaveText(content.title)
  await expect(page.locator('.agreement__meta-value')).toHaveText([content.version, content.publishedOn])
  await expect(page.locator('.agreement__section-title')).toHaveText(content.sections.map(section => section.title))
  await expect(page.locator('.agreement__section-body')).toHaveText(content.sections.map(section => section.body))
}
