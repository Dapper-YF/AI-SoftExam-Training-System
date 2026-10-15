export type PortalNewsCategory = 'REGISTRATION' | 'POLICY'

export interface PortalContentItem {
  id: string
  title: string
  summary: string
  publishedAt: string
  category: 'REGISTRATION' | 'POLICY' | 'OFFICIAL_RESOURCE'
  tag: string
  sourceName: string
  sourceUrl: string
}

export interface PortalContentGateway {
  listNews(category: PortalNewsCategory, pageSize?: number): Promise<PortalContentItem[]>
  listResources(pageSize?: number): Promise<PortalContentItem[]>
}
