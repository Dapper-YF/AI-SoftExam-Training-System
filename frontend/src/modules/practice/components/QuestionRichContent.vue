<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ content: string }>()

type Segment =
  | { kind: 'text'; value: string }
  | { kind: 'image'; fileName: string }

const marker = /\[图片:\s*([^\]]+)\]/g
const safeFileName = /^[0-9a-f]{16}\.(?:png|jpe?g|gif|webp)$/i

function fileName(value: string): string | null {
  const candidate = value.trim().replaceAll('\\', '/').split('/').at(-1) ?? ''
  return safeFileName.test(candidate) ? candidate : null
}

const segments = computed<Segment[]>(() => {
  const result: Segment[] = []
  let cursor = 0
  for (const match of props.content.matchAll(marker)) {
    const index = match.index ?? 0
    if (index > cursor) result.push({ kind: 'text', value: props.content.slice(cursor, index) })
    const image = fileName(match[1] ?? '')
    result.push(image ? { kind: 'image', fileName: image } : { kind: 'text', value: match[0] })
    cursor = index + match[0].length
  }
  if (cursor < props.content.length) result.push({ kind: 'text', value: props.content.slice(cursor) })
  return result.length ? result : [{ kind: 'text', value: props.content }]
})

function imageUrl(name: string): string {
  return `/api/v1/public/question-assets/${encodeURIComponent(name)}`
}
</script>

<template>
  <div class="question-rich-content">
    <template v-for="(segment, index) in segments" :key="index">
      <span v-if="segment.kind === 'text'">{{ segment.value }}</span>
      <img
        v-else
        class="question-rich-content__image"
        :src="imageUrl(segment.fileName)"
        :alt="`题目图片 ${index + 1}`"
        loading="lazy"
      />
    </template>
  </div>
</template>
