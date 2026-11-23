import { describe, expect, it } from 'vitest'

import pagesJson from '../../../pages.json?raw'
import homeSource from '../../../pages/home/index.vue?raw'
import { parseScanText } from '../application/scan-target'

const ORIGINS = ['https://app.example.test']
const ID = 'c2000000-0000-4000-8000-000000000101'

describe('scan landing (board Scan)', () => {
  it('opens a platform link on its page with only its own parameters', () => {
    expect(parseScanText(`https://app.example.test/#/pages/mall/product?productId=${ID}&cityCode=370500&from=poster`, ORIGINS))
      .toEqual({ kind: 'PAGE', url: `/pages/mall/product?productId=${ID}&cityCode=370500`, subject: '商品' })
    expect(parseScanText(`/pages/activity/detail?activityId=${ID}`, ORIGINS))
      .toEqual({ kind: 'PAGE', url: `/pages/activity/detail?activityId=${ID}`, subject: '活动' })
    expect(parseScanText(`  /pages/venue/detail?venueId=${ID}&cityCode=bad!  `, ORIGINS))
      .toEqual({ kind: 'PAGE', url: `/pages/venue/detail?venueId=${ID}`, subject: '场馆' })
  })

  it('never opens an outside link, an unknown route or a broken id', () => {
    expect(parseScanText(`https://evil.example.test/#/pages/mall/product?productId=${ID}`, ORIGINS)).toEqual({ kind: 'UNKNOWN' })
    expect(parseScanText('https://app.example.test/pay', ORIGINS)).toEqual({ kind: 'UNKNOWN' })
    expect(parseScanText(`/pages/trade/cashier?orderId=${ID}`, ORIGINS)).toEqual({ kind: 'UNKNOWN' })
    expect(parseScanText('/pages/mall/product?productId=1', ORIGINS)).toEqual({ kind: 'UNKNOWN' })
    expect(parseScanText('WIFI:S:guest;T:WPA;P:secret;;', ORIGINS)).toEqual({ kind: 'UNKNOWN' })
    expect(parseScanText('', ORIGINS)).toEqual({ kind: 'UNKNOWN' })
  })

  it('tells a check-in code apart', () => {
    expect(parseScanText('ACT-1234-5678-9ABC', ORIGINS)).toEqual({ kind: 'CHECK_IN_CODE' })
    expect(parseScanText('ven-abcd-efgh-2345', ORIGINS)).toEqual({ kind: 'CHECK_IN_CODE' })
  })

  it('has its page and the home scan button opens it in preview builds', () => {
    expect(pagesJson).toContain('"path": "pages/scan/index"')
    expect(homeSource).toContain('uni.navigateTo({ url: SCAN_PATH })')
  })
})
