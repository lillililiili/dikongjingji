import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'
import { formatYuan, minorToYuan, yuanToMinor } from '@/core/ui/money'

import {
  blockersFromDetails,
  createCatalogApi,
  mediaProblem,
  priceUnitsFor,
  type OfferDraft,
  type OpsOffer,
  type ProductDraft,
} from './catalog-api'
import {
  imageUrlProblem,
  pickupProblem,
  priceProblem,
  priceUnitProblem,
  sellingPointProblem,
} from './catalog-rules'
import { emptyOfferForm, offerFormFrom, stockLabel, toOfferDraft } from './offer-form'

const PRODUCT = 'c2000000-0000-4000-8000-000000000101'

describe('金额换算', () => {
  it('元和分互换不出浮点误差；超过两位小数、负数算无效', () => {
    expect(yuanToMinor(19.9)).toBe(1990)
    expect(yuanToMinor('1299')).toBe(129900)
    expect(yuanToMinor(0.07)).toBe(7)
    expect(yuanToMinor(1.005)).toBeNull()
    expect(yuanToMinor(-1)).toBeNull()
    expect(yuanToMinor(null)).toBeNull()
    expect(yuanToMinor('')).toBeNull()
    expect(minorToYuan(738000)).toBe(7380)
    expect(formatYuan(129900)).toBe('¥1,299.00')
    expect(formatYuan(null)).toBe('—')
  })
})

describe('商品接口', () => {
  it('列表带筛选条件，空的不带', async () => {
    const { http, requests } = recordingClient({ page: 1, size: 20, total: 0, hasMore: false, items: [] })
    await createCatalogApi(http).list({ keyword: ' 机器人 ', category: 'ROBOT', saleMode: '', status: 'DRAFT' }, 2, 20)
    expect(requests[0]?.url).toBe('/api/v1/ops/catalog/products?keyword=%E6%9C%BA%E5%99%A8%E4%BA%BA'
      + '&category=ROBOT&status=DRAFT&page=2&size=20')
  })

  it('新建带幂等键；同一个键重试时原样带上', async () => {
    const { http, requests } = recordingClient()
    const api = createCatalogApi(http)
    await api.create({ category: 'ROBOT', name: ' 编程机器人 ', saleMode: 'BUY' }, 'key-1')
    await api.create({ category: 'ROBOT', name: '编程机器人', saleMode: 'BUY' }, 'key-1')
    expect(requests.map((request) => request.headers['Idempotency-Key'])).toEqual(['key-1', 'key-1'])
    expect(requests[0]?.method).toBe('POST')
    expect(jsonBody(requests[0])).toEqual({ category: 'ROBOT', name: '编程机器人', saleMode: 'BUY' })
  })

  it('改资料整份替换：去空白、空行去掉、空的可选项写 null，带版本号', async () => {
    const { http, requests } = recordingClient()
    const draft: ProductDraft = {
      category: 'ROBOT',
      name: ' 套装 ',
      summary: '  ',
      coverUrl: 'https://cdn.example.com/a.jpg',
      experienceBadge: null,
      sortOrder: null,
      sellingPoints: [' 图形化编程 ', ''],
      specs: [{ label: '续航', value: '90 分钟' }, { label: ' ', value: '' }],
      packItems: [{ name: '主机', quantity: 1 }, { name: '', quantity: 1 }],
      gallery: ['https://cdn.example.com/b.jpg', ' '],
      introBlocks: [
        { type: 'TEXT', text: ' 一段介绍 ', imageUrl: 'ignored' },
        { type: 'IMAGE', text: '', imageUrl: 'https://cdn.example.com/c.jpg' },
        { type: 'IMAGE', text: '没图', imageUrl: '' },
      ],
    }
    await createCatalogApi(http).update(PRODUCT, draft, 7)
    expect(requests[0]?.url).toBe(`/api/v1/ops/catalog/products/${PRODUCT}`)
    expect(requests[0]?.method).toBe('PUT')
    expect(jsonBody(requests[0])).toEqual({
      category: 'ROBOT',
      name: '套装',
      summary: null,
      coverUrl: 'https://cdn.example.com/a.jpg',
      experienceBadge: null,
      sortOrder: null,
      sellingPoints: ['图形化编程'],
      specs: [{ label: '续航', value: '90 分钟' }],
      packItems: [{ name: '主机', quantity: 1 }],
      gallery: ['https://cdn.example.com/b.jpg'],
      introBlocks: [
        { type: 'TEXT', text: '一段介绍', imageUrl: null },
        { type: 'IMAGE', text: null, imageUrl: 'https://cdn.example.com/c.jpg' },
      ],
      version: 7,
    })
  })

  it('上下架、规格、供给的路径和请求体', async () => {
    const { http, requests } = recordingClient()
    const api = createCatalogApi(http)
    const draft: OfferDraft = {
      merchantKind: 'LOCAL_STORE',
      cityCode: ' ',
      priceAmountMinor: 129900,
      priceUnit: 'ITEM',
      stockQuantity: 12,
      expressAvailable: false,
      shippingFeeMinor: 0,
      pickupPoint: ' 门店 ',
      deliveryNote: '',
      shippingNote: '',
      fulfilledFrom: '',
    }
    await api.changeStatus(PRODUCT, 'PUBLISH', 3)
    await api.createSku(PRODUCT, { name: ' 教室套装 ', sortOrder: null })
    await api.updateSku(PRODUCT, 'sku-1', { name: '教室套装', sortOrder: 20 }, 'DISABLED', 2)
    await api.createOffer(PRODUCT, 'sku-1', 'org-1', draft, 'key-2')
    await api.updateOffer(PRODUCT, 'offer-1', draft, 4)
    await api.changeOfferStatus(PRODUCT, 'offer-1', 'ACTIVATE', 5)

    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual([
      `POST /api/v1/ops/catalog/products/${PRODUCT}/status`,
      `POST /api/v1/ops/catalog/products/${PRODUCT}/skus`,
      `PUT /api/v1/ops/catalog/products/${PRODUCT}/skus/sku-1`,
      `POST /api/v1/ops/catalog/products/${PRODUCT}/offers`,
      `PUT /api/v1/ops/catalog/products/${PRODUCT}/offers/offer-1`,
      `POST /api/v1/ops/catalog/products/${PRODUCT}/offers/offer-1/status`,
    ])
    expect(jsonBody(requests[0])).toEqual({ action: 'PUBLISH', version: 3 })
    expect(jsonBody(requests[1])).toEqual({ name: '教室套装', sortOrder: null })
    expect(jsonBody(requests[2])).toEqual({ name: '教室套装', sortOrder: 20, status: 'DISABLED', version: 2 })
    const offer = {
      merchantKind: 'LOCAL_STORE', cityCode: null, priceAmountMinor: 129900, priceUnit: 'ITEM', stockQuantity: 12,
      expressAvailable: false, shippingFeeMinor: 0, pickupPoint: '门店', deliveryNote: null, shippingNote: null,
      fulfilledFrom: null,
    }
    expect(requests[3]?.headers['Idempotency-Key']).toBe('key-2')
    expect(jsonBody(requests[3])).toEqual({ skuId: 'sku-1', merchantId: 'org-1', ...offer })
    expect(jsonBody(requests[4])).toEqual({ ...offer, version: 4 })
    expect(jsonBody(requests[5])).toEqual({ action: 'ACTIVATE', version: 5 })
  })

  it('上传图片用 multipart 的 file 字段；上传前挡住太大和不认识的类型', async () => {
    const { http, requests } = recordingClient({ mediaId: 'm', url: 'https://x/y.png', contentType: 'image/png', sizeBytes: 3 })
    const file = new File(['abc'], 'cover.png', { type: 'image/png' })
    await createCatalogApi(http).uploadMedia(file)
    expect(requests[0]?.url).toBe('/api/v1/ops/catalog/media')
    const form = requests[0]?.body as FormData
    expect((form.get('file') as File).name).toBe('cover.png')
    expect(mediaProblem({ size: 3, type: 'image/webp' })).toBeNull()
    expect(mediaProblem({ size: 5 * 1024 * 1024 + 1, type: 'image/png' })).toBe('图片不能超过 5 MB')
    expect(mediaProblem({ size: 3, type: 'image/gif' })).toBe('只能上传 JPEG、PNG 或 WebP 图片')
  })

  it('OPS_PRODUCT_INCOMPLETE 的 details.blockers 只留认识的编码', () => {
    expect(blockersFromDetails({ blockers: ['NO_ACTIVE_SKU', 'WHAT', 3, 'SUMMARY_MISSING'] }))
      .toEqual(['NO_ACTIVE_SKU', 'SUMMARY_MISSING'])
    expect(blockersFromDetails(null)).toEqual([])
  })
})

describe('商品与供给表单规则', () => {
  it('卖点 1–12 字、最多 5 个、不重复', () => {
    expect(sellingPointProblem([], '图形化编程')).toBeNull()
    expect(sellingPointProblem([], '  ')).toBe('请填写卖点')
    expect(sellingPointProblem([], '一'.repeat(13))).toBe('每个卖点最多 12 个字')
    expect(sellingPointProblem(['a', 'b', 'c', 'd', 'e'], 'f')).toBe('最多 5 个卖点')
    expect(sellingPointProblem(['避障练习'], ' 避障练习 ')).toBe('这个卖点已经有了')
  })

  it('价格单位随交易方式：购买、询价按件，租赁按天或半天；询价不标价', () => {
    expect(priceUnitsFor('BUY')).toEqual(['ITEM'])
    expect(priceUnitsFor('INQUIRY')).toEqual(['ITEM'])
    expect(priceUnitsFor('RENT')).toEqual(['DAY', 'HALF_DAY'])
    expect(priceUnitProblem('RENT', 'ITEM')).toBe('价格单位和交易方式不相配')
    expect(priceUnitProblem('BUY', 'ITEM')).toBeNull()
    expect(priceProblem('INQUIRY', 100)).toBe('询价商品不标价')
    expect(priceProblem('BUY', null)).toBeNull()
    expect(priceProblem('BUY', 1.234)).toBe('价格是非负数，最多两位小数')
    expect(priceProblem('RENT', 260)).toBeNull()
  })

  it('不能快递时自提点必填', () => {
    expect(pickupProblem(false, ' ')).toBe('不能快递时要填自提点')
    expect(pickupProblem(false, '示例门店')).toBeNull()
    expect(pickupProblem(true, '')).toBeNull()
  })

  it('图片地址只认 https 或开发环境上传得到的地址', () => {
    expect(imageUrlProblem('https://cdn.example.com/a.jpg')).toBeNull()
    expect(imageUrlProblem('')).toBeNull()
    expect(imageUrlProblem('http://127.0.0.1:18420/api/v1/mall/media/products/a.png')).toBeNull()
    expect(imageUrlProblem('http://example.com/a.jpg')).toBe('只能填上传得到的地址或 https 图片地址')
    expect(imageUrlProblem('/static/a.jpg')).toBe('只能填上传得到的地址或 https 图片地址')
  })

  it('供给表单按元填、按分提交；询价商品不带价格；库存空着是不限', () => {
    const form = { ...emptyOfferForm('BUY', 'sku-1'), priceYuan: 1299, shippingFeeYuan: 12.5, stockQuantity: null }
    expect(toOfferDraft(form, 'BUY')).toMatchObject({ priceAmountMinor: 129900, shippingFeeMinor: 1250, stockQuantity: null })
    expect(toOfferDraft(form, 'INQUIRY').priceAmountMinor).toBeNull()
    expect(emptyOfferForm('RENT').priceUnit).toBe('DAY')

    const offer = { priceAmountMinor: 738000, shippingFeeMinor: 0, cityCode: null, pickupPoint: null, deliveryNote: null,
      shippingNote: null, fulfilledFrom: null, stockQuantity: 3 } as unknown as OpsOffer
    expect(offerFormFrom(offer)).toMatchObject({ priceYuan: 7380, shippingFeeYuan: 0, cityCode: '', stockQuantity: 3 })
    expect(stockLabel(null)).toBe('不限')
    expect(stockLabel(0)).toBe('缺货')
    expect(stockLabel(12)).toBe('12 件')
  })
})
