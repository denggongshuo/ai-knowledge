<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTabsStore, type TabItem } from '@/stores/tabs'

const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

const scrollRef = ref<HTMLElement>()

const menu = reactive({ visible: false, x: 0, y: 0, target: null as TabItem | null })
const menuStyle = computed(() => ({ left: `${menu.x}px`, top: `${menu.y}px` }))

const canCloseCurrent = computed(() => !menu.target?.affix)
const canCloseOthers = computed(() => {
  const t = menu.target
  return !!t && tabs.tabs.some((x) => !x.affix && x.fullPath !== t.fullPath)
})
const canCloseRight = computed(() => {
  const t = menu.target
  if (!t) return false
  const idx = tabs.tabs.findIndex((x) => x.fullPath === t.fullPath)
  return tabs.tabs.slice(idx + 1).some((x) => !x.affix)
})
const canCloseAll = computed(() => tabs.closableCount > 0)

function onClick(tab: TabItem) {
  if (tab.fullPath === route.fullPath) return
  router.push(tab.fullPath)
}

function onClose(tab: TabItem) {
  const target = tabs.close(tab.fullPath)
  if (target) router.push(target)
}

function openMenu(e: MouseEvent, tab: TabItem) {
  menu.target = tab
  menu.visible = true
  // 贴边时向内收敛，避免菜单被视口裁掉
  menu.x = Math.min(e.clientX, window.innerWidth - 144)
  menu.y = Math.min(e.clientY, window.innerHeight - 176)
}

function closeMenu() {
  menu.visible = false
}

function onRefresh() {
  const t = menu.target
  closeMenu()
  if (!t) return
  tabs.refresh(t.fullPath)
  if (t.fullPath !== route.fullPath) router.push(t.fullPath)
}

function onCloseCurrent() {
  const t = menu.target
  closeMenu()
  if (t) onClose(t)
}

function onCloseOthers() {
  const t = menu.target
  closeMenu()
  if (!t) return
  tabs.closeOthers(t.fullPath)
  if (t.fullPath !== route.fullPath) router.push(t.fullPath)
}

function onCloseRight() {
  const t = menu.target
  closeMenu()
  if (!t) return
  tabs.closeRight(t.fullPath)
  if (t.fullPath !== route.fullPath) router.push(t.fullPath)
}

function onCloseAll() {
  closeMenu()
  router.push(tabs.closeAll())
}

/** 激活标签滚动到可视区（手算 scrollLeft，避免 scrollIntoView 连带滚动祖先容器） */
watch(
  () => tabs.active,
  async () => {
    await nextTick()
    const box = scrollRef.value
    const el = box?.querySelector<HTMLElement>('.tv-tab.is-active')
    if (!box || !el) return
    const left = el.offsetLeft
    const right = left + el.offsetWidth
    if (left < box.scrollLeft) box.scrollTo({ left: left - 8, behavior: 'smooth' })
    else if (right > box.scrollLeft + box.clientWidth) {
      box.scrollTo({ left: right - box.clientWidth + 8, behavior: 'smooth' })
    }
  },
)

onMounted(() => document.addEventListener('click', closeMenu))
onBeforeUnmount(() => document.removeEventListener('click', closeMenu))
</script>

<template>
  <div class="tags-view">
    <div ref="scrollRef" class="tv-scroll">
      <div
        v-for="tab in tabs.tabs"
        :key="tab.fullPath"
        class="tv-tab"
        :class="{ 'is-active': tab.fullPath === tabs.active, 'is-affix': tab.affix }"
        :title="tab.fullPath"
        @click="onClick(tab)"
        @contextmenu.prevent="openMenu($event, tab)"
      >
        <span class="tv-pin" />
        <span class="tv-label">{{ tab.title }}</span>
        <span v-if="!tab.affix" class="tv-close" @click.stop="onClose(tab)">
          <svg viewBox="0 0 12 12" width="10" height="10" aria-hidden="true">
            <path
              d="M3 3 L9 9 M9 3 L3 9"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
            />
          </svg>
        </span>
      </div>
    </div>

    <div class="tv-actions">
      <button class="tv-all" :disabled="!canCloseAll" title="关闭全部标签" @click="onCloseAll">
        关闭全部
      </button>
    </div>

    <div v-if="menu.visible" class="tv-menu" :style="menuStyle" @click.stop>
      <div class="tv-mi" @click="onRefresh">刷新当前</div>
      <div class="tv-mi" :class="{ 'is-disabled': !canCloseCurrent }" @click="canCloseCurrent && onCloseCurrent()">
        关闭当前
      </div>
      <div class="tv-mi" :class="{ 'is-disabled': !canCloseOthers }" @click="canCloseOthers && onCloseOthers()">
        关闭其他
      </div>
      <div class="tv-mi" :class="{ 'is-disabled': !canCloseRight }" @click="canCloseRight && onCloseRight()">
        关闭右侧
      </div>
      <div class="tv-mi tv-mi-danger" :class="{ 'is-disabled': !canCloseAll }" @click="canCloseAll && onCloseAll()">
        关闭全部
      </div>
    </div>
  </div>
</template>

<style scoped>
.tags-view {
  height: 36px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
  user-select: none;
}
.tv-scroll {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
}
.tv-scroll::-webkit-scrollbar {
  height: 0;
}
.tv-tab {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 9px;
  border-radius: var(--radius-s, 4px);
  border: 1px solid transparent;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-text-color-regular, #606266);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: color 0.15s, background 0.15s, border-color 0.15s;
}
.tv-tab:hover {
  color: var(--el-color-primary);
}
.tv-tab.is-active {
  background: var(--el-color-primary-light-9, #ecf5ff);
  border-color: var(--el-color-primary-light-7, #c6e2ff);
  color: var(--el-color-primary);
  font-weight: 500;
}
.tv-label {
  max-width: 148px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tv-pin {
  display: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--el-color-primary);
  flex: none;
}
.tv-tab.is-affix .tv-pin {
  display: block;
}
.tv-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-right: -3px;
  border-radius: 50%;
  color: inherit;
  opacity: 0.55;
}
.tv-close:hover {
  background: var(--el-color-primary);
  color: #fff;
  opacity: 1;
}
.tv-actions {
  flex: none;
  display: flex;
  align-items: center;
  padding-left: 8px;
  border-left: 1px solid var(--el-border-color-lighter);
}
.tv-all {
  height: 24px;
  padding: 0 8px;
  border: none;
  border-radius: var(--radius-s, 4px);
  background: transparent;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  cursor: pointer;
}
.tv-all:hover:not(:disabled) {
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-color-primary);
}
.tv-all:disabled {
  color: var(--el-text-color-placeholder, #a8abb2);
  cursor: not-allowed;
}
.tv-menu {
  position: fixed;
  z-index: 3000;
  min-width: 132px;
  padding: 4px;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--radius, 8px);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
}
.tv-mi {
  height: 30px;
  line-height: 30px;
  padding: 0 10px;
  border-radius: var(--radius-s, 4px);
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
  cursor: pointer;
}
.tv-mi:hover {
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-color-primary);
}
.tv-mi-danger:hover {
  color: var(--el-color-danger);
}
.tv-mi.is-disabled {
  color: var(--el-text-color-placeholder, #a8abb2);
  cursor: not-allowed;
}
.tv-mi.is-disabled:hover {
  background: transparent;
  color: var(--el-text-color-placeholder, #a8abb2);
}
</style>
