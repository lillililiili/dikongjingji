import { expect, type Page } from '@playwright/test'

// The home carousel has no arrows, dots or pause button (user 2026-11-23): slides change by
// swiping only. Specs that need one slide swipe left until it shows; they run with reduced
// motion so auto-play does not move the slide away before the click.
export async function swipeCarouselTo(page: Page, slideId: string): Promise<void> {
  const carousel = page.getByTestId('home-carousel')
  await expect(carousel).toBeVisible()

  for (let swipes = 0; swipes < 4; swipes += 1) {
    if ((await carousel.getAttribute('data-current-slide')) === slideId) break
    const box = await carousel.boundingBox()
    if (!box) throw new Error('home carousel has no box')
    const y = box.y + box.height / 2
    await page.mouse.move(box.x + box.width * 0.85, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * 0.15, y, { steps: 8 })
    await page.mouse.up()
    // The slide transition takes 350 ms; the attribute follows the swiper's change event.
    await page.waitForTimeout(500)
  }

  await expect(carousel).toHaveAttribute('data-current-slide', slideId)
  await expect(page.getByTestId(`home-carousel-action-${slideId}`)).toBeInViewport()
}
