import type { PlatformServiceChannelContent } from '../../domain/platform-service-content'

const image = (name: string): string =>
  `/static/images/inspection/platform/${name}`

export const DEVELOPMENT_PLATFORM_SERVICE_CONTENT = [
  {
    code: 'AERIAL',
    searchPlaceholder: '搜索航拍服务或场景',
    hero: {
      imageUrl: image('aerial-service-hero-v3.png'),
      title: '专业航拍',
      highlight: '记录每一种精彩',
      description: '城市宣传 · 活动跟拍 · 工程记录 · 个性定制',
      highlights: ['专业团队', '高清交付', '合规飞行'],
      actionLabel: '查找航拍服务',
    },
    scenarios: [
      { code: 'CITY', title: '城市宣传', subtitle: '城市形象 品牌展示', imageUrl: image('home-city-service-v2.png') },
      { code: 'EVENT', title: '活动跟拍', subtitle: '庆典赛事 全程记录', imageUrl: image('home-aerial-photo-v2.png') },
      { code: 'PROPERTY', title: '房产航拍', subtitle: '楼盘园区 空间展示', imageUrl: image('home-enterprise-service-v2.png') },
      { code: 'PROJECT', title: '工程进度', subtitle: '施工节点 定期记录', imageUrl: image('home-inspection-service-v2.png') },
      { code: 'TOUR', title: '文旅景区', subtitle: '山河景观 沉浸呈现', imageUrl: image('home-low-altitude-tour-v2.png') },
      { code: 'WEDDING', title: '婚礼航拍', subtitle: '高光时刻 浪漫珍藏', imageUrl: image('home-aerial-photo-v2.png') },
      { code: 'RURAL', title: '农林影像', subtitle: '田园林区 全景采集', imageUrl: image('home-drone-flight-v2.png') },
      { code: 'CUSTOM', title: '定制航拍', subtitle: '按需策划 专属交付', imageUrl: image('more-scenes-v2.png') },
    ],
    capabilityTitle: '航拍服务保障',
    capabilitySubtitle: '专业团队 · 透明交付',
    capabilities: [
      { code: 'PILOT', title: '认证飞手', description: '持证上岗 专业可靠' },
      { code: 'COMPLIANCE', title: '飞行合规', description: '批报备案 合规作业' },
      { code: 'PRICE', title: '费用透明', description: '明码标价 无隐形费' },
      { code: 'DELIVERY', title: '成片保障', description: '高清成片 准时交付' },
    ],
    recommendationTitle: '精选航拍服务',
    recommendationSubtitle: '真实案例 · 支持定制',
    recommendations: [
      { id: 'aerial-city', title: '城市形象航拍', subtitle: '4K成片｜专业调色', imageUrl: image('home-city-service-v2.png'), priceLabel: '¥1299 起/次', rating: 4.9, salesLabel: '已售 860+', actionLabel: '查看详情' },
      { id: 'aerial-event', title: '大型活动航拍', subtitle: '双机位｜全程记录', imageUrl: image('home-aerial-photo-v2.png'), priceLabel: '¥1599 起/场', rating: 4.9, salesLabel: '已售 520+', actionLabel: '查看详情' },
      { id: 'aerial-property', title: '楼盘园区航拍', subtitle: '全景展示｜快速交付', imageUrl: image('home-enterprise-service-v2.png'), priceLabel: '¥899 起/次', rating: 4.8, salesLabel: '已售 1260+', actionLabel: '查看详情' },
      { id: 'aerial-tour', title: '景区宣传航拍', subtitle: '创意脚本｜电影画面', imageUrl: image('home-low-altitude-tour-v2.png'), priceLabel: '¥1999 起/次', rating: 4.9, salesLabel: '已售 430+', actionLabel: '查看详情' },
    ],
  },
] satisfies PlatformServiceChannelContent[]
