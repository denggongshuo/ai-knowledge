<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Folder, Search, ArrowRight } from '@element-plus/icons-vue'
import { kbApi } from '@/api'
import type { KnowledgeBase } from '@/types'

interface KbBrief {
  id: number
  name: string
  docCount: number
  chunkCount: number
}

const router = useRouter()
const list = ref<KbBrief[]>([])
const loading = ref(false)
const keyword = ref('')
const scope = ref<'all' | 'mine'>('all')

async function load() {
  loading.value = true
  try {
    const res = await kbApi.list(scope.value)
    list.value = (res.list as KbBrief[]) ?? []
  } finally {
    loading.value = false
  }
}

const filtered = () => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((k) => k.name.toLowerCase().includes(kw))
}

onMounted(load)

function goCreate() {
  router.push('/kb/create')
}
</script>

<template>
  <div class="page kb-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">知识库管理</h2>
        <p class="page-desc">共 {{ list.length }} 个知识库 · 文档在库可被检索问答引用</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="goCreate">创建知识库</el-button>
    </div>

    <div class="kb-toolbar">
      <el-radio-group v-model="scope" @change="load">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="mine">我创建的</el-radio-button>
      </el-radio-group>
      <el-input v-model="keyword" placeholder="搜索知识库名称" :prefix-icon="Search" clearable style="width: 240px" />
    </div>

    <div v-loading="loading" class="kb-grid">
      <el-card v-for="k in filtered()" :key="k.id" class="kb-card" shadow="hover" @click="router.push(`/kb/${k.id}`)">
        <div class="kb-card-head">
          <div class="kb-icon"><el-icon :size="20"><Folder /></el-icon></div>
          <div class="kb-name">{{ k.name }}</div>
        </div>
        <div class="kb-meta">
          <span>文档 {{ k.docCount }}</span>
          <span>分片 {{ k.chunkCount.toLocaleString() }}</span>
        </div>
        <div class="kb-foot">
          <el-button text type="primary" size="small" :icon="ArrowRight" @click.stop="router.push(`/kb/${k.id}/upload`)">上传文档</el-button>
        </div>
      </el-card>
      <el-empty v-if="!loading && filtered().length === 0" description="暂无知识库" style="grid-column: 1 / -1" />
    </div>
  </div>
</template>

<style scoped>
.kb-page {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.kb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0 20px;
}
.kb-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  min-height: 200px;
}
.kb-card {
  cursor: pointer;
}
.kb-card :deep(.el-card__body) {
  padding: 18px;
}
.kb-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.kb-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.kb-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.kb-meta {
  display: flex;
  gap: 16px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kb-foot {
  margin-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
  text-align: right;
}
</style>