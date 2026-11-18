import { describe, expect, it, vi } from 'vitest'

import { describeRegion, regionOfCity } from '../application/address-book'
import {
  EMPTY_ADDRESS_FORM,
  findAddressFormProblems,
  formFromAddress,
  formatAddressLine,
  maskPhone,
  trimAddressForm,
} from '../application/address-form'
import { createAddressApi } from '../data/address-api'
import type { Address } from '../domain/address'

const ADDRESS: Address = {
  addressId: 'a1',
  recipientName: '王女士',
  phone: '13800002026',
  region: { provinceCode: '370000', provinceName: '山东省', cityCode: '370500', cityName: '东营市' },
  detail: '东营区 示例路 88 号',
  isDefault: true,
}

const PROVINCES = [
  { code: '370000', name: '山东省', pinyin: '', initials: '', cities: [
    { code: '370500', name: '东营市', pinyin: '', initials: '', provinceCode: '370000', provinceName: '山东省' },
  ] },
  { code: '110000', name: '北京市', pinyin: '', initials: '', cities: [
    { code: '110100', name: '北京市', pinyin: '', initials: '', provinceCode: '110000', provinceName: '北京市' },
  ] },
]

describe('address form', () => {
  it('accepts a complete form and names what is missing otherwise', () => {
    const form = { recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: '示例路 88 号', isDefault: false }
    expect(findAddressFormProblems(form)).toEqual([])
    expect(findAddressFormProblems(EMPTY_ADDRESS_FORM)).toEqual(['NAME', 'PHONE', 'CITY', 'DETAIL'])
    expect(findAddressFormProblems({ ...form, phone: '12800002026' })).toEqual(['PHONE'])
    expect(findAddressFormProblems({ ...form, recipientName: '  ' })).toEqual(['NAME'])
    expect(findAddressFormProblems({ ...form, detail: 'x'.repeat(81) })).toEqual(['DETAIL'])
  })

  it('round-trips an address and trims what is sent', () => {
    expect(formFromAddress(ADDRESS)).toEqual({
      recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: '东营区 示例路 88 号', isDefault: true,
    })
    expect(trimAddressForm({ recipientName: ' 王女士 ', phone: ' 13800002026 ', cityCode: '370500', detail: ' x ', isDefault: false }))
      .toEqual({ recipientName: '王女士', phone: '13800002026', cityCode: '370500', detail: 'x', isDefault: false })
  })

  it('masks the phone and writes the address on one line', () => {
    expect(maskPhone('13800002026')).toBe('138****2026')
    expect(maskPhone('12345')).toBe('12345')
    expect(formatAddressLine(ADDRESS)).toBe('山东省东营市 东营区 示例路 88 号')
    expect(formatAddressLine({ ...ADDRESS, region: { provinceCode: '110000', provinceName: '北京市', cityCode: '110100', cityName: '北京市' } }))
      .toBe('北京市 东营区 示例路 88 号')
  })

  it('finds the province of a city for the picker', () => {
    expect(regionOfCity(PROVINCES, '370500')).toEqual({ provinceCode: '370000', cityCode: '370500' })
    expect(regionOfCity(PROVINCES, '999999')).toEqual({ provinceCode: null, cityCode: null })
    expect(describeRegion(PROVINCES, { provinceCode: '370000', cityCode: '370500' })).toBe('山东省 东营市')
    expect(describeRegion(PROVINCES, { provinceCode: '110000', cityCode: '110100' })).toBe('北京市')
    expect(describeRegion(PROVINCES, { provinceCode: null, cityCode: null })).toBeNull()
  })
})

describe('address api', () => {
  it('reads, creates, updates and deletes with the caller\'s token', async () => {
    const request = vi.fn().mockResolvedValue({ ...ADDRESS, items: [ADDRESS] })
    const api = createAddressApi(request)
    const form = formFromAddress(ADDRESS)

    await api.list('t')
    await api.create('t', form)
    await api.update('t', 'a 1', form)
    await api.remove('t', 'a 1')

    expect(request.mock.calls.map(([input]) => [input.method, input.url, input.data])).toEqual([
      ['GET', '/api/v1/account/addresses', undefined],
      ['POST', '/api/v1/account/addresses', form],
      ['PUT', '/api/v1/account/addresses/a%201', form],
      ['DELETE', '/api/v1/account/addresses/a%201', undefined],
    ])
    expect(request.mock.calls.every(([input]) => input.headers.Authorization === 'Bearer t')).toBe(true)
  })
})
