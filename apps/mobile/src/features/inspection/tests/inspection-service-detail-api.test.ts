import { describe, expect, it } from 'vitest'

import { createInspectionServiceDetailApi } from '../data/detail/inspection-service-detail-api'
import contractExamples from '../../../../../../contracts/inspection-catalog.examples.json'

describe('inspection service detail api', () => {
  it('loads the dedicated detail endpoint and maps all formal sections', async () => {
    const requests: string[] = []
    const api = createInspectionServiceDetailApi({
      async request<T>(input: { url: string; method: 'GET' }): Promise<T> {
        requests.push(input.url)
        return {
          serviceId: '33333333-3333-3333-3333-333333333333',
          serviceNo: 'INS-SVC-DEV-POWER',
          category: { code: 'POWER', name: '输电巡检' },
          name: '输电线路无人机巡检',
          summary: '杆塔精细巡检 · 导线通道检测 · 缺陷报告',
          coverImage: null,
          price: {
            mode: 'STARTING_PRICE',
            startingAmountMinor: 69900,
            currency: 'CNY',
            unitCode: 'SERVICE',
            unitLabel: '起',
          },
          priceNote: '最终报价以现场评估为准',
          demonstrationMetrics: true,
          completedCount: 8560,
          availability: 'RESERVABLE',
          primaryAction: 'RESERVE',
          provider: {
            providerId: '22222222-2222-2222-2222-222222222222',
            name: '云翼科技',
            logo: null,
            logoAssetCode: 'FEIXIANG_APP_ICON',
            certificationLabel: '企业认证',
            teamDescription: '专业飞手团队',
          },
          capabilities: [
            {
              code: 'MODELING',
              name: '高精度建模',
              description: null,
              iconKey: 'checkbox',
              image: null,
              imageAssetCode: null,
            },
          ],
          inspectionItems: [
            {
              code: 'TOWER',
              name: '杆塔精细巡检',
              description: '精细化巡检杆塔本体',
              iconKey: 'tower',
              image: null,
              imageAssetCode: null,
            },
          ],
          processSteps: [
            {
              code: 'REQUEST',
              name: '提交需求',
              description: null,
              iconKey: null,
              image: null,
              imageAssetCode: null,
            },
          ],
          deliverables: [
            {
              code: 'IMAGES',
              name: '高清巡检影像',
              description: null,
              iconKey: 'image',
              image: null,
              imageAssetCode: 'POWER_LINE_IMAGE',
            },
            {
              code: 'DEFECT_LIST',
              name: '缺陷清单',
              description: null,
              iconKey: 'list',
              image: { url: 'https://media.example.org/defects.jpg' },
              imageAssetCode: 'DEFECT_LIST_IMAGE',
            },
            {
              code: 'REPORT',
              name: '巡检报告',
              description: null,
              iconKey: 'compose',
              image: null,
              imageAssetCode: 'REPORT_COVER_IMAGE',
            },
          ],
          guarantees: [
            {
              code: 'PILOT',
              name: '专业飞手',
              description: null,
              iconKey: 'staff',
              image: null,
              imageAssetCode: null,
            },
          ],
          reviews: {
            average: 4.9,
            total: 328,
            positiveRate: 98,
            tags: [{ code: 'PROFESSIONAL', name: '巡检专业', count: 126 }],
            items: [{
              id: 'review-1',
              maskedUserName: '李**',
              rating: 5,
              date: '2026-07-26',
              content: '飞手很专业，报告清晰。',
              imageAssets: [
                { image: null, assetCode: 'POWER_LINE_IMAGE' },
              ],
            }],
          },
        } as T
      },
    })

    const detail = await api.loadDetail(
      '33333333-3333-3333-3333-333333333333',
      '370500',
    )

    expect(requests).toEqual([
      '/api/v1/inspection/services/33333333-3333-3333-3333-333333333333?cityCode=370500',
    ])
    expect(detail).toMatchObject({
      serviceId: '33333333-3333-3333-3333-333333333333',
      title: '输电线路无人机巡检',
      demonstrationMetrics: true,
      completedCountLabel: '已完成 8,560 单',
      heroImageUrl: '',
      provider: {
        name: '云翼科技',
        logoUrl: '',
      },
      capabilities: [{ label: '高精度建模' }],
      inspectionItems: [{ description: '精细化巡检杆塔本体' }],
      deliverables: [
        { imageUrl: '' },
        { imageUrl: 'https://media.example.org/defects.jpg' },
        { imageUrl: '' },
      ],
      reviewCount: 328,
      positiveRateLabel: '98%好评',
      reviews: [{
        maskedUserName: '李**',
        imageUrls: [],
      }],
    })
  })

  it('keeps an explicit empty review state when the API has no reviews', async () => {
    const api = createInspectionServiceDetailApi({
      async request<T>(): Promise<T> {
        return {
          serviceId: 'service-no-review',
          serviceNo: 'INS-EMPTY',
          completedCount: 0,
          category: { code: 'POWER', name: '输电巡检' },
          name: '输电线路无人机巡检',
          summary: '服务摘要',
          coverImage: null,
          price: { mode: 'PROJECT_QUOTE', currency: 'CNY' },
          priceNote: null,
          availability: 'RESERVABLE',
          primaryAction: 'RESERVE',
          provider: null,
          capabilities: [],
          inspectionItems: [],
          processSteps: [],
          deliverables: [],
          guarantees: [],
          reviews: {
            average: null,
            total: 0,
            positiveRate: null,
            tags: [],
            items: [],
          },
        } as T
      },
    })

    const detail = await api.loadDetail('service-no-review', '370500')

    expect(detail.reviewCount).toBe(0)
    expect(detail.completedCountLabel).toBe('已完成 0 单')
    expect(detail.demonstrationMetrics).toBe(false)
    expect(detail.ratingAverage).toBeNull()
    expect(detail.reviews).toEqual([])
  })

  const detailExamples = (contractExamples as {
    serviceDetailScenarios: Record<string, unknown>
  }).serviceDetailScenarios

  it('maps the T02 pricing, factors, cancellation and cities from the contract example', async () => {
    const api = createInspectionServiceDetailApi({
      async request<T>(): Promise<T> {
        return detailExamples.powerWithPricingAndCancellation as T
      },
    })

    const detail = await api.loadDetail('11111111-1111-4111-8111-111111111111', '370500')

    expect(detail).toMatchObject({
      priceNote: '按线路公里计价，起步价含现场巡检和报告',
      priceFactors: ['线路长度', '杆塔数量', '现场条件'],
      cancellationNote: '方案确认前可取消，不收费；确认后按方案约定',
      serviceCities: [{ code: '370500', name: '东营' }],
      // catalog 1.3.0 (R6-4): the platform reviewed this provider's qualification.
      provider: { certificationLabel: '平台认证服务商', qualificationReviewed: true },
    })
  })

  it('reads a provider without the review field as not reviewed', async () => {
    const example = detailExamples.powerWithPricingAndCancellation as { provider: Record<string, unknown> }
    const { qualificationReviewed: _dropped, ...older } = example.provider
    const api = createInspectionServiceDetailApi({
      async request<T>(): Promise<T> {
        return { ...example, provider: older } as T
      },
    })

    const detail = await api.loadDetail('11111111-1111-4111-8111-111111111111', '370500')

    expect(detail.provider).toMatchObject({ certificationLabel: '平台认证服务商', qualificationReviewed: false })
  })

  it('turns the null pre-migration fields of the legacy example into empty values', async () => {
    const api = createInspectionServiceDetailApi({
      async request<T>(): Promise<T> {
        return detailExamples.legacyProjectQuoteWithoutNewFields as T
      },
    })

    const detail = await api.loadDetail('legacy', '370500')

    expect(detail.priceNote).toBe('')
    expect(detail.priceFactors).toEqual([])
    expect(detail.cancellationNote).toBe('')
    expect(detail.serviceCities).toEqual([])
  })
})
