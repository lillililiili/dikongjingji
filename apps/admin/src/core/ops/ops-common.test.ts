import { describe, expect, it } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import { isHttpsUrl, isRetainedMediaUrl, mediaProblem, uploadedFileName } from './media-api'
import { actionLabel, describeMissing, missingFields, minorToYuanText, yuanToMinor } from './ops-common'

describe('上线前必填项', () => {
  const incomplete = new ApiRequestError(422, 'OPS_PUBLISH_INCOMPLETE', '', 't-1', { missing: ['coverUrl', 'sessions', 'x'] })

  it('从 details.missing 取出缺的字段键并翻成中文', () => {
    expect(missingFields(incomplete)).toEqual(['coverUrl', 'sessions', 'x'])
    expect(describeMissing(incomplete, { coverUrl: '封面', sessions: '场次' })).toBe('上线前还差：封面、场次、x')
  })

  it('别的错误不当作缺必填项', () => {
    expect(missingFields(new ApiRequestError(409, 'OPS_VERSION_CONFLICT', '', null))).toEqual([])
    expect(describeMissing(new Error('x'), {})).toBeNull()
  })
})

describe('金额', () => {
  it('元和分互换，不用浮点乘法', () => {
    expect(yuanToMinor('12.5')).toBe(1250)
    expect(yuanToMinor('0.07')).toBe(7)
    expect(yuanToMinor('1299')).toBe(129900)
    expect(yuanToMinor('1.234')).toBeNull()
    expect(yuanToMinor('-1')).toBeNull()
    expect(minorToYuanText(129900)).toBe('1299')
    expect(minorToYuanText(1250)).toBe('12.50')
  })
})

describe('素材', () => {
  it('仅允许保留该记录已有的开发媒体，新增或替换使用 HTTPS', () => {
    const cover = '/api/v1/dev-media/images/preview/course-flight.jpg'
    expect(isRetainedMediaUrl(cover, cover)).toBe(true)
    const inspection = '/api/v1/dev-media/images/inspection/detail-report-cover.png'
    expect(isRetainedMediaUrl(inspection, inspection)).toBe(true)
    expect(isRetainedMediaUrl(inspection, null)).toBe(false)
    expect(isRetainedMediaUrl(cover, null)).toBe(false)
    expect(isRetainedMediaUrl(cover, '/api/v1/dev-media/images/home/hero-world.jpg')).toBe(false)
    expect(isRetainedMediaUrl('https://cdn.example.org/cover.jpg', cover)).toBe(true)
  })

  it('只收 https 受控地址', () => {
    expect(isHttpsUrl('https://cdn.example.com/a.jpg')).toBe(true)
    expect(isHttpsUrl('http://cdn.example.com/a.jpg')).toBe(false)
    expect(isHttpsUrl('https://u:p@cdn.example.com/a.jpg')).toBe(false)
    expect(isHttpsUrl('https://cdn.example.com/a b.jpg')).toBe(false)
  })

  it('认得本平台上传的地址，用文件名带登录预览', () => {
    expect(uploadedFileName('https://media.dev.localhost/ops-media/0b7c4c9e-1f7a-4c3e-9d55-2b3a4c5d6e7f.png'))
      .toBe('0b7c4c9e-1f7a-4c3e-9d55-2b3a4c5d6e7f.png')
    expect(uploadedFileName('https://cdn.example.com/a.png')).toBeNull()
  })

  it('上传前按类型和大小挡一次', () => {
    expect(mediaProblem({ type: 'image/gif', size: 10 }, 'IMAGE')).toContain('JPEG')
    expect(mediaProblem({ type: 'image/png', size: 6 * 1024 * 1024 }, 'IMAGE')).toContain('5 MB')
    expect(mediaProblem({ type: 'video/mp4', size: 10 }, 'VIDEO')).toBeNull()
  })

  it('动作编码翻成中文，块里自己的动作可以补', () => {
    expect(actionLabel('STOP_SALE')).toBe('停售')
    expect(actionLabel('FORCE_END', { FORCE_END: '强制结束' })).toBe('强制结束')
    expect(actionLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})
