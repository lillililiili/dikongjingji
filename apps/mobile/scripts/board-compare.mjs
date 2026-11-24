// 原型画板和 App 截图并排对照（S5-3 起用）。
// 用法（在 apps/mobile 下）：
//   node scripts/board-compare.mjs <画板名> "<帧名或空>" <App 截图 png> <输出目录> [输出名]
// 例：node scripts/board-compare.mjs EnrollWaitlist 候补中 ../../.planning/activity-s53/visual/EnrollWaitlist-waiting-app.png \
//       ../../.planning/style-compare/activity-s53 EnrollWaitlist-waiting
// 输出 <名>-board.png（画板整页，390 宽）和 <名>-compare.png（左画板、右 App）。
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { chromium } from '@playwright/test'

const PROTOTYPE = fileURLToPath(new URL('../../../prototype/v2-20261114/', import.meta.url))
const [board, frame, appShot, outDir, outName] = process.argv.slice(2)
if (!board || !appShot || !outDir) {
  console.error('usage: board-compare.mjs <Board> "<frame>" <app.png> <outDir> [name]')
  process.exit(2)
}
const name = outName || (frame ? `${board}-${frame}` : board)
mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome' })
try {
  const page = await browser.newPage({ viewport: { width: 460, height: 920 } })
  await page.goto(pathToFileURL(resolve(PROTOTYPE, `${board}.html`)).href)
  await page.waitForTimeout(600)
  if (frame) {
    const ok = await page.evaluate((value) => {
      const select = document.querySelector('select[data-prop="frame"]')
      if (!select) return false
      select.value = value
      select.dispatchEvent(new Event('change', { bubbles: true }))
      select.dispatchEvent(new Event('input', { bubbles: true }))
      return select.value === value
    }, frame)
    if (!ok) throw new Error(`frame ${frame} not found on ${board}`)
    await page.waitForTimeout(400)
  }
  await page.addStyleTag({
    content: `html,body{overflow:visible!important;height:auto!important;background:#fff!important}
      .stage{position:static!important;transform:none!important;width:390px!important;height:auto!important;margin:0!important}
      .phone{height:auto!important;border-radius:0!important;box-shadow:none!important}
      .screen{position:static!important;overflow:visible!important;height:auto!important}
      .notch,.home,.side{display:none!important}`,
  })
  await page.waitForTimeout(200)
  const boardShot = resolve(outDir, `${name}-board.png`)
  await page.locator('.phone').screenshot({ path: boardShot, timeout: 20_000, animations: 'disabled' })

  const appCopy = resolve(outDir, `${name}-app.png`)
  if (resolve(appShot) !== appCopy) copyFileSync(appShot, appCopy)
  const dataUri = (file) => `data:image/png;base64,${readFileSync(file).toString('base64')}`
  const compare = await browser.newPage({ viewport: { width: 820, height: 900 } })
  await compare.setContent(`<html><body style="margin:0;background:#888;display:flex;gap:16px;padding:12px;align-items:flex-start;font:12px sans-serif">
    <div><div style="color:#fff">画板 ${board}${frame ? ' · ' + frame : ''}</div><img src="${dataUri(boardShot)}" width="390"></div>
    <div><div style="color:#fff">App</div><img src="${dataUri(appCopy)}" width="390"></div></body></html>`)
  await compare.waitForTimeout(300)
  await compare.screenshot({ path: resolve(outDir, `${name}-compare.png`), fullPage: true })
  console.log(resolve(outDir, `${name}-compare.png`))
} finally {
  await browser.close()
}
