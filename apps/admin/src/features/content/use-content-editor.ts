import { ElMessageBox } from 'element-plus'
import { computed, ref, shallowRef, type Ref, type ShallowRef } from 'vue'

import { errorMessage } from '@/core/http/api-error'
import { useChangeHistory } from '@/core/ops/use-change-history'
import { describeMissing, type OpsChange } from '@/core/ops/ops-common'
import { notifyRecorded, showError } from '@/core/ui/feedback'

import { MISSING_LABELS } from './content-rules'

export interface Editable {
  version: number
}

export interface ContentEditor<V extends Editable> {
  entity: ShallowRef<V | null>
  loading: Ref<boolean>
  dirty: Readonly<Ref<boolean>>
  markSaved: () => void
  busy: Ref<boolean>
  error: Ref<string>
  changes: Ref<OpsChange[]>
  changesError: Ref<string>
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
  form: object,
): ContentEditor<V> {
  const entity = shallowRef(null) as ShallowRef<V | null>
  const savedForm = ref('')
  const dirty = computed(() => entity.value !== null && JSON.stringify(form) !== savedForm.value)
  function markSaved(): void {
    savedForm.value = JSON.stringify(form)
  }

  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const { changes, loading: changesLoading, error: changesError, load: refreshChanges } = useChangeHistory(readChanges)

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

  return { entity, dirty, markSaved, loading, busy, error, changes, changesError, changesLoading, load, refreshChanges, run }
}
