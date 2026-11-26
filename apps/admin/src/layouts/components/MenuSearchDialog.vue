<script setup lang="ts">
import { ArrowRight, Search } from '@element-plus/icons-vue'
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { MenuGroupView } from '@/core/navigation/module'

const props = defineProps<{
  open: boolean
  groups: readonly MenuGroupView[]
}>()

const emit = defineEmits<{
  close: []
}>()

const router = useRouter()
const keyword = ref('')
const activeIndex = ref(0)
const resultList = ref<HTMLUListElement>()

const candidates = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const all = props.groups.flatMap((group) =>
    group.items.map((item) => ({
      path: item.path,
      title: item.title,
      groupTitle: group.title,
      icon: item.icon,
    })),
  )
  if (!q) return all
  return all.filter(
    (item) =>
      item.title.toLowerCase().includes(q)
      || item.groupTitle.toLowerCase().includes(q)
      || item.path.toLowerCase().includes(q),
  )
})

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      keyword.value = ''
      activeIndex.value = 0
    }
  },
)

watch(keyword, () => {
  activeIndex.value = 0
})

function select(path: string): void {
  emit('close')
  void router.push(path)
}

function onKeydown(event: Event): void {
  if (!(event instanceof KeyboardEvent) || event.isComposing) return
  const count = candidates.value.length
  if (count === 0) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    activeIndex.value = (activeIndex.value + (event.key === 'ArrowDown' ? 1 : count - 1)) % count
    void nextTick(() => resultList.value?.children[activeIndex.value]?.scrollIntoView({ block: 'nearest' }))
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const target = candidates.value[activeIndex.value]
    if (target) select(target.path)
  }
}
</script>

<template>
  <el-dialog
    :model-value="open"
    title="快捷菜单导航"
    width="520px"
    append-to-body
    @close="emit('close')"
  >
    <div class="menu-search">
      <el-input
        v-model="keyword"
        size="large"
        clearable
        autofocus
        placeholder="输入菜单名称、分组或路径快速直达…"
        aria-label="搜索菜单"
        @keydown="onKeydown"
        :prefix-icon="Search"
      />

      <ul v-if="candidates.length > 0" ref="resultList" class="menu-search__list">
        <li v-for="(item, idx) in candidates" :key="item.path">
          <button
            type="button"
            :class="['menu-search__item', { 'is-selected': idx === activeIndex }]"
            @mouseenter="activeIndex = idx"
            @click="select(item.path)"
          >
            <span class="menu-search__icon">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
            <span class="menu-search__meta">
              <strong>{{ item.title }}</strong>
              <span>{{ item.groupTitle }} · {{ item.path }}</span>
            </span>
            <el-icon class="menu-search__arrow"><ArrowRight /></el-icon>
          </button>
        </li>
      </ul>
      <el-empty v-else description="没有匹配的菜单项" :image-size="72" />
    </div>
  </el-dialog>
</template>

<style scoped>
.menu-search {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.menu-search__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 360px;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}

.menu-search__item {
  width: 100%;
  text-align: left;
  font: inherit;
  background: transparent;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 9px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.14s ease;
}

.menu-search__item.is-selected,
.menu-search__item:hover {
  border-color: var(--el-color-primary-light-7);
  background: var(--admin-brand-surface);
}

.menu-search__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: #f1f5f9;
  color: #334155;
  font-size: 17px;
  flex-shrink: 0;
}

.menu-search__item.is-selected .menu-search__icon {
  background: var(--el-color-primary);
  color: #fff;
}

.menu-search__meta {
  flex: 1;
  min-width: 0;
}

.menu-search__meta strong {
  display: block;
  font-size: 14px;
  color: #0f172a;
}

.menu-search__meta span {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #64748b;
}

.menu-search__arrow {
  color: #94a3b8;
}
</style>
