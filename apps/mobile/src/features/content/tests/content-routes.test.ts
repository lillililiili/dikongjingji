import { describe, expect, it } from 'vitest'

import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import pagesJson from '../../../pages.json?raw'
import articleHostSource from '../../../pages/content/article.vue?raw'
import topicHostSource from '../../../pages/content/topic.vue?raw'
import videoFeedHostSource from '../../../pages/content/video-feed.vue?raw'
import videoHostSource from '../../../pages/content/video.vue?raw'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import discoverRouteSource from '../../../pages/discover/index.vue?raw'
import contentFlagSource from '../application/content-preview.ts?raw'
import {
  articleMetaLine,
  splitColumns,
  topicDateRange,
} from '../application/content-view'
import {
  MERCHANT_HOME_NOTICE,
  buildContentArticleUrl,
  buildContentTopicUrl,
  buildContentVideoFeedUrl,
  buildContentVideoUrl,
  contentAuthorHome,
  contentRefTargetUrl,
  parseContentArticleTarget,
  parseContentTopicTarget,
  parseContentVideoFeedTarget,
  parseContentVideoTarget,
} from '../navigation/content-routes'
import { DISCOVER_CHANNELS, DISCOVER_LIVE_CHANNEL_ENABLED, discoverChannels } from '../domain/content'
import { CITY, articleCard } from './content-fixtures'

const contentSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('content routes', () => {
  it('carries only the stable id and the city', () => {
    expect(buildContentArticleUrl('a 1', CITY)).toBe(`/pages/content/article?articleId=a%201&cityCode=${CITY}`)
    expect(buildContentTopicUrl('t1', CITY)).toBe(`/pages/content/topic?topicId=t1&cityCode=${CITY}`)
    expect(parseContentArticleTarget({ articleId: 'a%201', cityCode: CITY })).toEqual({ articleId: 'a 1', cityCode: CITY })
    expect(parseContentTopicTarget({ topicId: 't1' })).toEqual({ topicId: 't1', cityCode: '' })
    expect(parseContentTopicTarget(undefined)).toEqual({ topicId: '', cityCode: '' })
  })

  it('opens each mention on the page its module already has', () => {
    const id = '11111111-1111-4111-8111-111111111111'
    expect(contentRefTargetUrl({ type: 'INSPECTION_SERVICE', refId: id }, CITY))
      .toBe(`/pages/service/inspection/detail?serviceId=${id}&cityCode=${CITY}`)
    expect(contentRefTargetUrl({ type: 'PRO_SERVICE', refId: id }, CITY))
      .toBe(`/pages/proservice/detail?serviceId=${id}&cityCode=${CITY}`)
    expect(contentRefTargetUrl({ type: 'PRODUCT', refId: id }, CITY))
      .toBe(`/pages/mall/product?productId=${id}&cityCode=${CITY}`)
    expect(contentRefTargetUrl({ type: 'ACTIVITY', refId: id }, CITY))
      .toBe(`/pages/activity/detail?activityId=${id}&cityCode=${CITY}`)
    expect(contentRefTargetUrl({ type: 'VENUE', refId: id }, CITY))
      .toBe(`/pages/venue/detail?venueId=${id}&cityCode=${CITY}`)
  })

  it('sends 主页 to the provider page, says a merchant has none, and shows none for the platform', () => {
    expect(contentAuthorHome({ kind: 'PROVIDER', organizationId: 'p1', name: '鹰眼智能巡检' }, CITY))
      .toEqual({ kind: 'PAGE', url: `/pages/provider/detail?providerId=p1&cityCode=${CITY}` })
    expect(contentAuthorHome({ kind: 'MERCHANT', organizationId: 'm1', name: '示例机器人商家' }, CITY))
      .toEqual({ kind: 'NOTICE', message: MERCHANT_HOME_NOTICE })
    expect(contentAuthorHome({ kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' }, CITY))
      .toEqual({ kind: 'NONE' })
  })

  it('registers the article, topic and video pages', () => {
    for (const path of ['pages/content/article', 'pages/content/topic', 'pages/content/video', 'pages/content/video-feed']) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
    const feed = JSON.parse(pagesJson).pages.find((page: { path: string }) => page.path === 'pages/content/video-feed')
    expect(feed?.style).toMatchObject({ navigationStyle: 'custom', disableScroll: true })
  })

  it('opens a video and the stream by stable id and city, and comes back to them after a login', () => {
    const video = 'f3200000-0000-4000-8000-000000000001'
    expect(buildContentVideoUrl(video, CITY)).toBe(`/pages/content/video?videoId=${video}&cityCode=${CITY}`)
    expect(buildContentVideoFeedUrl(CITY, video)).toBe(`/pages/content/video-feed?cityCode=${CITY}&videoId=${video}`)
    expect(buildContentVideoFeedUrl(CITY)).toBe(`/pages/content/video-feed?cityCode=${CITY}`)
    expect(buildContentVideoFeedUrl(CITY, null, 'FOLLOWING')).toBe(`/pages/content/video-feed?cityCode=${CITY}&feed=FOLLOWING`)
    expect(parseContentVideoTarget({ videoId: video, cityCode: CITY })).toEqual({ videoId: video, cityCode: CITY })
    expect(parseContentVideoFeedTarget({ cityCode: CITY, videoId: video })).toEqual({ cityCode: CITY, startVideoId: video, feed: 'RECOMMEND' })
    expect(parseContentVideoFeedTarget({ cityCode: CITY, feed: 'FOLLOWING' }))
      .toEqual({ cityCode: CITY, startVideoId: null, feed: 'FOLLOWING' })
    expect(parseContentVideoFeedTarget({ cityCode: CITY, feed: 'ALL' }).feed).toBe('RECOMMEND')
    // The login page keeps these addresses as they are.
    for (const url of [buildContentVideoUrl(video, CITY), buildContentVideoFeedUrl(CITY, video, 'FOLLOWING')]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
  })
})

describe('content view', () => {
  it('writes the article line with the city for a city article and the kind otherwise', () => {
    const city = { code: CITY, name: '东营市' }
    expect(articleMetaLine(articleCard(), city)).toBe('10/28 发布 · 东营')
    expect(articleMetaLine(articleCard({ kind: 'KNOWLEDGE', cityCode: null, publishedAt: '2026-10-25T18:00:00Z' }), city))
      .toBe('10/26 发布 · 知识')
  })

  it('marks sample topics on their days and fills the waterfall in turn', () => {
    expect(topicDateRange({ startsOn: '2026-11-01', endsOn: '2026-11-30', sample: true })).toBe('11/01–11/30（示例）')
    expect(topicDateRange({ startsOn: '2026-11-01', endsOn: '2026-11-30', sample: false })).toBe('11/01–11/30')
    expect(splitColumns([1, 2, 3, 4, 5])).toEqual([[1, 3, 5], [2, 4]])
  })
})

describe('content preview gate', () => {
  it('reads its own release switch, without importing it', () => {
    expect(flagExpression(contentFlagSource, 'CONTENT_PREVIEW_ENABLED'))
      .toBe(moduleFlagExpression('CONTENT'))
    const offenders = Object.entries(contentSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })

  it('shows only a notice on the video pages in public builds', () => {
    expect(videoHostSource).toContain('v-if="CONTENT_PREVIEW_ENABLED && target"')
    expect(videoHostSource).toContain('<ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />')
    expect(videoFeedHostSource).toContain('v-if="CONTENT_PREVIEW_ENABLED && target && target.cityCode"')
    expect(videoFeedHostSource).toContain('<ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />')
  })

  it('shows only a notice on the article and topic pages in public builds', () => {
    expect(articleHostSource).toContain('v-if="CONTENT_PREVIEW_ENABLED && target"')
    expect(articleHostSource).toContain('<ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />')
    expect(topicHostSource).toContain('v-if="CONTENT_PREVIEW_ENABLED && target"')
    expect(topicHostSource).toContain('<ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />')
  })

  it('keeps 发现 closed in public builds, with the root bar under both states', () => {
    expect(discoverRouteSource).toContain('v-if="CONTENT_PREVIEW_ENABLED"')
    // The shared flag imported here would leave the home page's sample content in the public bundle.
    expect(discoverRouteSource).not.toMatch(/from\s+'[^']*platform-preview'/)
    expect(discoverRouteSource).toMatch(
      /发现频道尚未开通<\/text>\s*<\/view>\s*<RootAppTabBar active="DISCOVER" \/>/,
    )
    expect(discoverRouteSource).not.toContain('import.meta.env.DEV')
    expect(
      JSON.parse(pagesJson).pages.find((page: { path: string }) => page.path === 'pages/discover/index')?.style
        .navigationStyle,
    ).toBe('custom')
  })

  it('keeps 直播 in its board place but hidden until the live module turns it on', () => {
    expect(DISCOVER_LIVE_CHANNEL_ENABLED).toBe(false)
    expect(DISCOVER_CHANNELS).toEqual(['RECOMMEND', 'VIDEO', 'EVENTS', 'STUDY', 'CASE', 'KNOWLEDGE'])
    expect(discoverChannels(true)).toEqual(['RECOMMEND', 'VIDEO', 'LIVE', 'EVENTS', 'STUDY', 'CASE', 'KNOWLEDGE'])
  })

  it('shows no view, play or read counts it has no source for', () => {
    const shipped = Object.entries(contentSources).filter(([path]) => !path.includes('/tests/'))
    expect(shipped.length).toBeGreaterThan(5)
    for (const [path, source] of shipped) {
      // Comments may say what is left out; the code and the page text may not show it.
      const code = source.split('\n').filter((line) => !/^\s*(\/\/|\*|\/\*\*|<!--)/.test(line)).join('\n')
      expect(code, path).not.toMatch(/观看|阅读量|播放量|打赏|收藏/)
    }
  })
})
