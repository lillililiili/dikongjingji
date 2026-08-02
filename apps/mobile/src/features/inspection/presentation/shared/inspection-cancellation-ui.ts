export function confirmInspectionOrderCancellation(): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '确认取消订单？',
      content: '取消后将停止安排本次服务。',
      cancelText: '暂不取消',
      confirmText: '确认取消',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}
