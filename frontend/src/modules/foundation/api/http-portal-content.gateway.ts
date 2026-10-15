import { platformApiClient, unwrapApiResponse } from '@/api/client'
import type { PortalContentGateway, PortalContentItem, PortalNewsCategory } from './portal-content.gateway'

type JsonObject = Record<string, unknown>

const CATEGORIES = new Set<PortalContentItem['category']>(['REGISTRATION', 'POLICY', 'OFFICIAL_RESOURCE'])

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(value: unknown, label: string): string {
  if (typeof value === 'string' && value.trim()) return value.trim()
  throw new Error(`${label} response is invalid`)
}

function mapItem(value: unknown): PortalContentItem {
  if (!isObject(value)) throw new Error('Portal content response is invalid')
  const category = requiredString(value.category, 'Portal content category') as PortalContentItem['category']
  if (!CATEGORIES.has(category)) throw new Error('Portal content category response is invalid')
  const sourceUrl = requiredString(value.sourceUrl, 'Portal content sourceUrl')
  const parsedUrl = new URL(sourceUrl)
  if (parsedUrl.protocol !== 'https:') throw new Error('Portal content sourceUrl response is invalid')
  return {
    id: String(value.id),
    title: requiredString(value.title, 'Portal content title'),
    summary: requiredString(value.summary, 'Portal content summary'),
    publishedAt: requiredString(value.publishedAt, 'Portal content publishedAt'),
    category,
    tag: requiredString(value.tag, 'Portal content tag'),
    sourceName: requiredString(value.sourceName, 'Portal content sourceName'),
    sourceUrl,
  }
}

function mapItems(value: unknown): PortalContentItem[] {
  if (!Array.isArray(value)) throw new Error('Portal content list response is invalid')
  return value.map(mapItem)
}

export class HttpPortalContentGateway implements PortalContentGateway {
  async listNews(category: PortalNewsCategory, pageSize = 6): Promise<PortalContentItem[]> {
    return mapItems(await unwrapApiResponse(platformApiClient.get('/public/portal/news', { params: { category, pageSize } })))
  }

  async listResources(pageSize = 6): Promise<PortalContentItem[]> {
    return mapItems(await unwrapApiResponse(platformApiClient.get('/public/portal/resources', { params: { pageSize } })))
  }
}
