import { ElMessageBox } from 'element-plus'
import { ref, shallowRef, type Ref, type ShallowRef } from 'vue'

import { errorMessage } from '@/core/http/api-error'
import { describeMissing, type OpsChange } from '@/core/ops/ops-common'
import { notifyRecorded, showError } from '@/core/ui/feedback'

import { MISSING_LABELS } from './content-rules'

export interface Editable {
  version: number
}

export interface ContentEditor<V extends Editable> {
  entity: ShallowRef<V | null>
  loading: Ref<boolean>
  busy: Ref<boolean>
  error: Ref<string>
  changes: Ref<OpsChange[]>
  changesLoading: Ref<boolean>
  load: (id: string) => Promise<V | null>
  refreshChanges: (id: string) => Promise<void>
  /** 跑一个写操作：成功后换成新数据、提示“已记录”、刷新改动记录；上线前缺必填时把缺的项列出来。 */
  run: (action: () => Promise<V>, done: string, idOf: (entity: V) => string) => Promise<V | null>
}

/** 图文、视频、专题三个编辑页共用的读取、写操作和改动记录。 */
export function useContentEditor<V extends Editable>(
  read: (id: string) => Promise<V>,
  readChanges: (id: string) => Promise<OpsChange[]>,
): ContentEditor<V> {
  const entity = shallowRef(null) as ShallowRef<V | null>
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const changes = ref<OpsChange[]>([])
  const changesLoading = ref(false)

  async function refreshChanges(id: string): Promise<void> {
    changesLoading.value = true
    try {
      changes.value = await readChanges(id)
    } catch {
      changes.value = []
    } finally {
      changesLoading.value = false
    }
  }

  async function load(id: string): Promise<V | null> {
    loading.value = true
    error.value = ''
    try {
      entity.value = await read(id)
      void refreshChanges(id)
      return entity.value
    } catch (failure) {
      error.value = errorMessage(failure)
      return null
    } finally {
      loading.value = false
    }
  }

  async function run(action: () => Promise<V>, done: string, idOf: (entity: V) => string): Promise<V | null> {
    if (busy.value) return null
    busy.value = true
    try {
      const next = await action()
      entity.value = next
      notifyRecorded(done, '改动记录')
      void refreshChanges(idOf(next))
      return next
    } catch (failure) {
      const missing = describeMissing(failure, MISSING_LABELS)
      if (missing) {
        await ElMessageBox.alert(missing, '还不能上线', { type: 'warning', confirmButtonText: '知道了' }).catch(() => undefined)
      } else {
        showError(failure)
      }
      return null
    } finally {
      busy.value = false
    }
  }

  return { entity, loading, busy, error, changes, changesLoading, load, refreshChanges, run }
}
