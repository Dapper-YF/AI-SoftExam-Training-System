import { isEssayPreviewEnabled } from '../essay-state'
import type { EssayGateway } from './essay.gateway'
import { HttpEssayGateway } from './http-essay.gateway'

let testGateway: EssayGateway | null = null

export async function getEssayGateway(): Promise<EssayGateway> {
  if (testGateway) return testGateway
  if (isEssayPreviewEnabled(import.meta.env.DEV, import.meta.env.VITE_ESSAY_DEV_PREVIEW)) {
    const { PreviewEssayGateway } = await import('./preview-essay.gateway')
    return new PreviewEssayGateway()
  }
  return new HttpEssayGateway()
}

export function setEssayGatewayForTests(gateway: EssayGateway | null): void {
  if (import.meta.env.MODE === 'test') testGateway = gateway
}
