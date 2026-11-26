<script setup lang="ts">
import { computed } from 'vue'

import { roleLabel } from '@/core/auth/roles'
import { useSessionStore } from '@/core/auth/session'
import { buildMenu } from '@/core/navigation/module'
import PageSection from '@/core/ui/PageSection.vue'
import OpsDashboard from '@/features/dashboard/OpsDashboard.vue'
import { ADMIN_MODULES } from '@/modules'

const session = useSessionStore()
const groups = computed(() => buildMenu(ADMIN_MODULES, session.roles))
/** 平台运营、平台客服的首页先看经营概览；机构账号只有功能入口。 */
const showsDashboard = computed(() => session.can(['PLATFORM_OPERATOR', 'PLATFORM_SUPPORT']))

const initial = computed(() => {
  const name = session.account?.displayName?.trim()
  return name ? name.slice(0, 1).toUpperCase() : 'U'
})
</script>

<template>
  <div class="home">
    <OpsDashboard v-if="showsDashboard" />

    <section v-else class="workbench-profile">
      <div class="workbench-profile__left">
        <span class="workbench-profile__avatar">{{ initial }}</span>
        <div>
          <h1 class="workbench-profile__title">你好，{{ session.account?.displayName ?? '' }}</h1>
          <p class="workbench-profile__meta">
            <span v-if="session.account?.organizationName">所属机构：{{ session.account.organizationName }}</span>
            <span v-else>机构工作台</span>
          </p>
        </div>
      </div>
      <div class="workbench-profile__right">
        <span class="workbench-profile__label">当前角色：</span>
        <el-tag v-for="role in session.roles" :key="role" size="small" effect="plain" class="roles__tag">
          {{ roleLabel(role) }}
        </el-tag>
      </div>
    </section>

    <PageSection
      :title="showsDashboard ? '常用功能' : '业务功能导航'"
      :description="showsDashboard ? undefined : '仅展示本机构授权范围内的数据与操作入口'"
    >
      <p v-if="showsDashboard" class="roles">
        当前角色：
        <el-tag v-for="role in session.roles" :key="role" size="small" effect="plain" class="roles__tag">
          {{ roleLabel(role) }}
        </el-tag>
      </p>

      <el-empty
        v-if="groups.length === 0"
        description="你的账号暂时没有可用的功能。需要的话请联系平台运营给本机构开通角色。"
      />

      <div v-for="group in groups" :key="group.key" class="group">
        <h2 class="group__title">{{ group.title }}</h2>
        <div class="group__items">
          <router-link v-for="item in group.items" :key="item.path" :to="item.path" class="card">
            <span class="card__icon">
              <el-icon :size="18"><component :is="item.icon" /></el-icon>
            </span>
            <span class="card__label">{{ item.title }}</span>
          </router-link>
        </div>
      </div>
    </PageSection>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workbench-profile {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
}

.workbench-profile__left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.workbench-profile__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #ffffff;
  font-size: 18px;
  font-weight: 600;
  flex-shrink: 0;
}

.workbench-profile__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--admin-heading);
}

.workbench-profile__meta {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8c8c8c;
}

.workbench-profile__right {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #595959;
}

.roles {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.roles__tag {
  margin-right: 4px;
}

.group + .group {
  margin-top: 20px;
}

.group__title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #595959;
}

.group__items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(185px, 1fr));
  gap: 10px;
}

.card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #fafafa;
  color: var(--admin-text);
  text-decoration: none;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.card:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--admin-brand-surface);
  color: var(--el-color-primary);
}

.card__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 4px;
  background: #ffffff;
  border: 1px solid #ebeef5;
  color: var(--el-color-primary);
  flex-shrink: 0;
}

.card__label {
  font-size: 13.5px;
  font-weight: 500;
}
</style>
