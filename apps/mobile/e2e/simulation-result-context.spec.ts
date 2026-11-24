import { expect, test } from '@playwright/test'

const robotId = 'b1000000-0000-4000-8000-000000000001'
const droneId = 'b1000000-0000-4000-8000-000000000002'
const recordId = 'b1000000-1000-4000-8000-000000000001'

test('游客的机器人结果不能当作无人机结果打开，原链接仍可回到机器人任务', async ({ page }) => {
  await page.goto('/#/pages/experience/simulation?experienceId=' + robotId)
  await expect(page.getByTestId('simulation-level')).toBeVisible()
  await page.getByTestId('simulation-add-forward').click()
  await page.getByTestId('simulation-run').click()
  await expect(page.getByTestId('simulation-run')).not.toHaveText('运行中…')
  await page.getByTestId('simulation-exit').click()
  await page.locator('.uni-modal__btn_primary').click()
  await expect(page.getByTestId('simulation-result-guest')).toBeVisible()
  const url = new URL(page.url())
  const originalHash = url.hash
  expect(originalHash).toContain('experienceId=' + robotId)
  url.hash = originalHash.replace(robotId, droneId)
  url.searchParams.set('visit', 'mismatched')
  await page.goto(url.href)
  await expect(page.getByTestId('simulation-result-missing')).toBeVisible()
  await expect(page.getByTestId('simulation-result-main')).toHaveCount(0)
  url.hash = originalHash
  url.searchParams.set('visit', 'original')
  await page.goto(url.href)
  await page.getByTestId('simulation-result-main').click()
  await expect(page).toHaveURL(new RegExp('simulation\\?experienceId=' + robotId))
})

test('账号结果也按记录所属体验核对，跨项目链接提供返回入口', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'local-test-session'))
  await page.route('**/api/v1/experience/records/' + recordId, route => route.fulfill({ json: {
    recordId, experience: { experienceId: robotId, type: 'SIMULATION', title: '机器人避障模拟',
      coverImage: null, productId: null, simulationTemplate: 'ROBOT_OBSTACLE' },
    status: 'UNFINISHED', startedAt: '2026-11-24T12:00:00Z', endedAt: '2026-11-24T12:01:00Z',
    result: { score: 0, durationSeconds: 60, levelsCompleted: 0, levelsTotal: 3, mistakes: 0 },
  } }))
  await page.goto('/#/pages/experience/result?experienceId=' + droneId + '&recordId=' + recordId)
  const missing = page.getByTestId('simulation-result-missing')
  await expect(missing).toContainText('返回')
  await expect(page.getByTestId('simulation-result')).not.toContainText('机器人避障模拟')
})
