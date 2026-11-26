import { randomUUID } from 'node:crypto'
import { existsSync, readFileSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'

import { expect, test, type APIRequestContext } from '@playwright/test'

import { API, CITY, MERCHANT, apiLogin, databaseAvailable, psql } from './support/trade-fixtures'
import { testFiles } from './support/test-stack'

test.use({ trace: 'off' })

// Distinctive 96 × 48 image so the browser checks the uploaded bytes, including image decoding.
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAGAAAAAwCAIAAABhdOiYAAAA6ElEQVR4nO3YwQnCMBhA4Vhq5xA86k6u4Ci6m9PkJHgqgn1pUgt/eO/WBgr5CCXJ4Xh7JvvdsDBmAnGuIEggSCBoTB2V76/54/S4tn9z7JJm/rKRaehVp3C0f6BcMP8Wo9hAuXjm1UaxgXYoMFBeuSjqFlFgoH0SCBIIEqhfoGnlFrluSx0YaJ9iA03Fi6L6RBYbKJXNvOW8Gh4o0fwbT/OdXHdMHwXvg6BNbsi+Gk+X8+Yf7ake/kF/TSBIIEggSCBIIEggSCBIIEggSCBIIEggSCBIIEggSCBIIEggSCBIIEggSCBIIEggSKC03BshnCaPscZ/LwAAAABJRU5ErkJggg==', 'base64')
const upload = { file: { name: 'test-cover.png', mimeType: 'image/png', buffer: png } }
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/

function cleanupUpload(root: string, group: string, name: string, data: Buffer = png) {
  expect(/\.(png|mp4)$/.test(name) && uuid.test(name.slice(0, -4))).toBe(true)
  const file = join(root, group, name)
  if (existsSync(file) && readFileSync(file).equals(data)) unlinkSync(file)
}

// A shared file stays public while any public display uses it. A non-trial lesson alone
// cannot grant access, even if an old import put an image address in its resource field.
async function checkCourseAndVenueReferences(request: APIRequestContext, fileName: string) {
  expect(uuid.test(fileName.slice(0, -4)) && fileName.endsWith('.png')).toBe(true)
  const stored = 'https://media.dev.localhost/ops-media/' + fileName
  const guest = API + '/api/v1/dev-media/ops/covers/' + fileName
  const id = randomUUID()
  const organization = 'a1000000-0000-4000-8000-000000000003'
  try {
    psql(`BEGIN;
      INSERT INTO course(id, organization_id, delivery_mode, topic, title, status)
        VALUES ('${id}', '${organization}', 'ONLINE', 'DRONE', '运营图片课程验收', 'LISTED');
      INSERT INTO course_chapter(id, course_id, title, sort_order) VALUES ('${id}', '${id}', '第一章', 1);
      INSERT INTO course_lesson(course_id, chapter_id, title, duration_seconds, trial, video_url, sort_order)
        VALUES ('${id}', '${id}', '付费课时', 60, false, '${stored}', 1);
      INSERT INTO venue(id, operator_id, name, city_code, address, status)
        VALUES ('${id}', '${organization}', '运营图片场馆验收', '370500', '测试场馆', 'DRAFT');
      INSERT INTO venue_device(id, venue_id, category, name, image_url, trial_mode, status)
        VALUES ('${id}', '${id}', 'DRONE', '验收设备', '${stored}', 'DISPLAY_ONLY', 'ACTIVE');
      COMMIT;`)
    expect((await request.get(guest)).status()).toBe(404)
    psql(`UPDATE course SET gallery_urls=jsonb_build_array('${stored}'::text) WHERE id='${id}'`)
    expect((await request.get(guest)).status()).toBe(200)
    psql(`UPDATE venue SET status='PUBLISHED' WHERE id='${id}'; UPDATE course SET status='UNLISTED' WHERE id='${id}'`)
    expect((await request.get(guest)).status()).toBe(200)
    psql(`UPDATE venue_device SET status='DISABLED' WHERE id='${id}'`)
    expect((await request.get(guest)).status()).toBe(404)
  } finally {
    psql(`BEGIN;
      DELETE FROM venue_device WHERE id='${id}';
      DELETE FROM venue WHERE id='${id}';
      DELETE FROM course WHERE id='${id}';
      COMMIT;`)
  }
}

test('访客只在运营图文实际发布后能看见新上传的封面，下架后同一地址失效', async ({ page, request }, testInfo) => {
  test.setTimeout(75_000)
  const password = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password || !databaseAvailable(), '需要本机运营测试账号与可清理的开发数据库')
  const login = await request.post(API + '/api/v1/auth/login', { data: { loginName: 'dev-operator', password } })
  expect(login.ok()).toBe(true)
  const headers = { Authorization: 'Bearer ' + (await login.json()).accessToken }
  const asset = await request.post(API + '/api/v1/ops/media', { headers, multipart: upload })
  expect(asset.status()).toBe(201)
  const { fileName, url } = await asset.json()
  const publicPath = '/api/v1/dev-media/ops/covers/' + fileName
  const assetUrl = API + publicPath
  const preview = API + '/api/v1/ops/media/' + fileName
  const articles = API + '/api/v1/ops/content/articles'
  const title = '运营封面验收 ' + randomUUID().slice(0, 8)
  let id: string | undefined
  try {
    const draft = await request.post(articles, { headers, data: {
      kind: 'KNOWLEDGE', title, authorKind: 'PLATFORM', authorName: '开发验收', cityCode: CITY,
      coverUrl: url, sample: true, blocks: [{ type: 'PARAGRAPH', text: '这条测试图文用于验证图片的发布与下线。' }],
    } })
    expect(draft.status()).toBe(201)
    const created = await draft.json()
    expect(created.articleId).toMatch(uuid)
    id = created.articleId
    const item = articles + '/' + id
    const detail = API + '/api/v1/content/articles/' + id + '?cityCode=' + CITY
    expect((await request.get(assetUrl)).status()).toBe(404)
    expect((await request.get(preview)).status()).toBe(401)
    expect(await (await request.get(preview, { headers })).body()).toEqual(png)

    const future = new Date(Date.parse(draft.headers().date) + 24 * 60 * 60 * 1000).toISOString()
    const scheduled = await request.post(item + '/publish', { headers, data: { version: created.version, publishAt: future } })
    expect(scheduled.ok()).toBe(true)
    expect((await request.get(detail)).status()).toBe(404)
    expect((await request.get(assetUrl)).status()).toBe(404)
    const later = await request.post(item + '/offline', { headers, data: { version: (await scheduled.json()).version } })
    expect(later.ok()).toBe(true)
    const publish = await request.post(item + '/publish', { headers, data: { version: (await later.json()).version, publishAt: null } })
    expect(publish.ok()).toBe(true)
    expect((await request.get(detail)).ok()).toBe(true)
    const publicImage = await request.get(assetUrl)
    expect(publicImage.status()).toBe(200)
    expect(publicImage.headers()['cache-control']).toContain('no-store')
    expect(await publicImage.body()).toEqual(png)

    const received = page.waitForResponse(response => new URL(response.url()).pathname === publicPath && response.ok())
    await page.goto('/#/pages/content/article?articleId=' + id + '&cityCode=' + CITY)
    await expect(page.getByText(title, { exact: true })).toBeVisible()
    await received
    const hero = page.getByTestId('content-article-hero-image')
    await expect(hero).toBeVisible()
    await expect.poll(() => hero.evaluate(el => {
      const image = el instanceof HTMLImageElement ? el : el.querySelector('img')
      return image?.complete ? [image.naturalWidth, image.naturalHeight] : null
    })).toEqual([96, 48])
    await page.screenshot({ path: testInfo.outputPath('published-article.png') })

    const offline = await request.post(item + '/offline', { headers, data: { version: (await publish.json()).version } })
    expect(offline.ok()).toBe(true)
    expect((await request.get(detail)).status()).toBe(404)
    expect((await request.get(assetUrl)).status()).toBe(404)
    expect((await request.head(assetUrl)).status()).toBe(404)
    expect(await (await request.get(preview, { headers })).body()).toEqual(png)
    await checkCourseAndVenueReferences(request, fileName)
  } finally {
    if (id && uuid.test(id)) psql("BEGIN; DELETE FROM content_ops_change WHERE subject_type='ARTICLE' AND subject_id='" + id +
      "'; DELETE FROM content_article WHERE id='" + id + "'; COMMIT;")
    cleanupUpload(join(testFiles, 'lowaltitude-dev-ops-media'), 'ops-media', fileName)
  }
})

test('商家上传仍经商品媒体接口公开读取', async ({ request }) => {
  test.skip(!databaseAvailable(), '需要本机商家测试账号与可清理的开发数据库')
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const response = await request.post(API + '/api/v1/provider/catalog/media', { headers: merchant!.headers, multipart: upload })
  expect(response.status()).toBe(201)
  const asset = await response.json()
  expect(asset.mediaId).toMatch(uuid)
  const path = new URL(asset.url).pathname
  const name = path.split('/').at(-1)!
  try {
    expect(path).toBe('/api/v1/mall/media/products/' + name)
    const guest = await request.get(API + path)
    expect(guest.status()).toBe(200)
    expect(await guest.body()).toEqual(png)
    expect((await request.get(API + '/api/v1/dev-media/ops/covers/' + name)).status()).toBe(404)
  } finally {
    psql("DELETE FROM mall_media_asset WHERE id='" + asset.mediaId + "'")
    cleanupUpload(join(testFiles, 'lowaltitude-dev-mall-media'), 'products', name)
  }
})

test('运营上传的发现视频发布后可在 App 播放、跳转，撤回后访客不能再分段读取', async ({ page, request }, testInfo) => {
  test.setTimeout(75_000)
  const password = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password || !databaseAvailable(), '需要本机运营测试账号与可清理的开发数据库')
  const login = await request.post(API + '/api/v1/auth/login', { data: { loginName: 'dev-operator', password } })
  expect(login.ok()).toBe(true)
  const headers = { Authorization: 'Bearer ' + (await login.json()).accessToken }
  const media = API + '/api/v1/ops/media'
  const videos = API + '/api/v1/ops/content/videos'
  const root = join(testFiles, 'lowaltitude-dev-ops-media')
  const recording = readFileSync(join(__dirname, '../src/static/videos/preview/study-camp-day.mp4'))
  const uploaded = await request.post(media, { headers, multipart: {
    file: { name: 'discovery-test.mp4', mimeType: 'video/mp4', buffer: recording },
  } })
  expect(uploaded.status()).toBe(201)
  const clip = await uploaded.json()
  let coverName: string | undefined
  let id: string | undefined
  const course = randomUUID()
  try {
    const image = await request.post(media, { headers, multipart: upload })
    expect(image.status()).toBe(201)
    const cover = await image.json()
    coverName = cover.fileName
    expect(uuid.test(clip.fileName.slice(0, -4)) && clip.fileName.endsWith('.mp4')).toBe(true)
    expect(clip.url).toBe('https://media.dev.localhost/ops-media/' + clip.fileName)
    const path = '/api/v1/dev-media/ops/videos/' + clip.fileName
    const guest = API + path
    const range = { headers: { Range: 'bytes=4-11' } }
    const title = '运营视频验收 ' + randomUUID().slice(0, 8)
    const draft = await request.post(videos, { headers, data: {
      title, authorKind: 'PLATFORM', authorName: '开发验收', cityCode: CITY, sample: true,
      coverUrl: cover.url, playUrl: clip.url, durationSeconds: 10, orientation: 'PORTRAIT',
    } })
    expect(draft.status()).toBe(201)
    const created = await draft.json()
    expect(created.videoId).toMatch(uuid)
    id = created.videoId
    const item = videos + '/' + id
    psql(`BEGIN;
      INSERT INTO course(id, organization_id, delivery_mode, topic, title, status)
        VALUES ('${course}', 'a1000000-0000-4000-8000-000000000003', 'ONLINE', 'DRONE', '付费课程视频验收', 'LISTED');
      INSERT INTO course_chapter(id, course_id, title, sort_order) VALUES ('${course}', '${course}', '第一章', 1);
      INSERT INTO course_lesson(course_id, chapter_id, title, duration_seconds, trial, video_url, sort_order)
        VALUES ('${course}', '${course}', '付费课时', 10, false, '${clip.url}', 1);
      COMMIT;`)
    expect((await request.get(guest, range)).status()).toBe(404)
    expect((await request.get(API + '/api/v1/dev-media/ops/covers/' + clip.fileName)).status()).toBe(404)
    expect((await request.get(media + '/' + clip.fileName)).status()).toBe(401)
    expect(await (await request.get(media + '/' + clip.fileName, { headers })).body()).toEqual(recording)
    const future = new Date(Date.parse(draft.headers().date) + 24 * 60 * 60 * 1000).toISOString()
    const scheduled = await request.post(item + '/publish', { headers, data: { version: created.version, publishAt: future } })
    expect(scheduled.ok()).toBe(true)
    expect((await request.head(guest)).status()).toBe(404)
    const later = await request.post(item + '/offline', { headers, data: { version: (await scheduled.json()).version } })
    expect(later.ok()).toBe(true)
    const published = await request.post(item + '/publish', { headers, data: { version: (await later.json()).version } })
    expect(published.ok()).toBe(true)
    const metadata = await request.head(guest)
    expect(metadata.status()).toBe(200)
    expect(metadata.headers()['content-length']).toBe(String(recording.length))
    expect(metadata.headers()['content-type']).toBe('video/mp4')
    const part = await request.get(guest, range)
    expect(part.status()).toBe(206)
    expect(part.headers()['content-range']).toBe('bytes 4-11/' + recording.length)
    expect(part.headers()['cache-control']).toContain('no-store')
    expect(await part.body()).toEqual(recording.subarray(4, 12))
    expect((await request.get(guest, { headers: { Range: 'bytes=' + recording.length + '-' } })).status()).toBe(416)

    await page.goto('/#/pages/content/video?videoId=' + id + '&cityCode=' + CITY)
    const player = page.getByTestId('content-video-player').locator('video')
    await expect(player).toBeVisible()
    await expect(player).toHaveAttribute('src', guest)
    await page.getByTestId('content-video-play').click()
    await expect.poll(() => player.evaluate((el: HTMLVideoElement) => el.currentTime)).toBeGreaterThan(0)
    const seekTo = await player.evaluate((el: HTMLVideoElement) => {
      el.pause()
      el.currentTime = el.duration / 2
      return el.currentTime
    })
    expect(seekTo).toBeGreaterThan(0)
    await expect.poll(() => player.evaluate((el: HTMLVideoElement) => !el.seeking && el.videoWidth > 0)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('published-video.png') })
    const offline = await request.post(item + '/offline', { headers, data: { version: (await published.json()).version } })
    expect(offline.ok()).toBe(true)
    expect((await request.head(guest)).status()).toBe(404)
    expect((await request.get(guest, range)).status()).toBe(404)
    expect((await request.get(media + '/' + clip.fileName, { headers })).status()).toBe(200)
  } finally {
    psql(`DELETE FROM course WHERE id='${course}'`)
    if (id && uuid.test(id)) psql(`BEGIN; DELETE FROM content_ops_change WHERE subject_type='VIDEO' AND subject_id='${id}';
      DELETE FROM content_article WHERE id='${id}'; COMMIT;`)
    cleanupUpload(root, 'ops-media', clip.fileName, recording)
    if (coverName) cleanupUpload(root, 'ops-media', coverName)
  }
})
