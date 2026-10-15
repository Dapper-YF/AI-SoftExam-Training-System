export function isExplicitMockEnabled(value: string | undefined): boolean {
  return value === 'true'
}

export function resolveGatewayKind(value: string | undefined): 'http' | 'mock' {
  return isExplicitMockEnabled(value) ? 'mock' : 'http'
}
