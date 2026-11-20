import { describe, expect, it } from 'vitest'

import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import discoverSource from '../../content/presentation/discover/ContentDiscoverPage.vue?raw'
import pagesJson from '../../../pages.json?raw'
import noticeHostSource from '../../../pages/live/notice.vue?raw'
import roomHostSource from '../../../pages/live/room.vue?raw'
import discoverRouteSource from '../../../pages/discover/index.vue?raw'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import liveFlagSource from '../application/live-preview.ts?raw'
import { liveCityCode } from '../application/live-city'
import { resolveLivePreviewCover } from '../data/preview/live-preview-covers'
import {
  buildLiveNoticeUrl,
  buildLiveRoomUrl,
  liveCardUrl,
  parseLiveTarget,
} from '../navigation/live-routes'

const ROOM = 'f4000000-0000-4000-8000-000000000001'

const liveSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('live routes', () => {
  it('carry only the room id and the city', () => {
    expect(buildLiveRoomUrl(ROOM, '370500')).toBe(`/pages/live/room?roomId=${ROOM}&cityCode=370500`)
    expect(buildLiveNoticeUrl(ROOM, '370500')).toBe(`/pages/live/notice?roomId=${ROOM}&cityCode=370500`)
    // From a notification there is no city: the page reads the App's.
    expect(buildLiveRoomUrl(ROOM)).toBe(`/pages/live/room?roomId=${ROOM}`)
  })

  it('opens a live room on the room page and everything else on the preview page', () => {
    expect(liveCardUrl({ roomId: ROOM, status: 'LIVE' }, '370500')).toContain('/pages/live/room?')
    expect(liveCardUrl({ roomId: ROOM, status: 'SCHEDULED' }, '370500')).toContain('/pages/live/notice?')
    expect(liveCardUrl({ roomId: ROOM, status: 'ENDED' }, '370500')).toContain('/pages/live/notice?')
  })

  it('reads a target back and refuses anything that is not a room id', () => {
    expect(parseLiveTarget({ roomId: ROOM, cityCode: '370500' })).toEqual({ roomId: ROOM, cityCode: '370500' })
    expect(parseLiveTarget({ roomId: encodeURIComponent(ROOM) })).toEqual({ roomId: ROOM, cityCode: '' })
    expect(parseLiveTarget({ roomId: ROOM, cityCode: '东营' })).toEqual({ roomId: ROOM, cityCode: '' })
    expect(parseLiveTarget({ roomId: '../x' })).toBeNull()
    expect(parseLiveTarget(undefined)).toBeNull()
  })

  it('reads the room in the address city, then the current one, then the default one', () => {
    expect(liveCityCode('370500', { code: '370100' }, '110000')).toBe('370500')
    expect(liveCityCode('', { code: '370100' }, '110000')).toBe('370100')
    expect(liveCityCode('', null, ' 110000 ')).toBe('110000')
    expect(liveCityCode('', null, '')).toBe('')
  })

  it('registers both pages and survives the login round trip', () => {
    const pages = JSON.parse(pagesJson).pages as Array<{ path: string; style: Record<string, string> }>
    for (const path of ['pages/live/notice', 'pages/live/room']) {
      expect(pages.find((page) => page.path === path)?.style.navigationStyle).toBe('custom')
    }
    expect(normalizeInspectionReturnUrl(buildLiveRoomUrl(ROOM, '370500'))).toBe(buildLiveRoomUrl(ROOM, '370500'))
    expect(normalizeInspectionReturnUrl(buildLiveNoticeUrl(ROOM, '370500'))).toBe(buildLiveNoticeUrl(ROOM, '370500'))
    expect(normalizeInspectionReturnUrl('/pages/live/room')).toBe('/pages/home/index')
  })
})

describe('live preview gate', () => {
  it('uses exactly the expression of the shared preview flag, without importing it', () => {
    expect(flagExpression(liveFlagSource, 'LIVE_PREVIEW_ENABLED'))
      .toBe(flagExpression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED'))
    const offenders = Object.entries(liveSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })

  it('shows only a closed notice on the live pages in public builds', () => {
    for (const source of [noticeHostSource, roomHostSource]) {
      expect(source).toContain('v-if="LIVE_PREVIEW_ENABLED && target && cityCode"')
      expect(source).toContain('<LiveUnavailable v-else-if="!LIVE_PREVIEW_ENABLED" />')
    }
  })

  it('adds the 直播 tab and the live bar on 发现 only behind the live preview flag', () => {
    expect(discoverSource).toContain('LIVE_PREVIEW_ENABLED ? withLiveTab(DISCOVER_CHANNELS) : [...DISCOVER_CHANNELS]')
    expect(discoverSource).toContain('if (!LIVE_PREVIEW_ENABLED || !cityCode.value) return')
    expect(discoverSource).toContain('v-if="isGrid && liveNow"')
    // 发现 itself stays closed in public builds (content-routes.test.ts).
    expect(discoverRouteSource).toContain('v-if="CONTENT_PREVIEW_ENABLED"')
  })

  it('has board pictures for the sample rooms in preview builds only', () => {
    expect(resolveLivePreviewCover(ROOM, true)?.url).toBe('/static/images/preview/content-park-patrol.webp')
    expect(resolveLivePreviewCover(ROOM, false)).toBeNull()
    expect(resolveLivePreviewCover('f4000000-0000-4000-8000-0000000000ff', true)).toBeNull()
  })
})
