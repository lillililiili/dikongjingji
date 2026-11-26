import { expect, type Page } from '@playwright/test'

// The home carousel has no arrows, dots or pause button (user 2026-11-23): slides change by
// swiping only. Specs that need one slide swipe left until it shows; they run with reduced
// motion so auto-play does not move the slide away before the click.
export async function swipeCarouselOnce(page: Page): Promise<void> {
  const carousel = page.getByTestId('home-carousel')
  const previous = await carousel.getAttribute('data-current-slide')
  const box = await carousel.boundingBox()
  if (!box || !previous) throw new Error('首页轮播尚未就绪')
  const y = box.y + box.height / 2
  await page.mouse.move(box.x + box.width * 0.85, y)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * 0.15, y, { steps: 8 })
  await page.mouse.up()
  await expect(carousel).not.toHaveAttribute('data-current-slide', previous)
}

export async function swipeCarouselTo(page: Page, slideId: string): Promise<void> {
  const carousel = page.getByTestId('home-carousel')
  await expect(carousel).toBeVisible()

  const slideCount = await carousel.locator('swiper-item, uni-swiper-item').count()
  for (let swipes = 0; swipes < slideCount; swipes += 1) {
    if ((await carousel.getAttribute('data-current-slide')) === slideId) break
    await swipeCarouselOnce(page)
  }

  await expect(carousel).toHaveAttribute('data-current-slide', slideId)
  await expect(page.getByTestId(`home-carousel-action-${slideId}`)).toBeInViewport()
}
