import { describe, expect, it } from 'vitest'

import {
  inspectionCategoryFromText,
  inspectionDetailHeroImage,
  inspectionServiceImage,
} from '../data/inspection-service-images'
import { describeLoginResume } from '../presentation/auth/login-resume'
import {
  categoryBadgeColour,
  categoryBadgeLabel,
  deliverableIcon,
  processStepIcon,
} from '../presentation/detail/detail-line-icons'
import { toFileRows } from '../presentation/delivery/delivery-view'
import type { InspectionDelivery } from '../data/service-flow/inspection-plan-api'

// 2026-11-20 prototype alignment (docs/redesign/06 R2, R4).
describe('service pictures by category', () => {
  it('keeps a server cover and otherwise picks the category picture', () => {
    expect(inspectionServiceImage({ coverUrl: 'https://cdn.example.com/a.png', categoryCode: 'OIL_GAS' }))
      .toBe('https://cdn.example.com/a.png')
    expect(inspectionServiceImage({ categoryCode: 'OIL_GAS' })).toBe('/static/images/inspection/service-oil-gas.webp')
    expect(inspectionServiceImage({ categoryCode: 'PHOTOVOLTAIC' })).toBe('/static/images/inspection/service-photovoltaic.webp')
    expect(inspectionServiceImage({ categoryCode: 'PARK' })).toBe('/static/images/inspection/service-park.webp')
  })

  it('reads the category from the title where a snapshot has no code, never the old tower picture', () => {
    expect(inspectionCategoryFromText('燃气管道巡检服务')).toBe('OIL_GAS')
    expect(inspectionCategoryFromText('光伏电站巡检服务')).toBe('PHOTOVOLTAIC')
    expect(inspectionCategoryFromText('变电站智能巡检')).toBe('SUBSTATION')
    expect(inspectionCategoryFromText('电力输电线路无人机巡检')).toBe('POWER')
    expect(inspectionServiceImage({ title: '园区综合巡检服务' })).toBe('/static/images/inspection/service-park.webp')
    expect(inspectionServiceImage({ title: '未知服务' })).not.toContain('inspection-hero-v1')
  })

  it('gives the 热力 detail head the board picture', () => {
    expect(inspectionDetailHeroImage('THERMAL')).toBe('/static/images/inspection/category-park.webp')
    expect(inspectionDetailHeroImage('POWER')).toBe('/static/images/inspection/service-power-line.webp')
  })
})

describe('detail page board details', () => {
  it('names and colours the badge by category', () => {
    expect(categoryBadgeLabel('电力')).toBe('电力巡检')
    expect(categoryBadgeLabel('定期巡检')).toBe('定期巡检')
    expect(categoryBadgeColour('OIL_GAS')).toBe('#1d63c4')
    expect(categoryBadgeColour('PHOTOVOLTAIC')).toBe('#6a4fe0')
    expect(categoryBadgeColour('THERMAL')).toBe('#ba470b')
    expect(categoryBadgeColour('POWER')).toBe('#11804a')
  })

  it('picks the step and file icons by their words', () => {
    expect(processStepIcon('报告交付', 0)).toBe(processStepIcon('报告验收', 3))
    expect(processStepIcon('提交需求', 3)).not.toBe(processStepIcon('现场巡检', 0))
    expect(deliverableIcon('缺陷清单')).toBe(deliverableIcon('隐患清单'))
    expect(deliverableIcon('点位标注')).not.toBe(deliverableIcon('巡检报告'))
  })
})

describe('login resume card', () => {
  it('names the interrupted action, and shows nothing for a root tab', () => {
    expect(describeLoginResume('/pages/service/inspection/booking?serviceId=1&cityCode=370500')).toBe('预约巡检服务')
    expect(describeLoginResume('/pages/home/index')).toBe('')
    expect(describeLoginResume('/pages/mine/index')).toBe('')
  })
})

describe('delivery file rows', () => {
  it('titles each row by what the file is and its format', () => {
    const file = (fileId: string, type: string, displayName: string, contentType: string, extra: object = {}) => ({
      fileId, type, displayName, contentType, sizeBytes: 1024, pointCount: null, itemCount: null, ...extra,
    })
    const rows = toFileRows({
      files: [
        file('a', 'INSPECTION_REPORT', '第1次-巡检报告.pdf', 'application/pdf'),
        file('b', 'DEFECT_LIST', '缺陷.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
        file('c', 'POINT_KML', '点位.kml', 'application/vnd.google-earth.kml+xml', { pointCount: 24 }),
        file('d', 'IMAGE_ARCHIVE', '影像.zip', 'application/zip', { itemCount: 128 }),
      ],
    } as unknown as InspectionDelivery, {})

    expect(rows.map((row) => row.title)).toEqual([
      '巡检报告（PDF）',
      '缺陷清单（表格）',
      '点位标注（KML · 24 个点位）',
      '高清巡检影像（128 张）',
    ])
  })
})
