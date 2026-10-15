import { defineComponent } from 'vue'

export async function readDevVerificationCode(): Promise<string> {
  return ''
}

export default defineComponent({
  name: 'ProductionNoop',
  render: () => null,
})
