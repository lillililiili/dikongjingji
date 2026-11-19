<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import { resolveActivityIntent } from '../../application/activity-intent'
import { ACTIVITY_PREVIEW_ENABLED } from '../../application/activity-preview'
import { childBirthMonthRange, formatBirthMonth } from '../../application/enrollment-view'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { Participant } from '../../domain/enrollment'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Opened from a form (the sign-up form, or the venue booking's confirm page): adding someone offers
  // the way back to it with these words.
  returnLabel: string | null
}>()

const emit = defineEmits<{
  back: []
  login: []
}>()

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready' }
  | { kind: 'error' }

type SheetMode = 'closed' | 'add' | 'edit'

const NAME_MAX_LENGTH = 20

const state = ref<ListState>({ kind: 'loading' })
const people = ref<Participant[]>([])
const toast = ref('')
const toastOffersReturn = ref(false)

const sheetMode = ref<SheetMode>('closed')
const editing = ref<Participant | null>(null)
const formName = ref('')
const formBirthMonth = ref('')
const formConsent = ref(false)
const deleteAsked = ref(false)
const sheetError = ref('')
const saving = ref(false)
let requestVersion = 0

const self = computed(() => people.value.find((person) => person.relation === 'SELF') ?? null)
const children = computed(() => people.value.filter((person) => person.relation === 'CHILD'))
const birthRange = computed(() => childBirthMonthRange(new Date()))
const sheetTitle = computed(() => {
  if (sheetMode.value === 'add') return '添加参与人'
  return editing.value?.relation === 'SELF' ? '编辑本人信息' : '编辑参与人'
})
const editingChild = computed(() => sheetMode.value === 'edit' && editing.value?.relation === 'CHILD')
const canSave = computed(() => {
  if (saving.value || formName.value.trim() === '') return false
  return sheetMode.value !== 'add' || (formBirthMonth.value !== '' && formConsent.value)
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function messageOf(error: unknown, fallback: string): string {
  return error instanceof ApiRequestError ? error.message : fallback
}

function valueOf(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await enrollmentApi.loadParticipants(token)
    if (requested !== requestVersion) return
    people.value = loaded
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

function showToast(text: string, offersReturn = false): void {
  toast.value = text
  toastOffersReturn.value = offersReturn && props.returnLabel !== null
}

function openAdd(): void {
  sheetMode.value = 'add'
  editing.value = null
  formName.value = ''
  formBirthMonth.value = ''
  formConsent.value = false
  deleteAsked.value = false
  sheetError.value = ''
  toast.value = ''
}

function openEdit(person: Participant): void {
  sheetMode.value = 'edit'
  editing.value = person
  formName.value = person.name
  formBirthMonth.value = person.birthMonth ?? ''
  formConsent.value = false
  deleteAsked.value = false
  sheetError.value = ''
  toast.value = ''
}

function closeSheet(): void {
  if (saving.value) return
  sheetMode.value = 'closed'
}

function replacePerson(next: Participant): void {
  people.value = people.value.map((person) =>
    person.participantId === next.participantId ? next : person)
}

async function save(): Promise<void> {
  const token = props.accessToken
  if (!canSave.value || !token) return
  const name = formName.value.trim()
  saving.value = true
  sheetError.value = ''
  try {
    if (sheetMode.value === 'add') {
      const added = await enrollmentApi.addChild(token, {
        name,
        birthMonth: formBirthMonth.value,
        guardianConfirmed: true,
      })
      people.value = [...people.value, added]
      showToast(`已添加 ${added.name}`, true)
    } else if (editing.value) {
      replacePerson(await enrollmentApi.renameParticipant(token, editing.value.participantId, name))
      showToast('已保存')
    }
    sheetMode.value = 'closed'
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    sheetError.value = messageOf(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

async function confirmDelete(): Promise<void> {
  const token = props.accessToken
  const target = editing.value
  if (!token || !target || saving.value) return
  saving.value = true
  try {
    await enrollmentApi.deleteChild(token, target.participantId)
    people.value = people.value.filter((person) => person.participantId !== target.participantId)
    sheetMode.value = 'closed'
    showToast(`已删除 ${target.name}`)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    sheetError.value = messageOf(error, '删除失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// The switch only moves once the server has recorded the change.
async function togglePhotoUse(person: Participant): Promise<void> {
  const token = props.accessToken
  if (!token) return
  try {
    replacePerson(await enrollmentApi.setPhotoUse(
      token, person.participantId, !person.photoUseAuthorized))
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: messageOf(error, '没有保存，请稍后重试'), icon: 'none' })
  }
}

function readPrivacyRules(): void {
  const resolution = resolveActivityIntent(
    { type: 'READ_AGREEMENT', document: 'CHILD_PRIVACY_RULES' },
    ACTIVITY_PREVIEW_ENABLED,
  )
  if (resolution.action === 'SHOW_NOTICE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="participants" data-testid="participants">
    <header class="participants__header">
      <button
        class="participants__back"
        data-testid="participants-back"
        aria-label="返回"
        @click="emit('back')"
      >
        <UniIcons type="left" size="24" color="#202c42" />
      </button>
      <text class="participants__title">参与人</text>
    </header>

    <view
      v-if="state.kind === 'loading'"
      class="participants__body"
      data-testid="participants-loading"
      aria-label="正在加载"
    >
      <view class="participants__skeleton" />
      <view class="participants__skeleton" />
    </view>

    <view
      v-else-if="state.kind === 'error'"
      class="participants__state"
      data-testid="participants-error"
    >
      <text class="participants__state-title">加载失败</text>
      <text class="participants__state-text">请检查网络后重试</text>
      <button class="participants__state-button" data-testid="participants-retry" @click="load">
        重试
      </button>
    </view>

    <view v-else class="participants__body">
      <view class="participants__intro">
        <UniIcons class="participants__intro-icon" type="auth" size="18" color="#159a70" />
        <text class="participants__intro-text">参与人用于活动报名，与登录账号分开管理；未成年人由监护人维护。</text>
      </view>

      <view v-if="self" class="participants__card" data-testid="participants-self">
        <view class="participants__row">
          <text class="participants__avatar participants__avatar--self" aria-hidden="true">
            {{ self.name.slice(0, 1) }}
          </text>
          <view class="participants__who">
            <view class="participants__name-line">
              <text class="participants__name">{{ self.name }}</text>
              <text class="participants__tag">本人</text>
            </view>
            <text class="participants__sub">账户持有人</text>
          </view>
          <button
            class="participants__edit"
            :data-testid="`participant-edit-${self.participantId}`"
            aria-label="编辑本人信息"
            @click="openEdit(self)"
          >
            编辑
          </button>
        </view>
      </view>

      <view class="participants__section">
        <text class="participants__section-title" data-testid="participants-children-title">
          子女 · {{ children.length }} 人
        </text>
        <text class="participants__section-note">监护人：本人</text>
      </view>

      <view
        v-for="(child, index) in children"
        :key="child.participantId"
        class="participants__card participants__card--child"
        :data-testid="`participant-${child.participantId}`"
        :data-name="child.name"
      >
        <view class="participants__row">
          <text
            :class="['participants__avatar', index % 2 === 0 ? 'participants__avatar--blue' : 'participants__avatar--green']"
            aria-hidden="true"
          >
            {{ child.name.slice(-1) }}
          </text>
          <view class="participants__who">
            <view class="participants__name-line">
              <text class="participants__name">{{ child.name }}</text>
              <text class="participants__tag participants__tag--minor">未成年 · {{ child.age }} 岁</text>
            </view>
            <view class="participants__confirmed">
              <text>监护人确认</text>
              <UniIcons type="checkmarkempty" size="12" color="#159a70" />
            </view>
          </view>
          <button
            class="participants__edit"
            :data-testid="`participant-edit-${child.participantId}`"
            :aria-label="`编辑${child.name}`"
            @click="openEdit(child)"
          >
            编辑
          </button>
        </view>
        <view class="participants__photo">
          <text class="participants__photo-label">活动照片可公开使用</text>
          <text
            :class="['participants__photo-state', { 'participants__photo-state--on': child.photoUseAuthorized }]"
          >
            {{ child.photoUseAuthorized ? '已授权' : '未授权' }}
          </text>
          <button
            class="participants__switch"
            :data-testid="`participant-photo-${child.participantId}`"
            role="switch"
            :aria-checked="child.photoUseAuthorized"
            :aria-label="`${child.name}的活动照片可公开使用`"
            @click="togglePhotoUse(child)"
          >
            <view :class="['participants__track', { 'participants__track--on': child.photoUseAuthorized }]">
              <view class="participants__knob" />
            </view>
          </button>
        </view>
      </view>

      <text v-if="children.length === 0" class="participants__empty" data-testid="participants-no-children">
        还没有添加子女。给未成年人报名前，先在这里添加。
      </text>
      <text v-else class="participants__footnote">照片公开使用需单独授权，可随时撤回</text>

      <view class="participants__card participants__card--notes">
        <text class="participants__notes-title">资料使用说明</text>
        <view class="participants__note">
          <text class="participants__note-dot">·</text>
          <text class="participants__note-text">只保存姓名和出生年月，不收集证件号码；未成年人信息仅监护人可见</text>
        </view>
        <view class="participants__note">
          <text class="participants__note-dot">·</text>
          <text class="participants__note-text">主办方仅在报名的活动中查看签到所需的姓名</text>
        </view>
      </view>
    </view>

    <view v-if="state.kind === 'ready'" class="participants__bar">
      <view v-if="toast" class="participants__toast" role="status" data-testid="participants-toast">
        <UniIcons type="checkmarkempty" size="18" color="#159a70" />
        <text class="participants__toast-text">{{ toast }}</text>
        <button
          v-if="toastOffersReturn"
          class="participants__toast-link"
          data-testid="participants-back-to-enroll"
          @click="emit('back')"
        >
          {{ returnLabel }} ›
        </button>
      </view>
      <button class="participants__add" data-testid="participants-add" @click="openAdd">
        ＋ 添加参与人
      </button>
    </view>

    <view
      v-if="sheetMode !== 'closed'"
      class="participants__mask"
      @click.self="closeSheet"
    >
      <view class="participants__sheet" role="dialog" :aria-label="sheetTitle" data-testid="participant-sheet">
        <view class="participants__sheet-head">
          <text class="participants__sheet-title">{{ sheetTitle }}</text>
          <button class="participants__sheet-close" aria-label="关闭" @click="closeSheet">
            <UniIcons type="closeempty" size="20" color="#536178" />
          </button>
        </view>

        <view class="participants__fields">
          <view class="participants__field">
            <text class="participants__field-label">姓名</text>
            <input
              class="participants__input"
              data-testid="participant-name"
              type="text"
              aria-label="姓名"
              placeholder="请填写姓名"
              :maxlength="NAME_MAX_LENGTH"
              :value="formName"
              @input="formName = valueOf($event)"
              @blur="formName = valueOf($event)"
            />
          </view>
          <view class="participants__field">
            <text class="participants__field-label">关系</text>
            <text class="participants__field-value">
              {{ sheetMode === 'edit' && editing?.relation === 'SELF' ? '本人' : '子女' }}
            </text>
          </view>
          <picker
            v-if="sheetMode === 'add'"
            mode="date"
            fields="month"
            :value="formBirthMonth || birthRange.end"
            :start="birthRange.start"
            :end="birthRange.end"
            @change="formBirthMonth = valueOf($event)"
          >
            <view class="participants__field participants__field--last" data-testid="participant-birth">
              <text class="participants__field-label">出生年月</text>
              <text
                :class="['participants__field-value', { 'participants__field-value--empty': !formBirthMonth }]"
              >
                {{ formBirthMonth ? formatBirthMonth(formBirthMonth) : '请选择' }}
              </text>
              <UniIcons type="right" size="14" color="#91a0b4" />
            </view>
          </picker>
          <view v-else-if="editingChild && editing?.birthMonth" class="participants__field participants__field--last">
            <text class="participants__field-label">出生年月</text>
            <text class="participants__field-value">
              {{ formatBirthMonth(editing.birthMonth) }}（{{ editing.age }} 岁）
            </text>
          </view>
        </view>

        <text v-if="sheetError" class="participants__sheet-error" data-testid="participant-sheet-error">
          {{ sheetError }}
        </text>

        <view v-if="sheetMode === 'add'" class="participants__consent">
          <button
            class="participants__consent-toggle"
            data-testid="participant-consent"
            role="checkbox"
            :aria-checked="formConsent"
            aria-label="监护人确认"
            @click="formConsent = !formConsent"
          >
            <view :class="['participants__box', { 'participants__box--on': formConsent }]">
              <UniIcons v-if="formConsent" type="checkmarkempty" size="12" color="#ffffff" />
            </view>
          </button>
          <text class="participants__consent-text">
            <text @click="formConsent = !formConsent">我是该未成年人的监护人，已阅读</text>
            <text class="participants__link" data-testid="participant-privacy-rules" @click="readPrivacyRules">
              《儿童个人信息保护规则》
            </text>
          </text>
        </view>

        <button
          v-if="editingChild && !deleteAsked"
          class="participants__delete"
          data-testid="participant-delete"
          @click="deleteAsked = true"
        >
          删除参与人
        </button>
        <view v-if="editingChild && deleteAsked" class="participants__delete-ask" role="status">
          <text class="participants__delete-text">确定删除 {{ editing?.name }}？删除后报名时不能再选择</text>
          <button class="participants__keep" @click="deleteAsked = false">保留</button>
          <button
            class="participants__delete-confirm"
            data-testid="participant-delete-confirm"
            @click="confirmDelete"
          >
            确认删除
          </button>
        </view>

        <view class="participants__sheet-actions">
          <button class="participants__cancel" @click="closeSheet">取消</button>
          <button
            :class="['participants__save', { 'participants__save--off': !canSave }]"
            data-testid="participant-save"
            :disabled="!canSave"
            @click="save"
          >
            保存
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Participants (390 wide). */
.participants {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.participants__header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  min-height: calc(60px + var(--status-bar-height));
  padding: var(--status-bar-height) 8px 0;
  align-items: center;
  gap: 4px;
  background: #ffffff;
  box-sizing: border-box;
}

.participants__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.participants__title {
  font-size: 18px;
  font-weight: 700;
}

.participants__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 16px;
}

.participants__intro {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #e2f6ed;
}

.participants__intro-icon {
  flex: none;
  margin-top: 1px;
}

.participants__intro-text {
  min-width: 0;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.participants__card {
  display: flex;
  flex-direction: column;
  padding: 12px 4px 12px 14px;
  border-radius: 14px;
  background: #ffffff;
}

.participants__card--child {
  padding-bottom: 0;
}

.participants__card--notes {
  gap: 8px;
  padding: 14px;
}

.participants__row {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
}

.participants__avatar {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 18px;
  font-weight: 700;
  line-height: 1;
}

.participants__avatar--self {
  color: #ba470b;
  background: #fff0e3;
}

.participants__avatar--blue {
  color: #1d63c4;
  background: #e6f0ff;
}

.participants__avatar--green {
  color: #11804a;
  background: #e2f6ed;
}

.participants__who {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.participants__name-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.participants__name {
  min-width: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.participants__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.participants__tag--minor {
  color: #ba470b;
  background: #fff0e3;
}

.participants__sub {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.participants__confirmed {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.participants__edit {
  min-height: 44px;
  flex: none;
  padding: 0 10px;
  color: #536178;
  font-size: 14px;
  white-space: nowrap;
}

.participants__photo {
  display: flex;
  min-height: 44px;
  margin: 8px 0 0 56px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.participants__photo-label {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.participants__photo-state {
  flex: none;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.participants__photo-state--on {
  color: #11804a;
}

.participants__switch {
  display: flex;
  width: 56px;
  height: 44px;
  flex: none;
  padding: 0 10px 0 2px;
  align-items: center;
  justify-content: flex-end;
  box-sizing: border-box;
}

.participants__track {
  display: flex;
  width: 44px;
  height: 26px;
  padding: 2px;
  justify-content: flex-start;
  border-radius: 999px;
  background: #c9d3e1;
  box-sizing: border-box;
}

.participants__track--on {
  justify-content: flex-end;
  background: #ff6a00;
}

.participants__knob {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
}

.participants__section {
  display: flex;
  padding: 0 4px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.participants__section-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.participants__section-note,
.participants__footnote {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.participants__footnote {
  padding: 0 4px;
  color: #738096;
}

.participants__empty {
  padding: 16px 14px;
  border-radius: 14px;
  color: #738096;
  background: #ffffff;
  font-size: 13px;
  line-height: 19px;
}

.participants__notes-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.participants__note {
  display: flex;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 20px;
}

.participants__note-dot {
  width: 8px;
  flex: none;
}

.participants__note-text {
  min-width: 0;
}

.participants__skeleton {
  height: 72px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.participants__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.participants__state-title {
  font-size: 16px;
  font-weight: 700;
}

.participants__state-text {
  color: #738096;
  font-size: 13px;
}

.participants__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.participants__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.participants__toast {
  display: flex;
  min-height: 44px;
  padding: 0 2px 0 12px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  background: #e2f6ed;
}

.participants__toast-text {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: #11804a;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.participants__toast-link {
  min-height: 44px;
  flex: none;
  padding: 0 10px;
  color: #ba470b;
  font-size: 14px;
  white-space: nowrap;
}

.participants__add {
  height: 46px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 16px;
  font-weight: 500;
  line-height: 43px;
  box-sizing: border-box;
}

.participants__mask {
  position: fixed;
  /* Below 999, where the H5 date picker opens; it has to cover this sheet. */
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.participants__sheet {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 10px;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.participants__sheet-head {
  display: flex;
  align-items: center;
}

.participants__sheet-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.participants__sheet-close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.participants__fields {
  display: flex;
  flex-direction: column;
}

.participants__field {
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #f0f1f3;
}

.participants__field--last {
  border-bottom: 0;
}

.participants__field-label {
  width: 64px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.participants__field-value {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
}

.participants__field-value--empty {
  color: #91a0b4;
}

.participants__input {
  min-width: 0;
  height: 44px;
  flex: 1;
  color: #202c42;
  font-size: 15px;
}

.participants__sheet-error {
  color: #d64f67;
  font-size: 12px;
  line-height: 18px;
}

.participants__consent {
  display: flex;
  min-height: 44px;
  padding: 3px 0;
  align-items: flex-start;
  gap: 4px;
  box-sizing: border-box;
}

.participants__consent-toggle {
  display: flex;
  width: 30px;
  height: 30px;
  flex: none;
  margin: -5px 0 0 -6px;
  align-items: center;
  justify-content: center;
}

.participants__box {
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #9aa8ba;
  border-radius: 4px;
  background: #ffffff;
  box-sizing: border-box;
}

.participants__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.participants__consent-text {
  min-width: 0;
  color: #202c42;
  font-size: 13px;
  line-height: 19px;
}

.participants__link {
  color: #ba470b;
}

.participants__delete {
  min-height: 44px;
  align-self: flex-start;
  margin: -4px 0;
  padding: 0 4px;
  color: #d64f67;
  font-size: 14px;
}

.participants__delete-ask {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: #fff0e3;
}

.participants__delete-text {
  min-width: 0;
  flex: 1;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.participants__keep,
.participants__delete-confirm {
  height: 36px;
  flex: none;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.participants__keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
}

.participants__delete-confirm {
  color: #ffffff;
  background: #d64f67;
  font-weight: 500;
}

.participants__sheet-actions {
  display: flex;
  gap: 10px;
}

.participants__cancel,
.participants__save {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  box-sizing: border-box;
}

.participants__cancel {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.participants__save {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.participants__save--off,
.participants__save[disabled] {
  color: #91a0b4;
  background: #eef0f3;
}
</style>
