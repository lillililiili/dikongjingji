import type { PlatformDiscoverContent } from '../../domain/platform-discover-content'

const image = (name: string): string =>
  `/static/images/inspection/platform/${name}`

export const DEVELOPMENT_PLATFORM_DISCOVER_CONTENT = {
  hero: {
    imageUrl: image('discover-hero-v2.png'),
    title: '发现身边',
    highlight: '低空新体验',
    description: '探索城市新视角 · 解锁更多精彩体验',
    highlights: ['本地活动', '精选攻略', '兴趣社群'],
    actionLabel: '发现附近精彩',
  },
  activities: [
    { id: 'activity-ball', title: '无人机足球赛', subtitle: '奥体中心体育场', imageUrl: image('multi-drone-v2.png'), tag: '进行中', meta: '05.25 周日 14:00', metric: '86', priceLabel: '¥39 起' },
    { id: 'activity-photo', title: '航拍摄影大赛', subtitle: '大明湖景区', imageUrl: image('home-city-service-v2.png'), tag: '报名中', meta: '05.30 截止报名', metric: '102', priceLabel: '免费' },
    { id: 'activity-balloon', title: '低空观光节', subtitle: '城市湖畔', imageUrl: image('home-low-altitude-tour-v2.png'), tag: '即将开始', meta: '06.01—06.02', metric: '59', priceLabel: '¥128 起' },
    { id: 'activity-robot', title: '机器人体验营', subtitle: '城市科技馆', imageUrl: image('home-robot-service-v2.png'), tag: '报名中', meta: '05.26 周六 10:30', metric: '73', priceLabel: '¥69 起' },
  ],
  stories: [
    { id: 'story-sunset', title: '城市落日航拍，解锁绝美风光', subtitle: '航拍小飞手', imageUrl: image('home-aerial-photo-v2.png'), tag: '推荐', metric: '128' },
    { id: 'story-tour', title: '湖边低空游玩攻略，这份路线请收好', subtitle: '泉城玩家', imageUrl: image('home-low-altitude-tour-v2.png'), tag: '攻略', metric: '86' },
    { id: 'story-night', title: '无人机点亮城市夜空，全程记录', subtitle: '城市低空服务', imageUrl: image('home-city-service-v2.png'), tag: '案例', metric: '64' },
    { id: 'story-code', title: '带娃体验机器人编程，孩子超爱', subtitle: '科技奶爸', imageUrl: image('home-robot-service-v2.png'), tag: '体验', metric: '52' },
  ],
  communities: [
    { id: 'community-aerial', name: '城市航拍联盟', memberLabel: '2.3万 成员', imageUrl: image('home-aerial-photo-v2.png') },
    { id: 'community-robot', name: '机器人玩家部落', memberLabel: '1.8万 成员', imageUrl: image('home-robot-service-v2.png') },
    { id: 'community-tour', name: '低空旅行家', memberLabel: '8560 成员', imageUrl: image('home-low-altitude-tour-v2.png') },
    { id: 'community-event', name: '赛事活动控', memberLabel: '1.2万 成员', imageUrl: image('home-city-service-v2.png') },
  ],
} satisfies PlatformDiscoverContent
