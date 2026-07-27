# 10｜参考图与资产清单

## 1. UI 参考图

| 编号 | 文件 | 尺寸 | SHA-256 | 用途 |
| --- | --- | --- | --- | --- |
| UI-REF-01 | `references/ui-01-inspection-entry.png` | 941×1672 | `0fcf6be8996ed91459115bc6470db8778209a3bb06b79387c5cbf20749ae3e2a` | INS-M-002 视觉参考 |
| UI-REF-02 | `references/ui-02-inspection-catalog.png` | 941×1672 | `5920026f95cce69dc2fc652222a4096c32bd03ff988eaefd6017bf9dacae0174` | INS-M-003 视觉参考 |

如哈希不一致，Codex 必须先说明参考图发生变化，不得静默使用另一张图。

## 2. 使用规则

参考图允许：

- 视觉结构对比。
- 内容层级确认。
- 颜色、间距和组件关系参考。
- 自动截图回归的人工基线。

参考图禁止：

- 整图作为页面背景。
- 覆盖透明点击热区伪装交互。
- 裁切文字、按钮、价格、分类标签。
- 从截图读取示例数据并写入生产数据库。
- 把截图中的城市、天气、消息角标当实时数据。

## 3. 正式产品需要的独立资产

Codex 在 T01/T02 应检查是否已提供：

- Hero 巡检场景图。
- 电力巡检封面。
- 光伏巡检封面。
- 油气巡检封面。
- 热力巡检封面。
- 园区巡检封面。
- 七个场景图标及“更多”图标。
- 服务保障图标。
- 服务流程图标。

如果没有：

- 可以先用明确的开发占位资源完成布局。
- 必须列入交付阻塞清单。
- 生产包不得把来历不明的网络图片或截图裁切图作为正式资产。

## 4. 资产命名建议

```text
inspection-hero-default.webp
inspection-category-power.svg
inspection-category-photovoltaic.svg
inspection-category-oil-gas.svg
inspection-category-thermal.svg
inspection-category-park.svg
inspection-category-equipment.svg
inspection-category-periodic.svg
inspection-category-more.svg
inspection-service-power-line.webp
inspection-service-photovoltaic-station.webp
inspection-service-gas-pipeline.webp
inspection-service-thermal-pipeline.webp
inspection-service-industrial-park.webp
```

## 5. 图片优化

- 优先 WebP/AVIF，按项目平台兼容策略提供降级。
- API 返回明确宽高。
- 列表使用缩略图，不直接下载原始大图。
- 图片懒加载。
- 设置失败占位与重试。
- 避免将无人机、工业管线主体裁掉。
- alt 文本描述业务场景，不写“图片1”。

## 6. 授权记录

每个正式资产至少记录：

- 来源。
- 权利人。
- 授权类型。
- 使用范围。
- 到期时间（如有）。
- 是否允许商业发布和二次编辑。

无法确认授权的图片只能进入开发环境。

