import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { swipeCarouselTo } from './support/home-carousel'

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

    // Every channel has its own content now, so no placeholder is left.
    await page.getByTestId('home-channel-activity').click()
    await expect(page.getByTestId('activity-channel')).toBeVisible()
    await expect(page.getByTestId('home-channel-placeholder')).toHaveCount(0)
    await page.getByTestId('home-channel-mall').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
    await expect(page.getByTestId('home-channel-placeholder')).toHaveCount(0)
    await expect(page.getByTestId('home-search-input')).toContainText(
      '搜索设备、配件或课程',
    )
  })

  test.describe('轮播只靠左右滑动', () => {
    test.use({ reducedMotion: 'reduce' })

    test('HOME-002 轮播四张，下方没有圆点、箭头和暂停按钮，左滑依次切换', async ({ page }) => {
      await openHome(page)
      const carousel = page.getByTestId('home-carousel')

      await expect(carousel.locator('swiper-item, uni-swiper-item')).toHaveCount(4)
      await expect(carousel.getByRole('button', { name: /上一张|下一张|暂停轮播|播放轮播|第 \d 张/ })).toHaveCount(0)
      await expect(carousel).not.toContainText('运营精选')

      await expect(carousel).toHaveAttribute('data-current-slide', 'contest')
      await swipeCarouselTo(page, 'study-tour')
      await swipeCarouselTo(page, 'games')
      await swipeCarouselTo(page, 'experience')
      // Circular: one more swipe comes back to the first slide.
      await swipeCarouselTo(page, 'contest')
    })

    test('HOME-004 轮播按钮各到各的去处', async ({ page }) => {
      await openHome(page)

      await swipeCarouselTo(page, 'contest')
      await page.getByTestId('home-carousel-action-contest').click()
      await expect(page.getByTestId('home-channel-activity')).toHaveClass(
        /home-header__channel--active/,
      )

      await page.getByTestId('home-channel-recommend').click()
      await swipeCarouselTo(page, 'experience')
      await page.getByTestId('home-carousel-action-experience').click()
      await expect(page.getByTestId('home-channel-experience')).toHaveClass(
        /home-header__channel--active/,
      )

      await page.getByTestId('home-channel-recommend').click()
      await swipeCarouselTo(page, 'games')
      await page.getByTestId('home-carousel-action-games').click()
      await expect(page.getByTestId('play-games')).toBeVisible()
    })
  })

  test('HOME-003 轮播自己往后翻', async ({ page }) => {
    test.setTimeout(40_000)
    await openHome(page)
    const carousel = page.getByTestId('home-carousel')
    await expect(carousel).toHaveAttribute('data-current-slide', 'contest')

    // One interval is 5.5 s; the second slide has to arrive on its own.
    await expect(carousel).toHaveAttribute('data-current-slide', 'study-tour', {
      timeout: 9_000,
    })
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
  // No auto-play, so every screenshot shows the first slide.
  await page.emulateMedia({ reducedMotion: 'reduce' })

  for (const width of [320, 375, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await openHome(page)
    await swipeCarouselTo(page, 'contest')
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

// 验收清单已知问题 2：定位一直没有回应（系统弹窗没人点、定位服务卡住）时，首页不能一直停在“选择城市”。
test('HOME-901 定位一直不回应，几秒后退回默认城市并显示内容', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: () => undefined,
        watchPosition: () => 0,
        clearWatch: () => undefined,
      },
    })
  })
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营', { timeout: 12_000 })
  await expect(page.getByTestId('home-page')).toBeVisible()
})

// 验收清单已知问题 3：App 里状态栏盖在页面上面，首页往上滑时卡片不能从时间和灵动岛下面露出来。
// 浏览器里状态栏高度是 0，这里把它设成 44px 模拟 App。
test('HOME-902 往上滑时状态栏那一条有底色，内容不从下面露出来', async ({ page }) => {
  await page.addInitScript(() => {
    document.addEventListener('DOMContentLoaded', () => {
      const style = document.createElement('style')
      style.textContent = ':root, body, uni-page-body { --status-bar-height: 44px !important; }'
      document.head.appendChild(style)
    })
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.mouse.wheel(0, 600)
  await page.waitForTimeout(500)
  const topClasses = await page.evaluate(() => {
    const element = document.elementFromPoint(200, 20)
    return element ? element.closest('[class]')?.className ?? '' : ''
  })
  expect(String(topClasses)).toContain('home-page__status-bar')
})
