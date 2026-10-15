import { computed } from 'vue'
import { defineStore } from 'pinia'
import { useRoute } from 'vue-router'
import { productNavigation } from '@/modules/platform/navigation'
import type { ProductCode } from '@/types/navigation'

export const useProductShellStore = defineStore('product-shell', () => {
  const route = useRoute()
  const currentProduct = computed<ProductCode | null>(() => {
    if (route.path.startsWith('/app/practice')) return 'practice'
    if (route.path.startsWith('/app/essay')) return 'essay'
    return null
  })
  const navigation = computed(() => currentProduct.value ? productNavigation[currentProduct.value] : null)

  return { currentProduct, navigation }
})
