<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  View,
  Download,
  Refresh,
  Delete,
  Plus,
  InfoFilled,
  Collection,
} from '@element-plus/icons-vue'
import { docApi, kbApi } from '@/api'
import type { DocMeta, DocVersion, IndexTask, ChunkStat, SecretLevel } from '@/types'
import { goBack } from '@/utils/nav'

const route = useRoute()
const router = useRouter()
const docId = computed(() => String(route.params.id ?? '1'))

const SECRET_LABEL: Record<SecretLevel, string> = { PUBLIC: '公开', INTERNAL: '内部', CONFIDENTIAL: '机密', SECRET: '绝密' }
const SECRET_ORDER: SecretLevel[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']

const loading = ref(false)
const activeTab = ref('attr')
const meta = ref<DocMeta | null>(null)
const versions = ref<DocVersion[]>([])
const indexTasks = ref<IndexTask[]>([])
const chunkStat = ref<ChunkStat | null>(null)
const kbOptions = ref<{ id: number; name: string }[]>([])

// 可编辑项
const secretLevel = ref<SecretLevel>('INTERNAL')
const targetKbId = ref<number>(1)
const tags = ref<string[]>([])
const newTag = ref('')

/** 本页加载时失败的端点（**按端点降级**：一个坏了不该让整页什么都不显示）。 */
const loadErrors = ref<string[]>([])

/**
 * 加载本页全部数据。
 *
 * <p>⚠️ 抽成独立函数而不是只写在 `onMounted` 里：**保存成功后要复用它**
 * （`saveAttr`）—— 移动知识库之后库名变了、密级下限也变了，页面必须重新取一次
 * 而不是拿旧值继续渲染。
 */
async function load() {
  loading.value = true
  // ⚠️ 原先是 `Promise.all` 且**没有 catch** —— 五个请求里任何一个失败
  // （`/doc/{id}/versions` 还没实现、文档不存在、后端没起）都会让整页空白，
  // 而空白的表现与"数据为空"长得一样。改成 allSettled + 逐项说明。
  const [m, v, t, c, kbs] = await Promise.allSettled([
    docApi.meta(docId.value),
    docApi.versionList(docId.value),
    docApi.indexTasks(docId.value),
    docApi.chunkStat(docId.value),
    kbApi.list(),
  ])
  const failed: string[] = []
  if (m.status === 'fulfilled') {
    meta.value = m.value
    secretLevel.value = m.value.securityLevel
    targetKbId.value = m.value.kbId
    // ⚠️ `tags` 可能是 `null`（= 未落库），**不是**空数组 —— 展开 null 会当场抛异常
    tags.value = m.value.tags ? [...m.value.tags] : []
  } else {
    failed.push('文档元信息')
  }
  if (v.status === 'fulfilled') {
    versions.value = v.value.list
  } else {
    failed.push('版本历史')
  }
  if (t.status === 'fulfilled') {
    indexTasks.value = t.value.list
  } else {
    failed.push('索引任务')
  }
  if (c.status === 'fulfilled') {
    chunkStat.value = c.value
  } else {
    failed.push('分片统计')
  }
  if (kbs.status === 'fulfilled') {
    kbOptions.value = ((kbs.value.list as unknown as { id: number; name: string }[]) ?? []).map((x) => ({ id: x.id, name: x.name }))
  }
  loadErrors.value = failed
  loading.value = false
}

onMounted(load)

/**
 * 密级下拉的可选项：**只能提高**（`I-6` / `G7-3`）。
 *
 * ⚠️ 下限取的是**当前生效密级**（`meta.securityLevel` = `max(文档, 库)`），**不是**库级：
 * 当文档自己已经比库更严时，按库级切会让用户看到一个"能把它降下来"的选项，
 * 而服务端会拒 —— 用户拿到的是一句他无法理解的失败。前端把不可选项**根本不列出来**，
 * 与需求 §6.7「密级下拉的选项**过滤掉了低于知识库密级的选项**」同一条口径。
 */
const secretOptions = computed(() => {
  const floor = meta.value?.securityLevel ?? 'INTERNAL'
  return SECRET_ORDER.slice(SECRET_ORDER.indexOf(floor))
})

/**
 * 选项文案。需求 §6.7：「密级下拉标注「**内部（继承知识库）**」，让用户知道当前值来自哪里」。
 *
 * ⚠️ 「继承」只标在**当前生效值**上，判据是 `ownSecurityLevel === null`（文档**未覆盖**）——
 * **不是**"值恰好等于库级"：用户主动把文档设成与库相同的值，与"它继承自库"是两回事。
 * 两者在数据上分得很清（`sys_document.secret_level` 是不是 NULL），页面上不该混起来。
 */
function optionLabel(l: SecretLevel) {
  const m = meta.value
  if (!m) return SECRET_LABEL[l]
  return m.ownSecurityLevel === null && l === m.securityLevel
    ? `${SECRET_LABEL[l]}（继承知识库）`
    : SECRET_LABEL[l]
}

const kbChanged = computed(() => meta.value != null && targetKbId.value !== meta.value.kbId)
/**
 * ⚠️ `meta.tags` 可能是 `null`（= **未落库**，不是"没有标签"）。
 * 🔴 **`tags` 不参与"改没改"的判定** —— 本批明确不支持改标签（见 `TAGS_UNSUPPORTED_REASON`），
 * 把它算进来只会让保存按钮在没有可保存内容时亮着，点下去只得到一句拒绝。
 */
const attrDirty = computed(
  () => meta.value != null && (secretLevel.value !== meta.value.securityLevel || kbChanged.value),
)
/**
 * `tags` 在本版本**不可编辑** —— 文档级内容标签**没有归属表**（`T-609`，`IndexEnhancer` 的
 * javadoc 自己写着）。⚠️ 与"按钮置灰要给原因"同一条纪律：**不能改就明确禁用并说明**，
 * 不要让用户填完一屏标签、点保存才被告知不支持。
 */
const TAGS_UNSUPPORTED_REASON = '本版本不支持修改内容标签：标签的归属表还没建（T-609）。密级与所属知识库可以正常保存。'

/**
 * ⚠️ 这里原先有 `addTag()` / `removeTag()`，本批**删掉了**它们。
 *
 * <p>与上一批删 `saveAttr()` 同一条纪律（**死代码不是起点，是诱饵**）：标签编辑器已经
 * 禁用（理由见 `TAGS_UNSUPPORTED_REASON`），留一对"还在、还能调"的函数，下一个人接
 * `T-609` 时最可能的动作是**只把控件解禁** —— 而后端的归属表还没建。
 *
 * <p>`T-609` 那批照着写即可：需求 §6.7 的约束是「输入后回车添加」+「建议控制在 3~8 个」。
 */

function sizeText(b: number) {
  return b / 1024 / 1024 >= 1 ? `${(b / 1024 / 1024).toFixed(1)} MB` : `${(b / 1024).toFixed(0)} KB`
}
function qualityOf(m: DocMeta) {
  const parts: string[] = []
  let danger = false
  if (m.tableTotal) {
    parts.push(`表格 ${m.tableParsed}/${m.tableTotal}`)
    if (m.tableParsed < m.tableTotal) danger = true
  }
  if (m.imageTotal) {
    parts.push(`图片 ${m.imageParsed ?? 0}/${m.imageTotal}`)
    if ((m.imageParsed ?? 0) < m.imageTotal) danger = true
  }
  if (!parts.length) return { text: '无表格 / 图片', danger: false }
  return { text: parts.join(' · '), danger }
}

// ---------------- 操作 ----------------
function preview() {
  router.push(`/doc/${docId.value}/preview`)
}
async function download() {
  try {
    const res = await docApi.downloadUrl(docId.value)
    ElMessage.success('已生成下载链接（有效期 10 分钟）')
    window.open(res.url, '_blank')
  } catch {
    ElMessage.error('下载链接生成失败')
  }
}
async function reindex() {
  await docApi.reindex(docId.value)
  ElMessage.success('已发起重新索引，用于修复解析失败的文档')
}
/**
 * 保存属性（`PATCH /doc/{id}/attributes` · `T-601`）。
 *
 * <p>响应里的 `needReindex` = **本次触发了重建索引**（也就是移动了知识库）——
 * 需求 §6.7 的两句：「属性修改后**立即生效，无需重建索引**；**移动知识库除外**」。
 * 所以提示文案分两种，不合并成一句"保存成功"。
 *
 * <p>⚠️ **只发生变化的字段才发**：服务端把"什么都没变"也当正常请求处理（幂等、不写库），
 * 但每次无脑全量发会让那次"改没改"的判定永远为真，审计与日志上就看不出真实动作。
 */
async function saveAttr() {
  const m = meta.value
  if (!m) return
  const moving = kbChanged.value
  if (moving) {
    const target = kbOptions.value.find((k) => k.id === targetKbId.value)
    await ElMessageBox.confirm(
      `将本文档移动到「${target?.name ?? targetKbId.value}」。\n`
      + '移动会触发重建索引：分片会重新向量化到目标知识库的分区，期间本文档暂时不可检索。\n'
      + '⚠️ 目标知识库的密级不能低于本文档当前生效的密级 —— 否则换个库就能把机密文档降级（I-6）。',
      '移动知识库',
      { type: 'warning' },
    )
  }
  try {
    const res = await docApi.saveAttributes(docId.value, {
      secretLevel: secretLevel.value !== m.securityLevel ? secretLevel.value : undefined,
      kbId: moving ? targetKbId.value : undefined,
    })
    ElMessage.success(res.needReindex ? '已保存；知识库已变更，正在重建索引' : '已保存（立即生效，无需重建索引）')
    await load()
  } catch (e) {
    // 服务端会给出**可行动**的原因（密级只能提高 / 目标库权限不足 / 目标库密级更低），
    // 原样透出比吞掉换成"保存失败"有用得多
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

function discard() {
  if (!meta.value) return
  secretLevel.value = meta.value.securityLevel
  targetKbId.value = meta.value.kbId
  tags.value = meta.value.tags ? [...meta.value.tags] : []
  ElMessage.info('已放弃修改')
}

async function rollback(v: DocVersion) {
  await ElMessageBox.confirm(
    `回滚到 ${v.version} 会将历史版本重新向量化并覆盖当前索引，但「历史问答的引用不会改变」。确认回滚？`,
    '版本回滚',
    { type: 'warning' },
  )
  await docApi.rollback(docId.value, v.version)
  ElMessage.success(`已回滚到 ${v.version}（保留一条新版本记录，可追溯）`)
}

function compareVersion(v: DocVersion) {
  ElMessage.info(`版本对比：${v.version} ↔ v3（v1 仅做元数据对比，文本 diff 接口已预留）`)
}

async function removeDoc() {
  try {
    await ElMessageBox.confirm('将移入回收站，30 天内可恢复（恢复后需重建索引）。', '删除文档', {
      distinguishCancelAndClose: true,
      confirmButtonText: '移入回收站',
      cancelButtonText: '彻底删除',
      type: 'warning',
    })
    await docApi.remove(docId.value)
    ElMessage.success('已移入回收站')
    router.push('/kb')
  } catch (action) {
    if (String(action) !== 'cancel') return
    // 彻底删除：二次确认输入文件名
    if (!meta.value) return
    try {
      await ElMessageBox.prompt(`此操作不可恢复，向量数据将被一并清理。请输入文件名「${meta.value.title}」以确认。`, '彻底删除', {
        type: 'error',
        inputPlaceholder: meta.value.title,
        inputValidator: (val: string) => val === meta.value?.title || '文件名不匹配',
        confirmButtonText: '彻底删除',
        confirmButtonClass: 'el-button--danger',
      })
      ElMessage.success('已彻底删除')
      router.push('/kb')
    } catch {
      /* 取消 */
    }
  }
}
</script>

<template>
  <div v-loading="loading" class="page doc-detail">
    <!-- 降级说明：某个端点失败时**说清是哪一块**，而不是整页空白 -->
    <el-alert
      v-if="loadErrors.length"
      type="warning"
      :closable="false"
      show-icon
      class="mb-12"
      title="部分内容没能加载"
      :description="`以下部分暂时取不到：${loadErrors.join('、')}。其余内容照常显示。`"
    />
    <!-- 整页无内容（多半是文档不存在 / 库不可见）：给可行动的说明，别留一片空白 -->
    <el-empty
      v-if="!loading && !meta && loadErrors.length"
      description="这篇文档打不开：它可能不存在，或你所在的部门看不到它所属的知识库。"
    >
      <el-button @click="goBack(router, '/kb')">返回知识库列表</el-button>
    </el-empty>
    <div v-if="meta" class="page-head">
      <div class="head-left">
        <el-button text :icon="ArrowLeft" @click="goBack(router, '/kb')">返回</el-button>
        <div class="file-ic" :class="'ft-' + meta.type.toLowerCase()">{{ meta.type }}</div>
        <div>
          <h2 class="page-title">{{ meta.title }}</h2>
          <div class="head-tags">
            <el-tag size="small" type="success">已就绪</el-tag>
            <el-tag size="small" :type="qualityOf(meta).danger ? 'danger' : 'success'">{{ qualityOf(meta).text }}</el-tag>
            <el-tag size="small" type="info">{{ sizeText(meta.size) }} · {{ meta.pageCount }} 页 · {{ meta.chunkCount }} 分片</el-tag>
            <span class="muted">{{ meta.kbName }}</span>
          </div>
        </div>
      </div>
      <div class="head-actions">
        <el-button :icon="View" @click="preview">在线预览</el-button>
        <el-button :icon="Download" @click="download">下载</el-button>
        <el-button :icon="Refresh" @click="reindex">重新索引</el-button>
        <el-button type="danger" plain :icon="Delete" @click="removeDoc">删除</el-button>
      </div>
    </div>

    <el-card v-if="meta" shadow="never" class="mt-12">
      <el-tabs v-model="activeTab">
        <!-- ============ 属性与标签 ============ -->
        <el-tab-pane name="attr" label="属性与标签">
          <div class="attr-grid">
            <div>
              <div class="sec-title">
                <span class="tag-mini">人工维护</span>
                <b>治理属性</b>
                <span class="muted sm">覆盖知识库默认值</span>
              </div>

              <div class="two">
                <el-form-item label="密级">
                  <el-select v-model="secretLevel" style="width: 100%">
                    <el-option v-for="l in secretOptions" :key="l" :label="optionLabel(l)" :value="l" />
                  </el-select>
                  <div class="hint">可单独提高，不能低于知识库密级</div>
                </el-form-item>
                <el-form-item label="所属知识库">
                  <el-select v-model="targetKbId" style="width: 100%">
                    <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="k.id" />
                  </el-select>
                  <!-- 需求 §6.7：「移动知识库前弹出变更确认（复用 16 页）」+「移动后需重建索引」 -->
                  <div class="hint" :class="{ warn: kbChanged }">
                    {{ kbChanged ? '已改动：保存时会确认，并触发重建索引' : '改动后才需重建索引（改密级不需要）' }}
                  </div>
                </el-form-item>
              </div>

              <el-form-item label="内容标签">
                <!--
                  ⚠️ **禁用而不是"能点但不保存"**：文档级内容标签至今没有归属表
                  （`IndexEnhancer` 的 javadoc 自己写着这一条）。让用户填完再告诉他存不上，
                  与"按钮置灰要给原因"是同一条纪律的反面 —— 不能改就该一开始就说清。
                  ⚠️ 下面那段"还没有落库表"的说明**保留**：禁用是对本版本的事实陈述，不是待办。
                -->
                <div class="tag-input-row">
                  <el-input v-model="newTag" placeholder="输入后回车添加标签" disabled :title="TAGS_UNSUPPORTED_REASON" />
                  <el-button :icon="Plus" disabled>添加</el-button>
                </div>
                <div class="chip-row mt-8">
                  <el-tag v-for="t in tags" :key="t">{{ t }}</el-tag>
                  <span v-if="!tags.length" class="hint">暂无标签</span>
                </div>
                <div class="hint warn">{{ TAGS_UNSUPPORTED_REASON }}</div>
              </el-form-item>

              <div class="sec-title mt-16"><el-icon><Collection /></el-icon><b>文档信息</b></div>
              <dl class="kv">
                <dt>文档 ID</dt><dd class="mono">{{ meta.id }}</dd>
                <dt>原文件名</dt><dd class="ellipsis">{{ meta.title }}</dd>
                <dt>文件类型</dt><dd>{{ meta.fileDesc }}</dd>
                <dt>文件大小</dt><dd class="mono">{{ sizeText(meta.size) }}</dd>
                <dt>页数 / 分片</dt><dd class="mono">{{ meta.pageCount }} 页 / {{ meta.chunkCount }} 个分片</dd>
                <dt>其中表格</dt><dd class="mono">{{ meta.tableTotal }} 张 · {{ chunkStat?.tableSubBlocks ?? 88 }} 个子块</dd>
                <dt>其中图片</dt><dd class="mono">{{ meta.imageTotal ?? 0 }} 张 · VL 已描述 {{ meta.imageParsed ?? 0 }} 张</dd>
                <dt>解析质量</dt>
                <dd>
                  <el-tag v-if="qualityOf(meta).danger" size="small" type="danger">{{ qualityOf(meta).text }}，建议重建索引</el-tag>
                  <el-tag v-else size="small" type="success">{{ qualityOf(meta).text }}</el-tag>
                </dd>
                <dt>MD5</dt><dd class="mono ellipsis">{{ meta.md5 }}</dd>
                <dt>存储路径</dt><dd class="mono ellipsis">{{ meta.storagePath }}</dd>
                <dt>上传人</dt><dd>{{ meta.owner }}</dd>
                <dt>上传时间</dt><dd class="mono">{{ meta.uploadedAt }}</dd>
                <dt>最近索引</dt><dd class="mono">{{ meta.lastIndexAt }}</dd>
                <!--
                  ⚠️ 这两个数**恒为 null**（未采集）：`cited` 列存在但写入方写死 null，
                  引用角标属 `T-804`。所以这里显示「未采集」而**不是「0 次」** ——
                  后者是一句关于内容的断言，而我们并没有那个事实（长期约定：
                  判不了必须与判否分开报）。
                -->
                <dt>被引用</dt>
                <dd v-if="meta.citations === null && meta.downVotes === null" class="hint">
                  未采集（引用角标能力未上线，暂时拿不到"被引用 / 点踩"的次数）
                </dd>
                <dd v-else>{{ meta.citations ?? 0 }} 次问答引用 · {{ meta.downVotes ?? 0 }} 次点踩</dd>
              </dl>
            </div>

            <div>
              <!-- 版本历史摘要 -->
              <el-card shadow="never" class="side-card">
                <template #header>
                  <div class="card-head-row">
                    <h3>版本历史</h3>
                    <el-button size="small" text type="primary" @click="activeTab = 'version'">查看全部</el-button>
                  </div>
                </template>
                <div
                  v-for="v in versions"
                  :key="v.version"
                  class="ver-item"
                  :class="{ current: v.current }"
                >
                  <div class="row-between">
                    <div class="row-inline">
                      <b>{{ v.version }}</b>
                      <el-tag v-if="v.current" size="small" type="success">当前</el-tag>
                    </div>
                    <span class="tiny mono muted">{{ v.at.slice(5, 16) }}</span>
                  </div>
                  <div class="tiny muted">{{ v.owner }} · {{ sizeText(v.size) }} · {{ v.chunkCount }} 个分片</div>
                </div>
                <div class="hint mt-8">同名上传默认归档为新版本，不覆盖（避免历史问答引用指向新内容）</div>
              </el-card>

              <!-- 索引记录摘要 -->
              <el-card shadow="never" class="side-card">
                <template #header>
                  <div class="card-head-row">
                    <h3>索引记录</h3>
                    <el-button size="small" text type="primary" @click="activeTab = 'index'">查看全部</el-button>
                  </div>
                </template>
                <div v-for="t in indexTasks.slice(0, 3)" :key="t.id" class="task-row">
                  <span class="dot" :class="t.status.toLowerCase()" />
                  <div class="flex-1">
                    <div class="row-between">
                      <span class="txt">{{ t.stage }}</span>
                      <span class="tiny mono muted">{{ t.at.slice(5, 16) }}</span>
                    </div>
                    <div class="tiny muted">
                      <template v-if="t.durationSec">耗时 {{ t.durationSec }} 秒 · {{ t.chunkCount }} 个分片 · {{ t.version }}</template>
                      <template v-else>{{ t.detail }}</template>
                    </div>
                  </div>
                </div>
              </el-card>
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 版本历史 ============ -->
        <el-tab-pane name="version">
          <template #label>版本历史<span class="tab-num">{{ versions.length }}</span></template>
          <!--
            ⚠️ 本版本两件事都还没有：`GET /doc/{id}/versions` 未实现（`T-606`），
            而"同名上传归档为新版本"的机制也不存在（没有版本表）。
            ⇒ 说清楚，而不是渲染一张恒空的表（空表读起来像"这篇文档只有一个版本"）。
          -->
          <el-alert type="info" :closable="false" show-icon class="mb-12">
            <template #title>
              <span class="alert-text">
                版本历史本版本尚未交付：同名上传归档、版本对比与回滚属后续批次。
                现在每篇文档都只有一个版本。
              </span>
            </template>
          </el-alert>
          <el-table :data="versions" border>
            <el-table-column prop="version" label="版本" width="100">
              <template #default="{ row }">
                <b>{{ row.version }}</b>
                <el-tag v-if="row.current" size="small" type="success" class="ml-6">当前</el-tag>
                <el-tag v-if="row.hasNewer" size="small" type="warning" class="ml-6">已有更新版本</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="110">
              <template #default="{ row }"><span class="mono">{{ sizeText(row.size) }}</span></template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="分片数" width="90" />
            <el-table-column prop="owner" label="上传人" width="100" />
            <el-table-column prop="at" label="上传时间" width="160" />
            <el-table-column prop="note" label="说明" min-width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text @click="compareVersion(row)">对比</el-button>
                <el-button v-if="!row.current" size="small" text type="primary" @click="rollback(row)">回滚</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-alert type="warning" :closable="false" show-icon class="mt-12">
            <template #title>
              <span class="alert-text">回滚会将该版本重新向量化并覆盖当前索引，<b>历史问答的引用不会改变</b>；当前版本无回滚按钮</span>
            </template>
          </el-alert>
        </el-tab-pane>

        <!-- ============ 分片统计 ============ -->
        <el-tab-pane name="chunk" label="分片统计">
          <div v-if="chunkStat" class="stat-grid">
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.total }}</div>
              <div class="sc-label">分片总数</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.text }}</div>
              <div class="sc-label">正文块</div>
            </div>
            <div class="stat-card warn">
              <div class="sc-val">{{ chunkStat.table }}</div>
              <div class="sc-label">表格块（原子）</div>
            </div>
            <div class="stat-card img">
              <div class="sc-val">{{ chunkStat.image ?? 0 }}</div>
              <div class="sc-label">图片块（原子）</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.summary }}</div>
              <div class="sc-label">摘要块</div>
            </div>
            <div class="stat-card">
              <!-- ⚠️ 空文档时后端给的是 null（0/0 的均值是"判不了"，不是 0）⇒ 渲染成「—」 -->
              <div class="sc-val">{{ chunkStat.avgTokens ?? '—' }}</div>
              <div class="sc-label">平均 tokens</div>
            </div>
            <div class="stat-card">
              <div class="sc-val">{{ chunkStat.titlePathCoverage }}%</div>
              <div class="sc-label">标题路径覆盖</div>
            </div>
          </div>

          <div v-if="chunkStat" class="dist-block">
            <div class="field-title">按块类型分布</div>
            <div v-for="item in [
              { label: '正文', value: chunkStat.text, color: '#909399' },
              { label: '表格', value: chunkStat.table, color: '#e6a23c' },
              { label: '图片', value: chunkStat.image ?? 0, color: '#70b0f0' },
              { label: '摘要', value: chunkStat.summary, color: '#409eff' },
            ]" :key="item.label" class="dist-row">
              <span class="dist-label">{{ item.label }}</span>
              <div class="dist-bar">
                <i :style="{ width: (item.value / chunkStat.total) * 100 + '%', background: item.color }" />
              </div>
              <span class="dist-val mono">{{ item.value }} · {{ ((item.value / chunkStat.total) * 100).toFixed(1) }}%</span>
            </div>
            <div class="hint mt-8">
              表格 12 个原子块共拆出 {{ chunkStat.tableSubBlocks }} 个子块用于检索（父块用于回表）；
              图片块整体原子化（不做图像切分），其 VL 描述与检索摘要均由当前 VL 模型产出。
              被问到的通常是子块或图片描述，喂给大模型的是完整父块。
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 索引记录 ============ -->
        <el-tab-pane name="index">
          <template #label>索引记录<span class="tab-num">{{ indexTasks.length }}</span></template>
          <div class="timeline">
            <div v-for="t in indexTasks" :key="t.id" class="tl-row">
              <span class="dot" :class="t.status.toLowerCase()" />
              <div class="flex-1">
                <div class="row-between">
                  <span class="tl-title">{{ t.stage }}</span>
                  <span class="tiny mono muted">{{ t.at }}</span>
                </div>
                <div class="tiny muted">
                  <template v-if="t.durationSec">耗时 {{ t.durationSec }} 秒 · {{ t.chunkCount }} 个分片 · {{ t.version }}</template>
                  <template v-else>{{ t.detail }}</template>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <div class="save-bar">
        <span class="sb-left">
          <el-icon><InfoFilled /></el-icon>
          <!--
            ⚠️ 这两句是需求 §6.7 的口径原文：**属性修改后立即生效、无需重建索引；移动知识库除外**。
            分两句写而不是合成"保存后生效"：用户最需要知道的就是"这次改动要不要等重建"。
          -->
          <span class="tiny muted">
            密级修改立即生效，无需重建索引；移动知识库会触发重建（保存时会再次确认）。
          </span>
        </span>
        <div class="sb-actions">
          <el-button :disabled="!attrDirty" @click="discard">取消</el-button>
          <el-button type="primary" :disabled="!attrDirty" @click="saveAttr">保存属性</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.doc-detail {
  height: 100%;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.file-ic {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  flex: none;
  background: #909399;
}
.ft-pdf {
  background: #f56c6c;
}
.ft-doc,
.ft-docx {
  background: #409eff;
}
.ft-xls,
.ft-xlsx {
  background: #67c23a;
}
.ft-ppt,
.ft-pptx {
  background: #e6a23c;
}
.ft-txt,
.ft-md {
  background: #909399;
}
.page-title {
  font-size: 18px;
  margin: 0;
}
.head-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex: none;
}
.mt-12 {
  margin-top: 12px;
}
.mt-16 {
  margin-top: 16px;
}
.mt-8 {
  margin-top: 8px;
}
.ml-6 {
  margin-left: 6px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.sm {
  font-size: 12px;
}
.tiny {
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono);
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
.hint.warn {
  color: var(--el-color-warning);
}
.alert-text {
  font-size: 12.5px;
  line-height: 1.6;
}
.attr-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 26px;
  align-items: start;
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.sec-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 12px;
}
.sec-title b {
  font-size: 13.5px;
  font-weight: 600;
}
.tag-mini {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary-dark-2);
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag-input-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.kv {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 11px 10px;
  font-size: 13px;
  margin: 0;
}
.kv dt {
  color: var(--el-text-color-secondary);
}
.kv dd {
  margin: 0;
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.side-card {
  margin-bottom: 16px;
}
.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-head-row h3 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.ver-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 11px 12px;
  margin-bottom: 9px;
}
.ver-item.current {
  border-color: var(--el-color-primary-light-5);
  background: #f7fbff;
}
.ver-item b {
  font-size: 13px;
}
.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.row-inline {
  display: flex;
  align-items: center;
  gap: 7px;
}
.flex-1 {
  flex: 1;
  min-width: 0;
}
.task-row,
.tl-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 13px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  margin-top: 5px;
  background: var(--el-color-info);
}
.dot.done {
  background: var(--el-color-success);
}
.dot.running {
  background: var(--el-color-primary);
}
.dot.failed {
  background: var(--el-color-danger);
}
/*
 * `PENDING`（排队中）—— 本批补的第四档。
 * ⚠️ 原先类型里那一档叫 `INFO`，而**这里根本没有 `.dot.info`** ⇒ 那一行的圆点是裸的
 * （它落到 `.dot` 的默认灰，与"没有任何状态"长得一样）。现在与后端状态机一对一。
 */
.dot.pending {
  background: var(--el-color-warning);
}
.txt,
.tl-title {
  font-size: 12.5px;
  font-weight: 500;
}
.tab-num {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  font-size: 11px;
  border-radius: 8px;
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-secondary);
}

/* 分片统计 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}
.stat-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px;
  text-align: center;
}
.stat-card.warn {
  border-color: #f0d9b5;
  background: #fffbf5;
}
.stat-card.img {
  border-color: #cddff5;
  background: #f5f9ff;
}
.sc-val {
  font-size: 22px;
  font-weight: 600;
  font-family: var(--font-mono);
}
.sc-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.dist-block {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 16px;
}
.field-title {
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 12px;
}
.dist-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.dist-label {
  width: 50px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.dist-bar {
  flex: 1;
  height: 8px;
  background: var(--el-border-color-extra-light);
  border-radius: 4px;
  overflow: hidden;
}
.dist-bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
}
.dist-val {
  width: 130px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: right;
  flex: none;
}

/* 底部保存条 */
.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px -20px -20px;
  padding: 13px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: #fafbfd;
}
.sb-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-info);
}
.sb-actions {
  display: flex;
  gap: 10px;
}
</style>
