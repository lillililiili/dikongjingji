import type { PlatformMallContent } from '../../domain/platform-mall-content'

const image = (name: string): string =>
  `/static/images/inspection/platform/${name}`

export const DEVELOPMENT_PLATFORM_MALL_CONTENT = {
  hero: {
    imageUrl: image('mall-hero-v2.png'),
    title: '智能装备',
    highlight: '生活更精彩',
    description: '一站购齐无人机、机器人及低空服务',
    highlights: ['正品保障', '专业选型', '售后无忧'],
    actionLabel: '查看精选商品',
  },
  categories: [
    { code: 'DRONE', title: '无人机', subtitle: '消费航拍·行业应用', imageUrl: image('home-drone-flight-v2.png') },
    { code: 'ROBOT', title: '机器人', subtitle: '配送·巡检·四足', imageUrl: image('home-robot-service-v2.png') },
    { code: 'VEHICLE', title: '无人车', subtitle: '配送·巡逻·清扫', imageUrl: image('unmanned-vehicle-v2.png') },
    { code: 'PARTS', title: '配件耗材', subtitle: '电池·桨叶·相机', imageUrl: image('remote-controller-v2.png') },
    { code: 'RENTAL', title: '设备租赁', subtitle: '短租长租·灵活便捷', imageUrl: image('home-equipment-rental-v2.png') },
    { code: 'SOFTWARE', title: '软件服务', subtitle: '航线规划·数据平台', imageUrl: image('ai-inspection-module-v2.png') },
    { code: 'INSURANCE', title: '保险', subtitle: '设备险·责任险', imageUrl: image('mine-feature-certification-v2.png') },
    { code: 'TRAINING', title: '培训课程', subtitle: '飞手培训·认证课程', imageUrl: image('mine-feature-qualifications-v2.png') },
  ],
  products: [
    { id: 'product-mini', title: '轻巧航拍无人机', subtitle: '轻于249g 4K航拍', imageUrl: image('home-drone-flight-v2.png'), priceLabel: '¥4799', salesLabel: '已售 1.2万+', badges: ['热销', '官方正品'] },
    { id: 'product-industry', title: '行业应用无人机', subtitle: '农业植保 行业旗舰', imageUrl: image('multi-drone-v2.png'), priceLabel: '¥69999', salesLabel: '已售 860+', badges: ['企业优选', '包邮'] },
    { id: 'product-quadruped', title: '智能四足机器人', subtitle: '灵活稳定 智能避障', imageUrl: image('quadruped-robot-v2.png'), priceLabel: '¥15999', salesLabel: '已售 3200+', badges: ['现货速发'] },
    { id: 'product-delivery', title: '配送机器人 X3', subtitle: '室内外配送 无人送达', imageUrl: image('delivery-robot-v2.png'), priceLabel: '¥89999', salesLabel: '已售 560+', badges: ['新品', '送货上门'] },
    { id: 'product-camera', title: '专业云台相机', subtitle: '变焦 热成像 激光测距', imageUrl: image('remote-controller-v2.png'), priceLabel: '¥65999', salesLabel: '已售 380+', badges: ['行业限定'] },
  ],
  rentals: [
    { id: 'rental-aerial', title: '航拍无人机', subtitle: '按天租赁', imageUrl: image('home-drone-flight-v2.png'), priceLabel: '¥120/天' },
    { id: 'rental-industry', title: '行业无人机', subtitle: '按天租赁', imageUrl: image('multi-drone-v2.png'), priceLabel: '¥380/天' },
    { id: 'rental-quadruped', title: '四足机器人', subtitle: '按天租赁', imageUrl: image('quadruped-robot-v2.png'), priceLabel: '¥200/天' },
    { id: 'rental-delivery', title: '配送机器人', subtitle: '按天租赁', imageUrl: image('delivery-robot-v2.png'), priceLabel: '¥350/天' },
    { id: 'rental-vehicle', title: '无人清扫车', subtitle: '按天租赁', imageUrl: image('unmanned-vehicle-v2.png'), priceLabel: '¥800/天' },
  ],
  solutions: [
    { id: 'solution-agriculture', title: '智慧农业', subtitle: '植保监测·农田管理', imageUrl: image('home-enterprise-service-v2.png') },
    { id: 'solution-power', title: '电力巡检', subtitle: '输电线路·设备巡检', imageUrl: image('home-inspection-service-v2.png') },
    { id: 'solution-rescue', title: '应急救援', subtitle: '搜救侦察·通信保障', imageUrl: image('multi-drone-v2.png') },
    { id: 'solution-city', title: '城市管理', subtitle: '巡逻执法·环保监测', imageUrl: image('home-city-service-v2.png') },
  ],
  brands: ['大疆创新', '极飞科技', '道通智能', '海康机器人', '宇树科技'],
  stores: [
    { id: 'store-drone', title: '无人机官方旗舰店', subtitle: '官方认证｜极速发货', imageUrl: image('home-drone-flight-v2.png'), priceLabel: '进店' },
    { id: 'store-robot', title: '机器人官方店', subtitle: '官方认证｜正品保障', imageUrl: image('home-robot-service-v2.png'), priceLabel: '进店' },
    { id: 'store-industry', title: '行业设备优选店', subtitle: '企业采购｜方案服务', imageUrl: image('home-enterprise-service-v2.png'), priceLabel: '进店' },
  ],
} satisfies PlatformMallContent
