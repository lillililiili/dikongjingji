// 原型主题照片仅用于预览。公开的游戏链接使用项目内自绘的机器人和无人机图标。
const preview = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

export const robotArtwork = preview ? '/static/images/home/robot-rover.jpg' : '/static/images/play/avatar-robot.svg'
export const droneArtwork = preview ? '/static/images/home/hero-world.jpg' : '/static/images/play/avatar-drone.svg'
export const workshopArtwork = preview ? '/static/images/home/robot-workshop.jpg' : '/static/images/play/avatar-robot.svg'
