<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Check, DocumentAdd, InfoFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import AdminQuestionEditor from '../components/AdminQuestionEditor.vue'
import { getAdminQuestionGateway } from '../api/gateway'
import { buildQuestionPayload, createBlankQuestionForm, validateQuestionForm } from '../model/question-form'
import { resolveAdminQuestionError } from '../model/error-messages'

const router = useRouter()
const authStore = useAuthStore()
const gateway = getAdminQuestionGateway()
const form = ref(createBlankQuestionForm())
const errors = ref<string[]>([])
const submitting = ref(false)
const mutationId = ref(createMutationId())

function createMutationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `question-create-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function submit(): Promise<void> {
  errors.value = validateQuestionForm(form.value)
  if (errors.value.length) return
  if (!authStore.hasPermission('question:create')) {
    errors.value = ['当前账号无权创建题目']
    return
  }
  submitting.value = true
  try {
    const result = await gateway.createQuestion({
      clientMutationId: mutationId.value,
      baseVersionId: null,
      payload: buildQuestionPayload(form.value),
      copyrightStatus: form.value.copyrightStatus,
      usageRestriction: form.value.usageRestriction,
    })
    mutationId.value = createMutationId()
    ElMessage.success(result.replayed ? '已恢复此前创建结果' : '已创建题目草稿')
    await router.push(`/admin/questions/${result.questionId}/versions/${result.questionVersionId}`)
  } catch (error) {
    errors.value = [resolveAdminQuestionError(error, '创建草稿失败，请稍后重试')]
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="question-write-page" data-testid="admin-question-new-page">
    <header class="write-header">
      <div>
        <p>题库管理 <span>/</span> 新建题目</p>
        <h1>创建题目草稿</h1>
        <small>完整填写题目内容与答案解析，保存后进入题目工作流</small>
      </div>
      <div class="write-actions">
        <el-button class="back-button" @click="router.push('/admin/questions')"><el-icon><ArrowLeft /></el-icon>返回列表</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!authStore.hasPermission('question:create')" data-testid="save-question-draft" @click="submit">
          <el-icon><DocumentAdd /></el-icon>保存草稿
        </el-button>
      </div>
    </header>

    <section class="write-guide" aria-label="录入说明">
      <span class="guide-icon"><el-icon><InfoFilled /></el-icon></span>
      <div><strong>录入提示</strong><p>资格、科目和题型决定后续编辑结构；请确认答案、解析及权利信息完整无误。</p></div>
      <div class="guide-status"><el-icon><Check /></el-icon>保存为草稿</div>
    </section>

    <el-alert v-if="errors.length" class="form-error-alert" type="error" :closable="false" show-icon data-testid="question-form-errors">
      <template #title>请处理以下问题</template>
      <ul><li v-for="message in errors" :key="message">{{ message }}</li></ul>
    </el-alert>
    <main class="editor-workspace">
      <AdminQuestionEditor v-model="form" />
    </main>
  </section>
</template>

<style scoped>
.question-write-page {
  display: grid;
  gap: 18px;
  min-height: calc(100vh - var(--platform-header-height));
  padding: 28px var(--platform-page-padding) 52px;
  background:
    radial-gradient(circle at 90% 1%, rgb(37 99 235 / 5%), transparent 28rem),
    var(--color-page-canvas);
  color: #172033;
}

.write-header,
.write-guide,
.editor-workspace,
.question-write-page > .el-alert {
  width: min(1320px, 100%);
  margin-right: auto;
  margin-left: auto;
}

.write-header, .write-actions, .write-guide {
  display: flex;
  align-items: center;
  gap: 16px;
}

.write-header { justify-content: space-between; }
.write-header p {
  margin: 0 0 4px;
  color: #2563eb;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .08em;
}
.write-header p span { padding: 0 4px; color: #cbd5e1; }

.write-header h1 {
  margin: 6px 0 0;
  color: #172033;
  font-size: 29px;
  letter-spacing: -.025em;
}

.write-header small { display: block; margin-top: 7px; color: #64748b; font-size: 11px; }
.write-actions { gap: 9px; }
.write-actions .el-button { min-height: 41px; margin: 0; border-radius: 10px; }
.back-button { border-color: #dbe4f0; color: #475569; }

.write-guide {
  min-height: 66px;
  padding: 12px 16px;
  box-sizing: border-box;
  border: 1px solid #d7e4f8;
  border-radius: 14px;
  background: linear-gradient(90deg, #f7faff, #fff);
}
.guide-icon { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 11px; background: #e8f1ff; color: #2563eb; font-size: 17px; }
.write-guide > div:nth-child(2) { display: grid; gap: 4px; }
.write-guide strong { color: #334155; font-size: 11px; }
.write-guide p { margin: 0; color: #64748b; font-size: 9px; line-height: 1.5; }
.guide-status { display: inline-flex; margin-left: auto; padding: 6px 9px; align-items: center; gap: 5px; border-radius: 999px; background: #ecfdf5; color: #047857; font-size: 9px; font-weight: 650; white-space: nowrap; }
.form-error-alert { border-radius: 11px; }
.question-write-page ul { margin: 6px 0 0; padding-left: 20px; }

.editor-workspace :deep(.question-editor) { gap: 18px; }
.editor-workspace :deep(.editor-section) {
  gap: 16px;
  padding: 24px 26px;
  border: 1px solid #dce5f0;
  border-radius: 15px;
  background: white;
  box-shadow: 0 10px 28px rgb(15 23 42 / 4%);
}
.editor-workspace :deep(.editor-section h2) { position: relative; padding-left: 13px; color: #1e293b; font-size: 17px; }
.editor-workspace :deep(.editor-section h2::before) { position: absolute; top: 2px; bottom: 2px; left: 0; width: 3px; border-radius: 99px; background: #2563eb; content: ""; }
.editor-workspace :deep(.editor-section h3) { color: #334155; font-size: 13px; }
.editor-workspace :deep(.editor-section label) { gap: 7px; color: #475569; font-size: 10px; font-weight: 650; }
.editor-workspace :deep(.editor-section input),
.editor-workspace :deep(.editor-section select),
.editor-workspace :deep(.editor-section textarea) {
  min-height: 41px;
  padding: 9px 11px;
  border-color: #dbe4f0;
  border-radius: 9px;
  background: #fbfdff;
  color: #334155;
  font-size: 11px;
  transition: border-color .16s ease, box-shadow .16s ease, background .16s ease;
}
.editor-workspace :deep(.editor-section textarea) { line-height: 1.65; resize: vertical; }
.editor-workspace :deep(.editor-section input:focus),
.editor-workspace :deep(.editor-section select:focus),
.editor-workspace :deep(.editor-section textarea:focus) { border-color: #2563eb; outline: 0; background: white; box-shadow: 0 0 0 3px rgb(37 99 235 / 8%); }
.editor-workspace :deep(.editor-section input:disabled),
.editor-workspace :deep(.editor-section select:disabled),
.editor-workspace :deep(.editor-section textarea:disabled) { cursor: not-allowed; background: #f1f5f9; color: #94a3b8; }
.editor-workspace :deep(.el-select__wrapper) { min-height: 41px; border-radius: 9px; box-shadow: 0 0 0 1px #dbe4f0 inset; }
.editor-workspace :deep(.editor-grid) { gap: 14px; }
.editor-workspace :deep(.option-list), .editor-workspace :deep(.point-list) { padding: 14px; border: 1px solid #e6ecf3; border-radius: 12px; background: #f8fafc; }
.editor-workspace :deep(.option-row) { padding: 8px; border: 1px solid #e5eaf1; border-radius: 10px; background: white; }
.editor-workspace :deep(.option-row > strong) { display: grid; width: 27px; height: 27px; place-items: center; border-radius: 8px; background: #eff6ff; color: #2563eb; font-size: 10px; }
.editor-workspace :deep(.el-button) { border-radius: 8px; }
.editor-workspace :deep(.sub-editor) { padding: 18px; border: 1px solid #e1e8f1; border-radius: 12px; background: #fbfdff; }
.editor-workspace :deep(.question-preview) { padding: 18px; border: 1px solid #d7e4f8; border-left: 3px solid #2563eb; border-radius: 10px; background: #f7faff; }
.editor-workspace :deep(.score-closure) { padding: 10px 12px; border-radius: 9px; background: #f1f5f9; }

@media (max-width: 900px) {
  .question-write-page { padding: 22px 14px 38px; }
  .editor-workspace :deep(.editor-grid--four) { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .editor-workspace :deep(.editor-section) { padding: 20px 17px; }
  .editor-workspace :deep(.option-row), .editor-workspace :deep(.point-row) { grid-template-columns: 28px minmax(180px, 1fr) auto; overflow-x: auto; }
}

@media (max-width: 560px) {
  .write-header { align-items: flex-start; flex-direction: column; }
  .write-actions { width: 100%; }
  .write-actions .el-button { flex: 1; }
  .guide-status { display: none; }
  .editor-workspace :deep(.editor-grid--four) { grid-template-columns: 1fr; }
  .editor-workspace :deep(.editor-grid__wide) { grid-column: auto; }
}
</style>
