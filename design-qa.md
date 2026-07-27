# M01 视觉验收记录

## 本轮范围

- 页面：`/pages/service/inspection/index`
- 整改区域：底部五项导航栏
- 视觉基准：`C:\Users\ADMIN~1\AppData\Local\Temp\codex-clipboard-83067b71-247e-4f16-8849-6d431f9bc533.png`
- 用户反馈图：`C:\Users\ADMIN~1\AppData\Local\Temp\codex-clipboard-41cdf41c-91d4-42bb-9463-aeb223f2b095.png`
- 状态：M01 巡检服务页，服务项激活

## 尺寸与测量

- 基准整图：941 × 1672；底栏约 941 × 107
- 用户反馈底栏：1008 × 125
- 浏览器轮次整图：1139 × 641；聚焦底栏约 1139 × 73
- 浏览器运行时：约 1153 × 648，DPR 1.665
- 测量结论：反馈图中的底栏约高 13px，图标和文字整体约低 17–18px；发现图标约大 7px，商城图标约大 12px且造型错误

## 视觉真值与证据

- 基准整图：`output/visual/m01-v4-bottom-nav/reference-full.png`
- 基准底栏：`output/visual/m01-v4-bottom-nav/reference-bottom-nav.png`
- 用户反馈：`output/visual/m01-v4-bottom-nav/user-reported-before.png`
- 第一轮整图：`output/visual/m01-v4-bottom-nav/round-1-full.jpg`
- 第一轮底栏：`output/visual/m01-v4-bottom-nav/round-1-bottom-nav.png`
- 第二轮整图：`output/visual/m01-v4-bottom-nav/round-2-full.jpg`
- 第二轮底栏：`output/visual/m01-v4-bottom-nav/round-2-bottom-nav.png`
- 聚焦对照：`output/visual/m01-v4-bottom-nav/focused-comparison.png`
- 轮次历史：`output/visual/m01-v4-bottom-nav/round-history.png`

## 比较历史

1. 第一轮：移除房子、方格、圆形、方框、人形等文本占位符，改用真实图标组件。严重级别 P1。
2. 第二轮：发现商城使用了店铺造型，与基准购物袋不一致；替换为 Phosphor 正式购物袋资产，并生成灰色、橙色两态。严重级别 P2。
3. 代码收口：按像素测量统一 30px 常规图标、14px 服务四点图标，并调整底栏高度、项目高度和标签字号比例。
4. 最终浏览器复拍被浏览器安全策略阻止，当前 CSS 状态未取得新截图，因此不能标记视觉验收通过。

## 必查面

| 检查面 | 当前结果 |
| --- | --- |
| 字体与排版 | 标签文案、字重保持；字号由页面比例规则控制 |
| 间距与布局节奏 | 已按反馈图测量修正底栏高度和纵向位置 |
| 颜色与设计变量 | 激活态 `#ff5a00`，未激活态 `#5d626a` |
| 图片质量与资产 | 商城使用 81 × 81 RGBA 购物袋资产，含灰色和橙色两态 |
| 文案 | 首页、服务、发现、商城、我的未改动 |
| 全视图与聚焦对比 | 已完成两轮；最终代码状态待复拍 |

## 自动化验证

- M01 定向视觉契约测试：9/9
- 全量测试、类型检查、H5 构建：以本轮最终命令输出为准
- 页面与静态资产健康检查：以本轮最终 HTTP 检查为准

## 阻塞项

浏览器安全策略阻止了最终自动复拍。需要用户刷新当前页面确认底栏，或在浏览器能力恢复后补拍同状态截图并再次并排比较。

final result: blocked
