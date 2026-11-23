import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

import { expect, type Page } from '@playwright/test'

// 和 2026-11-14 修订版原型画板同宽（390）并排对照（AGENTS §7）。仓库外层的 .planning 在工作区上面几层，
// 主工作区（.worktrees/x）和 Claude 的 worktree（.claude/worktrees/x）深度不同，所以往上找。
function planningRoot(): string {
  let dir = __dirname
  for (let i = 0; i < 10; i += 1) {
    if (existsSync(resolve(dir, '.planning/prototype/v2-20261114'))) return resolve(dir, '.planning')
    const parent = dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  return resolve(__dirname, '../../../../../.planning')
}

export const PLANNING = planningRoot()
export const BOARD_DIR = resolve(PLANNING, 'prototype/v2-20261114')

export function compareFolder(name: string): string {
  const folder = resolve(PLANNING, 'style-compare', name)
  mkdirSync(folder, { recursive: true })
  return folder
}

/**
 * App 的一屏，390 宽，视口和页面一样高（底栏这样落在最后，和画板一样），存成 `<名字>-app.png`。
 */
export async function shootApp(page: Page, folder: string, name: string): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(400)
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: 390, height: Math.max(844, height) })
  await page.waitForTimeout(300)
  await page.screenshot({ path: resolve(folder, `${name}-app.png`) })
  await page.setViewportSize({ width: 390, height: 844 })
}

/**
 * 画板的一帧（`frame` 是画板右下角“状态”下拉的一项；`prepare` 在截图前点一下画板，比如选中某个筛选）
 * 存成 `<画板>-board.png`，再和 App 的截图左右并排存成 `<名字>-compare.png`。
 */
export async function compareWithBoard(
  page: Page,
  folder: string,
  input: { board: string; app: string; frame?: string; prepare?: (page: Page) => Promise<void> },
): Promise<void> {
  const boardName = input.frame ? `${input.board}-${input.frame}` : input.board
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`file://${resolve(BOARD_DIR, `${input.board}.html`)}`)
  if (input.frame) await page.locator('select[data-prop="frame"]').selectOption(input.frame)
  const frame = page.locator('#root > div').first()
  await expect(frame).toBeVisible()
  if (input.prepare) await input.prepare(page)
  await page.waitForTimeout(300)
  await frame.screenshot({ path: resolve(folder, `${boardName}-board.png`) })

  const image = (file: string) => `data:image/png;base64,${readFileSync(resolve(folder, file)).toString('base64')}`
  await page.setViewportSize({ width: 820, height: 900 })
  await page.setContent(`
    <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
      <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
        <figure style="margin:0;width:390px"><figcaption>画板 ${boardName}</figcaption>
          <img style="width:390px;display:block" src="${image(`${boardName}-board.png`)}"></figure>
        <figure style="margin:0;width:390px"><figcaption>App</figcaption>
          <img style="width:390px;display:block" src="${image(`${input.app}-app.png`)}"></figure>
      </div>
    </body>`)
  await page.screenshot({ path: resolve(folder, `${input.app}-compare.png`), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
}
