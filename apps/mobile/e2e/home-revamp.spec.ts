import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

// Home revamp from the 2026-11-14 revised prototype: carousel, renamed channel, play games.
// None of these cases needs the back end; the recommend channel keeps its entries when content fails to load.
const visualOutput = resolve(__dirname, '../../../.planning/home-revamp/visual')

async function openHome(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-carousel')).toBeVisible()
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

function activeDot(page: Page) {
  return page.locator('.home-carousel__control--dot[aria-pressed="true"]')
}

// The home tab stays alive between visits, so the carousel may already be paused.
async function pauseCarousel(page: Page): Promise<void> {
  const pause = page.getByTestId('home-carousel-pause')
  if ((await pause.getAttribute('aria-label')) === '暂停轮播') await pause.click()
  await expect(pause).toHaveAttribute('aria-label', '播放轮播')
}

test.describe('首页改版', () => {
  test('HOME-001 频道行为五个，“活动”改名“赛事研学”', async ({ page }) => {
    await openHome(page)

    await expect(page.locator('.home-header__channel')).toHaveText([
      '推荐',
      '专业服务',
      '赛事研学',
      '体验',
      '商城',
    ])
    await expect(page.getByTestId('home-search-input')).toContainText(
      '搜索设备、赛事研学或服务',
    )

    // 赛事研学 has its own list since step ④a; 商城 is still a placeholder.
    await page.getByTestId('home-channel-activity').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
    await expect(page.getByTestId('home-channel-placeholder')).toHaveCount(0)
    await page.getByTestId('home-channel-mall').click()
    await expect(page.getByTestId('home-channel-placeholder')).toContainText(
      '商城',
    )
    await expect(page.getByTestId('home-search-input')).toContainText(
      '搜索设备、配件或课程',
    )
  })

  test('HOME-002 轮播四张，圆点、前后按钮可切换', async ({ page }) => {
    await openHome(page)
    await pauseCarousel(page)

    await expect(page.locator('.home-carousel__control--dot')).toHaveCount(4)
    await page.getByTestId('home-carousel-dot-contest').click()
    await expect(activeDot(page)).toHaveAttribute(
      'data-testid',
      'home-carousel-dot-contest',
    )
    await expect(page.getByTestId('home-carousel-action-contest')).toBeInViewport()

    await page.getByTestId('home-carousel-next').click()
    await expect(activeDot(page)).toHaveAttribute(
      'data-testid',
      'home-carousel-dot-study-tour',
    )

    await page.getByTestId('home-carousel-prev').click()
    await page.getByTestId('home-carousel-prev').click()
    await expect(activeDot(page)).toHaveAttribute(
      'data-testid',
      'home-carousel-dot-experience',
    )
    await expect(
      page.getByTestId('home-carousel-action-experience'),
    ).toBeInViewport()
  })

  test('HOME-003 轮播自动切换，暂停后不再前进', async ({ page }) => {
    test.setTimeout(40_000)
    await openHome(page)
    await page.getByTestId('home-carousel-dot-contest').click()
    await expect(activeDot(page)).toHaveAttribute(
      'data-testid',
      'home-carousel-dot-contest',
    )

    // One interval is 5.5 s; the second slide has to arrive on its own.
    await expect(activeDot(page)).toHaveAttribute(
      'data-testid',
      'home-carousel-dot-study-tour',
      { timeout: 9_000 },
    )

    await pauseCarousel(page)
    await page.waitForTimeout(7_000)
    await expect(activeDot(page)).toHaveAttribute(
      'data-testid',
      'home-carousel-dot-study-tour',
    )
  })

  test('HOME-004 轮播按钮各到各的去处', async ({ page }) => {
    await openHome(page)
    await pauseCarousel(page)

    await page.getByTestId('home-carousel-dot-contest').click()
    await page.getByTestId('home-carousel-action-contest').click()
    await expect(page.getByTestId('home-channel-activity')).toHaveClass(
      /home-header__channel--active/,
    )

    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-carousel-dot-experience').click()
    await page.getByTestId('home-carousel-action-experience').click()
    await expect(page.getByTestId('home-channel-experience')).toHaveClass(
      /home-header__channel--active/,
    )

    await page.getByTestId('home-channel-recommend').click()
    await page.getByTestId('home-carousel-dot-games').click()
    await page.getByTestId('home-carousel-action-games').click()
    await expect(page.getByTestId('play-games')).toBeVisible()
  })

  test('HOME-005 金刚位：趣玩挑战换下专业服务，商品入口进商城频道', async ({
    page,
  }) => {
    await openHome(page)

    await expect(page.locator('.home-entries__king-label')).toHaveText([
      '无人机',
      '机器人',
      '趣玩挑战',
      '设备租赁',
      '培训课程',
      '赛事报名',
      '研学体验',
      '线下场馆',
    ])
    await expect(page.getByTestId('home-king-service')).toHaveCount(0)

    await page.getByTestId('home-king-drone').click()
    await expect(page.getByTestId('home-channel-mall')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('HOME-006 大卡“找专业服务”仍进专业服务频道', async ({ page }) => {
    await openHome(page)

    await page.getByTestId('home-card-service').click()
    await expect(page.getByTestId('home-channel-service')).toHaveClass(
      /home-header__channel--active/,
    )
  })
})

test.describe('趣玩挑战', () => {
  test('PLAY-001 首页金刚位 → 趣玩挑战 → 机器人迷宫，返回逐级退回', async ({
    page,
  }) => {
    await openHome(page)
    await page.getByTestId('home-king-games').click()
    await expect(page.getByTestId('play-games')).toBeVisible()
    await expect(page.locator('.play-games__title')).toHaveText([
      '机器人迷宫',
      '无人机寻星',
    ])

    await page.getByTestId('play-game-robot-maze').click()
    await expect(page.getByTestId('play-board')).toBeVisible()
    await expect(page.getByTestId('play-status')).toContainText(
      '收集 3 颗星星，再到达右下角终点',
    )

    await page.getByTestId('play-back').click()
    await expect(page.getByTestId('play-games')).toBeVisible()
    await page.getByTestId('play-back').click()
    await expect(page.getByTestId('home-page')).toBeVisible()
  })

  test('PLAY-002 边界和障碍拦住，不计步；星星只收一次', async ({ page }) => {
    await page.goto('/#/pages/play/game?game=robot-maze')
    await expect(page.getByTestId('play-board')).toBeVisible()

    await page.getByTestId('play-move-up').click()
    await expect(page.getByTestId('play-status')).toContainText('到边界啦')
    await expect(page.getByTestId('play-score-moves')).toHaveText('0')
    await expect(page.getByTestId('play-score-time')).toHaveText('00:00')

    await page.getByTestId('play-move-right').click()
    await page.getByTestId('play-move-down').click()
    await expect(page.getByTestId('play-status')).toContainText('前方有障碍')
    await expect(page.getByTestId('play-score-moves')).toHaveText('1')
    await expect(page.getByTestId('play-cell-1')).toHaveAttribute(
      'data-kind',
      'AVATAR',
    )

    await page.getByTestId('play-move-right').click()
    await expect(page.getByTestId('play-score-stars')).toHaveText('1 / 3')
    await page.getByTestId('play-move-left').click()
    await page.getByTestId('play-move-right').click()
    await expect(page.getByTestId('play-score-stars')).toHaveText('1 / 3')
    await expect(page.getByTestId('play-score-moves')).toHaveText('4')
  })

  test('PLAY-003 没收齐星星到终点不算通关；收齐后通关，方向键停用，可重新开始', async ({
    page,
  }) => {
    await page.goto('/#/pages/play/game?game=robot-maze')
    await expect(page.getByTestId('play-board')).toBeVisible()

    const tap = async (directions: string[]): Promise<void> => {
      for (const direction of directions) {
        await page.getByTestId(`play-move-${direction}`).click()
      }
    }

    // Along the top row and down the right edge: one star only.
    await tap(['right', 'right', 'right', 'right', 'down', 'down', 'down', 'down'])
    await expect(page.getByTestId('play-status')).toContainText('还差 2 颗星星')
    await expect(page.getByTestId('play-success')).toHaveCount(0)

    // Back for the other two.
    await tap(['left', 'left'])
    await expect(page.getByTestId('play-score-stars')).toHaveText('2 / 3')
    await tap(['left', 'left', 'up', 'up'])
    await expect(page.getByTestId('play-score-stars')).toHaveText('3 / 3')
    await tap(['down', 'down', 'right', 'right', 'right', 'right'])

    await expect(page.getByTestId('play-success')).toBeInViewport()
    await expect(page.getByTestId('play-result')).toContainText('3 颗星星 · 20 步')
    // uni-app renders the attribute as disabled="true" on H5; a real click proves it does nothing.
    await expect(page.getByTestId('play-move-up')).toHaveAttribute('disabled', 'true')
    await page.getByTestId('play-move-up').click({ force: true })
    await expect(page.getByTestId('play-score-moves')).toHaveText('20')
    await expect(page.getByTestId('play-cell-24')).toHaveAttribute('data-kind', 'AVATAR')

    const stoppedAt = await page.getByTestId('play-score-time').textContent()
    await page.waitForTimeout(1_500)
    await expect(page.getByTestId('play-score-time')).toHaveText(stoppedAt ?? '')

    await page.getByTestId('play-restart').click()
    await expect(page.getByTestId('play-success')).toHaveCount(0)
    await expect(page.getByTestId('play-score-moves')).toHaveText('0')
    await expect(page.getByTestId('play-score-time')).toHaveText('00:00')
    await expect(page.getByTestId('play-cell-0')).toHaveAttribute(
      'data-kind',
      'AVATAR',
    )
    await expect(page.getByTestId('play-move-up')).not.toHaveAttribute(
      'disabled',
      'true',
    )
    await page.getByTestId('play-move-right').click()
    await expect(page.getByTestId('play-score-moves')).toHaveText('1')
  })

  test('PLAY-004 无人机寻星可用键盘方向键', async ({ page }) => {
    await page.goto('/#/pages/play/game?game=drone-route')
    await expect(page.getByTestId('play-board')).toBeVisible()

    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('play-cell-2')).toHaveAttribute(
      'data-kind',
      'AVATAR',
    )
    await expect(page.getByTestId('play-score-moves')).toHaveText('2')
  })

  test('PLAY-005 关卡不存在时给出口；直接打开的关卡页返回到趣玩入口', async ({
    page,
  }) => {
    await page.goto('/#/pages/play/game?game=unknown')
    await expect(page.getByTestId('play-game-missing')).toContainText(
      '没有找到这个关卡',
    )
    await expect(page.getByTestId('play-board')).toHaveCount(0)

    await page.getByTestId('play-game-missing-back').click()
    await expect(page.getByTestId('play-games')).toBeVisible()

    // The list was opened without a page behind it, so back leaves for the home tab.
    await page.getByTestId('play-back').click()
    await expect(page.getByTestId('home-page')).toBeVisible()
  })
})

test('VIS-HOME 首页与趣玩在 320/375/430 无横向溢出并留存截图', async ({ page }) => {
  mkdirSync(visualOutput, { recursive: true })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await openHome(page)
    await pauseCarousel(page)
    await page.getByTestId('home-carousel-dot-contest').click()
    await expect(page.getByTestId('home-carousel-action-contest')).toBeInViewport()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `home-${width}.png`) })

    await page.getByTestId('home-king-games').click()
    await expect(page.getByTestId('play-games')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `play-games-${width}.png`) })

    await page.getByTestId('play-game-drone-route').click()
    await expect(page.getByTestId('play-board')).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(visualOutput, `play-game-${width}.png`) })
  }
})
