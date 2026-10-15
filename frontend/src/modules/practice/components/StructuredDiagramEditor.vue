<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { VueFlow, type Connection, type Edge, type Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

const props = defineProps<{
  modelValue: Record<string, unknown> | undefined
  readonly?: boolean
  mode: 'FLOWCHART' | 'UML_GRAPH'
}>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>] }>()

const initial = computed(() => props.modelValue ?? { schemaVersion: 'diagram/1.0', nodes: [], edges: [] })
const nodes = ref<Node[]>(Array.isArray(initial.value.nodes) ? initial.value.nodes as Node[] : [])
const edges = ref<Edge[]>(Array.isArray(initial.value.edges) ? initial.value.edges as Edge[] : [])

function publish(): void {
  emit('update:modelValue', {
    schemaVersion: 'diagram/1.0',
    diagramType: props.mode,
    nodes: nodes.value,
    edges: edges.value,
  })
}

function addNode(): void {
  if (props.readonly) return
  const id = `${props.mode.toLowerCase()}-${Date.now()}`
  nodes.value.push({ id, position: { x: 120 + nodes.value.length * 24, y: 90 + nodes.value.length * 24 },
    label: props.mode === 'UML_GRAPH' ? '类 / 接口' : '处理步骤', type: 'default' })
  publish()
}

function connect(connection: Connection): void {
  if (props.readonly) return
  edges.value.push({ ...connection, id: `edge-${Date.now()}`, type: 'smoothstep' })
  publish()
}

watch([nodes, edges], publish, { deep: true })
watch(() => props.modelValue, (value) => {
  if (!value) return
  nodes.value = Array.isArray(value.nodes) ? value.nodes as Node[] : []
  edges.value = Array.isArray(value.edges) ? value.edges as Edge[] : []
}, { deep: true })
</script>

<template>
  <section class="diagram-editor" :data-diagram-type="mode">
    <header>
      <strong>{{ mode === 'UML_GRAPH' ? 'UML 图' : '算法流程图' }}</strong>
      <el-button v-if="!readonly" size="small" @click="addNode">添加节点</el-button>
    </header>
    <VueFlow v-model:nodes="nodes" v-model:edges="edges" :nodes-connectable="!readonly" :nodes-draggable="!readonly" :elements-selectable="!readonly" @connect="connect" @nodes-change="publish" @edges-change="publish">
      <Background pattern-color="#d9e2ec" :gap="16" />
      <Controls v-if="!readonly" />
    </VueFlow>
  </section>
</template>

<style scoped>
.diagram-editor { min-height: 420px; border: 1px solid #ccd8e7; background: #fff; }
.diagram-editor header { display:flex; justify-content:space-between; align-items:center; min-height:42px; padding:0 12px; border-bottom:1px solid #e5ebf2; color:#233b5d; }
.diagram-editor :deep(.vue-flow) { height: 376px; background:#f8fbfe; }
</style>
