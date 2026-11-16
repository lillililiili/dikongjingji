import { describe, expect, it } from 'vitest'

import heroSource from '../presentation/detail/components/ServiceDetailHero.vue?raw'
import cardSource from '../presentation/detail/components/ServicePricingCard.vue?raw'
import { resolveServicePricingRows } from '../presentation/detail/service-pricing-rows'

describe('M03 价格与取消 card (T05)', () => {
  it('shows the three rows in the agreed order with the server wording', () => {
    expect(resolveServicePricingRows({
      priceNote: '按公里计价，现场评估后以方案报价为准',
      priceFactors: ['线路长度', '杆塔数量', '现场条件'],
      cancellationNote: '方案确认前取消不收费，确认后按方案约定',
    })).toEqual([
      { code: 'PRICING', label: '计价方式', value: '按公里计价，现场评估后以方案报价为准' },
      { code: 'FACTORS', label: '影响价格', value: '线路长度、杆塔数量、现场条件' },
      { code: 'CANCELLATION', label: '取消说明', value: '方案确认前取消不收费，确认后按方案约定' },
    ])
  })

  it('drops rows the server did not configure and hides the card when none is left', () => {
    expect(resolveServicePricingRows({
      priceNote: '',
      priceFactors: ['  '],
      cancellationNote: '方案确认前可取消',
    })).toEqual([
      { code: 'CANCELLATION', label: '取消说明', value: '方案确认前可取消' },
    ])
    expect(resolveServicePricingRows({ priceNote: ' ', priceFactors: [], cancellationNote: '' })).toEqual([])
    expect(cardSource).toContain('v-if="rows.length"')
  })

  it('keeps the project-quote wording from the server as the pricing row', () => {
    expect(resolveServicePricingRows({
      priceNote: '按项目报价，真实金额在方案里确认',
      priceFactors: [],
      cancellationNote: '',
    })).toEqual([
      { code: 'PRICING', label: '计价方式', value: '按项目报价，真实金额在方案里确认' },
    ])
  })

  it('moves the price note out of the hero and shows the service cities next to the certification tag', () => {
    expect(heroSource).not.toContain('service-detail-hero__note')
    expect(heroSource).toContain('`可服务城市 ${props.detail.serviceCityNames.join(\'、\')}`')
    expect(heroSource).toContain('class="service-detail-hero__certification"')
    expect(heroSource).toContain('background: #e8f7ef')
  })
})
