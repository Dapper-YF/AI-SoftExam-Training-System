<script setup lang="ts">
import { computed } from 'vue'

type Metric = { name?: string; target?: string; threshold?: string; unit?: string; formula?: string }
const props = defineProps<{ modelValue?: Record<string, unknown>; type: string; readonly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>] }>()
const model = computed(() => props.modelValue ?? { schemaVersion: '1.0', type: props.type })
const content = computed({
  get: () => String(model.value.content ?? ''),
  set: (value: string) => update({ content: value }),
})
const metrics = computed<Metric[]>(() => Array.isArray(model.value.metrics) ? model.value.metrics as Metric[] : [{ name: '', target: '', threshold: '', unit: '', formula: '' }])

function update(fields: Record<string, unknown>) {
  emit('update:modelValue', { schemaVersion: '1.0', type: props.type, ...model.value, ...fields })
}
function updateMetric(index: number, field: keyof Metric, value: string) {
  const next = metrics.value.map((item) => ({ ...item }))
  next[index] = { ...next[index], [field]: value }
  update({ metrics: next })
}
function addMetric() { update({ metrics: [...metrics.value, { name: '', target: '', threshold: '', unit: '', formula: '' }] }) }
function removeMetric(index: number) { update({ metrics: metrics.value.filter((_, item) => item !== index) }) }
</script>

<template>
  <div class="structured-editor">
    <template v-if="type === 'CODE_GAP'">
      <label>补全代码</label><el-input v-model="content" type="textarea" :rows="14" :readonly="readonly" spellcheck="false" placeholder="填写完整代码或代码片段" />
    </template>
    <template v-else-if="type === 'FILL_BLANK'">
      <label>填空答案</label><el-input v-model="content" type="textarea" :rows="5" :readonly="readonly" placeholder="每个空一行，按题目顺序填写" />
    </template>
    <template v-else-if="type === 'METRIC_CONFIGURATION'">
      <div class="metric-toolbar"><strong>服务指标</strong><el-button size="small" :disabled="readonly" @click="addMetric">新增指标</el-button></div>
      <div v-for="(metric, index) in metrics" :key="index" class="metric-row">
        <el-input :model-value="metric.name" :disabled="readonly" placeholder="指标名称" @update:model-value="updateMetric(index, 'name', $event)" />
        <el-input :model-value="metric.target" :disabled="readonly" placeholder="目标值" @update:model-value="updateMetric(index, 'target', $event)" />
        <el-input :model-value="metric.threshold" :disabled="readonly" placeholder="阈值" @update:model-value="updateMetric(index, 'threshold', $event)" />
        <el-input :model-value="metric.unit" :disabled="readonly" placeholder="单位" @update:model-value="updateMetric(index, 'unit', $event)" />
        <el-input :model-value="metric.formula" :disabled="readonly" placeholder="计算口径" @update:model-value="updateMetric(index, 'formula', $event)" />
        <el-button text type="danger" :disabled="readonly || metrics.length === 1" @click="removeMetric(index)">删除</el-button>
      </div>
    </template>
    <template v-else>
      <label>{{ type === 'REQUIREMENT_SPEC' ? '需求规格补全' : type === 'SERVICE_DESIGN' ? '服务方案设计' : '架构方案论述' }}</label>
      <el-input v-model="content" type="textarea" :rows="12" :readonly="readonly" placeholder="按题目要求填写结构化方案内容" />
    </template>
    <small>答案包含 schemaVersion，并按题目版本保存快照。</small>
  </div>
</template>

<style scoped>
.structured-editor { display:flex; flex-direction:column; gap:10px; }.structured-editor label { font-weight:600; color:#374151; }.structured-editor small { color:#6b7280; }.metric-toolbar { display:flex; justify-content:space-between; align-items:center; }.metric-row { display:grid; grid-template-columns:1.4fr 1fr 1fr .8fr 1.6fr auto; gap:8px; align-items:center; }
@media (max-width: 900px) { .metric-row { grid-template-columns:1fr 1fr; } }
</style>
