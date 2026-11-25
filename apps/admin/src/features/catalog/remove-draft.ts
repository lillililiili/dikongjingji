import { ElMessage } from 'element-plus'

import { confirmAction, showError } from '@/core/ui/feedback'

import type { CatalogApi } from './catalog-api'

/** 删除自己的草稿：先确认，成功返回 true；用户取消或失败（已提示）返回 false。商家工作台的列表和详情共用。 */
export async function confirmAndRemoveDraft(
  api: Pick<CatalogApi, 'removeDraft'>,
  product: { productId: string, name: string, version: number },
): Promise<boolean> {
  const confirmed = await confirmAction({
    title: '确认删除草稿',
    message: `删除“${product.name}”？规格和供给一起删掉，不能恢复。`,
    confirmText: '删除',
    danger: true,
  })
  if (!confirmed) return false
  try {
    await api.removeDraft(product.productId, product.version)
    ElMessage.success('草稿已删除')
    return true
  } catch (failure) {
    showError(failure)
    return false
  }
}
