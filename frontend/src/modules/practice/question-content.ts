export function normalizeQuestionText(value: string): string {
  return value.trim().replace(/\s+/g, ' ')
}

function questionTextKey(value: string): string {
  return value.normalize('NFKC').replace(/\s+/g, '')
}

function questionSegments(value: string): string[] {
  return value
    .split(/\r?\n/)
    .flatMap((line) => line.match(/[^。！？!?；;]+[。！？!?；;]?/g) ?? [])
    .map((segment) => segment.trim())
    .filter(Boolean)
}

export function collapseRepeatedQuestionText(value: string): string {
  const content = value.trim()
  const segments = questionSegments(content)
  if (segments.length < 2) return content

  for (let period = 1; period <= Math.floor(segments.length / 2); period += 1) {
    if (segments.length % period !== 0) continue
    const repeated = segments.every((segment, index) => (
      index < period || questionTextKey(segment) === questionTextKey(segments[index % period]!)
    ))
    if (repeated) return segments.slice(0, period).join(' ')
  }
  return content
}

export function distinctQuestionTexts(values: string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  values.forEach((value) => {
    const content = collapseRepeatedQuestionText(value)
    const normalized = questionTextKey(content)
    if (!normalized || seen.has(normalized)) return
    seen.add(normalized)
    result.push(content)
  })
  return result
}

export function isSameQuestionText(left: string, right: string): boolean {
  const normalizedLeft = questionTextKey(collapseRepeatedQuestionText(left))
  return Boolean(normalizedLeft)
    && normalizedLeft === questionTextKey(collapseRepeatedQuestionText(right))
}
