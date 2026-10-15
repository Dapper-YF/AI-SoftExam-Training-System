import { afterEach, describe, expect, it, vi } from 'vitest'
import { platformApiClient } from '@/api/client'
import { HttpPlatformFoundationGateway } from './http-platform-foundation.gateway'

function envelope<T>(data: T) {
  return {
    data: {
      success: true,
      errorCode: 'OK',
      message: 'success',
      data,
      traceId: 'trace-foundation',
    },
  }
}

type JsonRecord = Record<string, unknown>

const verification = {
  sourceUrl: 'https://official.example/rule',
  verifiedAt: '2026-07-01T00:00:00+08:00',
  sourceTitle: 'official notice',
  sourceHash: `sha256:${'a'.repeat(64)}`,
}

function independentSubject(overrides: JsonRecord = {}): JsonRecord {
  return {
    subjectCode: 'APPLIED_TECHNOLOGY',
    subjectType: 'APPLICATION',
    displayOrder: 1,
    durationMinutes: 150,
    fullScore: 75,
    passScore: 45,
    questionTypes: ['CASE'],
    earliestSubmitMinutes: 30,
    forceSubmitMinutes: 150,
    feedbackPolicy: 'AFTER_ATTEMPT',
    ...overrides,
  }
}

function sharedSubject(
  subjectCode: string,
  displayOrder: number,
  overrides: JsonRecord = {},
): JsonRecord {
  return {
    subjectCode,
    subjectType: displayOrder === 1 ? 'COMPREHENSIVE' : 'CASE',
    displayOrder,
    durationMinutes: 150,
    fullScore: 75,
    passScore: 45,
    questionTypes: displayOrder === 1 ? ['SINGLE_CHOICE'] : ['CASE'],
    earliestSubmitMinutes: 30,
    feedbackPolicy: 'AFTER_EXAM',
    linkedGroup: 'HIGH_PM_LINKED_1',
    sequence: displayOrder,
    ...overrides,
  }
}

function independentRule(overrides: JsonRecord = {}): JsonRecord {
  return {
    schemaVersion: '1.0',
    ruleVersion: '2026-H2-v1',
    qualificationCode: 'HIGH_PM',
    examCycle: '2026',
    periodCode: '2026-H2',
    effectiveFrom: '2026-07-01T00:00:00+08:00',
    effectiveTo: '2027-01-01T00:00:00+08:00',
    enabled: true,
    totalDurationMinutes: 150,
    deadlinePolicy: 'INDEPENDENT_SUBJECTS',
    subjects: [independentSubject()],
    verification,
    ...overrides,
  }
}

function sharedRule(overrides: JsonRecord = {}): JsonRecord {
  return {
    schemaVersion: '1.0',
    ruleVersion: '2026-H2-v1',
    qualificationCode: 'HIGH_PM',
    examCycle: '2026',
    periodCode: '2026-H2',
    effectiveFrom: '2026-07-01T00:00:00+08:00',
    effectiveTo: '2027-01-01T00:00:00+08:00',
    enabled: true,
    totalDurationMinutes: 240,
    deadlinePolicy: 'SHARED_TOTAL_DEADLINE',
    sharedForceSubmitMinutes: 240,
    subjects: [
      sharedSubject('COMPREHENSIVE_KNOWLEDGE', 1),
      sharedSubject('CASE_ANALYSIS', 2),
    ],
    verification,
    ...overrides,
  }
}

function ruleRecord(ruleJson: JsonRecord, id = 501) {
  return {
    id,
    qualificationId: 101,
    ruleCode: `RULE-${id}`,
    ruleVersion: '2026-H2-v1',
    status: 'PUBLISHED',
    ruleJson: JSON.stringify(ruleJson),
  }
}

function withoutField(source: JsonRecord, key: string): JsonRecord {
  const result = { ...source }
  delete result[key]
  return result
}

const contractViolationVectors: Array<{
  name: string
  ruleJson: JsonRecord
  expectedError: string
}> = [
  {
    name: 'rejects an unknown feedback policy',
    ruleJson: independentRule({
      subjects: [independentSubject({ feedbackPolicy: 'AFTER_NEVER' })],
    }),
    expectedError: 'feedbackPolicy',
  },
  {
    name: 'rejects a negative earliest-submit offset',
    ruleJson: independentRule({
      subjects: [independentSubject({ earliestSubmitMinutes: -1 })],
    }),
    expectedError: 'earliestSubmitMinutes',
  },
  {
    name: 'rejects earliest-submit after the subject duration',
    ruleJson: independentRule({
      subjects: [independentSubject({ earliestSubmitMinutes: 151 })],
    }),
    expectedError: 'earliestSubmitMinutes',
  },
  {
    name: 'rejects independent force-submit after the subject duration',
    ruleJson: independentRule({
      subjects: [independentSubject({ forceSubmitMinutes: 151 })],
    }),
    expectedError: 'forceSubmitMinutes',
  },
  {
    name: 'rejects explicitly null linkedGroup on an independent subject',
    ruleJson: independentRule({
      subjects: [independentSubject({ linkedGroup: null })],
    }),
    expectedError: '禁止 linkedGroup/sequence',
  },
  {
    name: 'rejects explicitly null sequence on an independent subject',
    ruleJson: independentRule({
      subjects: [independentSubject({ sequence: null })],
    }),
    expectedError: '禁止 linkedGroup/sequence',
  },
  {
    name: 'rejects explicitly null shared force on an independent rule',
    ruleJson: independentRule({ sharedForceSubmitMinutes: null }),
    expectedError: '禁止 sharedForceSubmitMinutes',
  },
  {
    name: 'requires independent total duration to equal the subject sum',
    ruleJson: independentRule({ totalDurationMinutes: 149 }),
    expectedError: '总时长必须等于',
  },
  {
    name: 'rejects duplicate subject codes',
    ruleJson: independentRule({
      totalDurationMinutes: 300,
      subjects: [
        independentSubject(),
        independentSubject({ displayOrder: 2 }),
      ],
    }),
    expectedError: 'subjectCode 不得重复',
  },
  {
    name: 'rejects duplicate display orders',
    ruleJson: independentRule({
      totalDurationMinutes: 300,
      subjects: [
        independentSubject(),
        independentSubject({ subjectCode: 'BASIC_KNOWLEDGE' }),
      ],
    }),
    expectedError: 'displayOrder 不得重复',
  },
  {
    name: 'rejects a one-subject shared rule',
    ruleJson: sharedRule({
      totalDurationMinutes: 150,
      sharedForceSubmitMinutes: 150,
      subjects: [sharedSubject('COMPREHENSIVE_KNOWLEDGE', 1)],
    }),
    expectedError: '至少需要两个科目',
  },
  {
    name: 'requires one linked group in a shared rule',
    ruleJson: sharedRule({
      subjects: [
        sharedSubject('COMPREHENSIVE_KNOWLEDGE', 1),
        sharedSubject('CASE_ANALYSIS', 2, { linkedGroup: 'OTHER_GROUP' }),
      ],
    }),
    expectedError: '同一个 linkedGroup',
  },
  {
    name: 'rejects duplicate shared sequences',
    ruleJson: sharedRule({
      subjects: [
        sharedSubject('COMPREHENSIVE_KNOWLEDGE', 1),
        sharedSubject('CASE_ANALYSIS', 2, { sequence: 1 }),
      ],
    }),
    expectedError: 'sequence 必须从 1 唯一连续',
  },
  {
    name: 'rejects a gap in shared sequences',
    ruleJson: sharedRule({
      subjects: [
        sharedSubject('COMPREHENSIVE_KNOWLEDGE', 1),
        sharedSubject('CASE_ANALYSIS', 2, { sequence: 3 }),
      ],
    }),
    expectedError: 'sequence 必须从 1 唯一连续',
  },
  {
    name: 'rejects explicitly null subject force on a shared rule',
    ruleJson: sharedRule({
      subjects: [
        sharedSubject('COMPREHENSIVE_KNOWLEDGE', 1),
        sharedSubject('CASE_ANALYSIS', 2, { forceSubmitMinutes: null }),
      ],
    }),
    expectedError: '禁止 forceSubmitMinutes',
  },
  {
    name: 'rejects shared total duration below the longest subject',
    ruleJson: sharedRule({
      totalDurationMinutes: 149,
      sharedForceSubmitMinutes: 149,
    }),
    expectedError: '总时长超出允许范围',
  },
  {
    name: 'rejects shared total duration above the subject sum',
    ruleJson: sharedRule({ totalDurationMinutes: 301 }),
    expectedError: '总时长超出允许范围',
  },
  {
    name: 'requires shared force-submit',
    ruleJson: withoutField(sharedRule(), 'sharedForceSubmitMinutes'),
    expectedError: 'sharedForceSubmitMinutes',
  },
  {
    name: 'bounds shared force-submit by total duration',
    ruleJson: sharedRule({ sharedForceSubmitMinutes: 241 }),
    expectedError: 'sharedForceSubmitMinutes 超出总时长',
  },
  {
    name: 'rejects duplicate question types',
    ruleJson: independentRule({
      subjects: [independentSubject({ questionTypes: ['CASE', 'CASE'] })],
    }),
    expectedError: 'questionTypes 不得重复',
  },
  {
    name: 'rejects an unknown question type',
    ruleJson: independentRule({
      subjects: [independentSubject({ questionTypes: ['APPLICATION'] })],
    }),
    expectedError: 'questionTypes',
  },
  {
    name: 'rejects pass score above full score',
    ruleJson: independentRule({
      subjects: [independentSubject({ passScore: 76 })],
    }),
    expectedError: 'passScore 不得大于 fullScore',
  },
  {
    name: 'rejects unknown root fields',
    ruleJson: independentRule({ legacyDeadlineMinutes: 150 }),
    expectedError: '未知字段 legacyDeadlineMinutes',
  },
  {
    name: 'rejects unknown subject fields',
    ruleJson: independentRule({
      subjects: [independentSubject({ legacyFeedback: true })],
    }),
    expectedError: '未知字段 legacyFeedback',
  },
  {
    name: 'rejects unknown verification fields',
    ruleJson: independentRule({
      verification: { ...verification, sourceNote: 'legacy' },
    }),
    expectedError: '未知字段 sourceNote',
  },
  {
    name: 'rejects timezone-less effective timestamps',
    ruleJson: independentRule({ effectiveFrom: '2026-07-01T00:00:00' }),
    expectedError: '带时区的 ISO-8601 时间',
  },
  {
    name: 'rejects non-URI verification sources',
    ruleJson: independentRule({
      verification: { ...verification, sourceUrl: 'official notice without URI' },
    }),
    expectedError: 'sourceUrl 必须是绝对 URI',
  },
  {
    name: 'rejects malformed verification hashes',
    ruleJson: independentRule({
      verification: { ...verification, sourceHash: 'sha256:not-a-digest' },
    }),
    expectedError: 'sourceHash 必须是 sha256 小写摘要',
  },
]

describe('HttpPlatformFoundationGateway', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('unwraps the real qualification page and maps backend DTO names and numeric IDs', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [
        {
          id: 101,
          qualificationCode: 'HIGH_PM',
          qualificationName: '信息系统项目管理师',
          levelCode: 'ADVANCED',
          status: 'ENABLED',
          sortOrder: 10,
        },
      ],
      pageNo: 1,
      pageSize: 100,
      total: 1,
      hasNext: false,
    }) as never)

    await expect(new HttpPlatformFoundationGateway().listQualifications()).resolves.toEqual([
      {
        id: '101',
        code: 'HIGH_PM',
        name: '信息系统项目管理师',
        levelCode: 'ADVANCED',
        status: 'ENABLED',
        sortOrder: 10,
        available: undefined,
        unavailableReason: undefined,
      },
    ])
    expect(get).toHaveBeenCalledWith('/foundation/qualifications', {
      params: { pageNo: 1, pageSize: 100 },
    })
  })

  it('unwraps the real subject page and returns the single frontend subject shape', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [
        {
          id: 201,
          qualificationId: 101,
          subjectCode: 'COMPREHENSIVE_KNOWLEDGE',
          subjectName: '综合知识',
          subjectType: 'COMPREHENSIVE',
          status: 'ENABLED',
          sortOrder: 1,
        },
      ],
      pageNo: 1,
      pageSize: 100,
      total: 1,
      hasNext: false,
    }) as never)

    await expect(new HttpPlatformFoundationGateway().listSubjects('101')).resolves.toEqual([
      {
        id: '201',
        qualificationId: '101',
        code: 'COMPREHENSIVE_KNOWLEDGE',
        name: '综合知识',
        type: 'COMPREHENSIVE',
        status: 'ENABLED',
        sortOrder: 1,
      },
    ])
    expect(get).toHaveBeenCalledWith('/foundation/qualifications/101/subjects', {
      params: { pageNo: 1, pageSize: 100 },
    })
  })

  it('keeps compatibility with an earlier direct-array payload without leaking DTO names', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope([
      {
        id: 'qualification-intermediate',
        code: 'MIDDLE_SOFTWARE_DESIGNER',
        name: '软件设计师',
        levelCode: 'INTERMEDIATE',
        status: 'ENABLED',
      },
    ]) as never)

    const result = await new HttpPlatformFoundationGateway().listQualifications()

    expect(result).toEqual([
      expect.objectContaining({
        id: 'qualification-intermediate',
        code: 'MIDDLE_SOFTWARE_DESIGNER',
        name: '软件设计师',
        levelCode: 'INTERMEDIATE',
      }),
    ])
  })

  it('returns an empty list for an empty page and rejects malformed or legacy enum data', async () => {
    const get = vi.spyOn(platformApiClient, 'get')
    get.mockResolvedValueOnce(envelope({ items: [], pageNo: 1, pageSize: 100, total: 0 }) as never)
    await expect(new HttpPlatformFoundationGateway().listQualifications()).resolves.toEqual([])

    get.mockResolvedValueOnce(envelope({ total: 1 }) as never)
    await expect(new HttpPlatformFoundationGateway().listQualifications())
      .rejects.toThrow('items 必须是数组')

    get.mockResolvedValueOnce(envelope([{
      id: 1,
      qualificationCode: 'HIGH_PM',
      qualificationName: '信息系统项目管理师',
      levelCode: 'HIGH',
      status: 'ENABLED',
    }]) as never)
    await expect(new HttpPlatformFoundationGateway().listQualifications())
      .rejects.toThrow('levelCode')
  })

  it('uses the existing exam-rules endpoint and fails closed when no rule is published', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [],
      pageNo: 1,
      pageSize: 20,
      total: 0,
      hasNext: false,
    }) as never)

    await expect(new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H1'))
      .resolves.toBeNull()
    expect(get).toHaveBeenCalledWith('/foundation/exam-rules', {
      params: { qualificationId: '101', pageNo: 1, pageSize: 100 },
    })
  })

  it('selects the one active requested-period rule and maps nested verification evidence', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-16T00:00:00+08:00'))
    const rule = (
      id: number,
      periodCode: string,
      effectiveFrom: string,
      effectiveTo: string,
      enabled = true,
    ) => ({
      id,
      qualificationId: 101,
      ruleCode: `RULE-${periodCode}`,
      ruleName: periodCode,
      ruleVersion: `${periodCode}-v1`,
      status: 'PUBLISHED',
      ruleJson: JSON.stringify({
        schemaVersion: '1.0',
        ruleVersion: `${periodCode}-v1`,
        qualificationCode: 'HIGH_PM',
        examCycle: '2026',
        periodCode,
        effectiveFrom,
        effectiveTo,
        enabled,
        totalDurationMinutes: 240,
        deadlinePolicy: 'SHARED_TOTAL_DEADLINE',
        sharedForceSubmitMinutes: 240,
        subjects: [{
          subjectCode: 'COMPREHENSIVE_KNOWLEDGE',
          subjectType: 'COMPREHENSIVE',
          displayOrder: 1,
          durationMinutes: 150,
          fullScore: 75,
          passScore: 45,
          questionTypes: ['SINGLE_CHOICE'],
          earliestSubmitMinutes: 120,
          linkedGroup: 'HIGH_PM_LINKED_1',
          sequence: 1,
          feedbackPolicy: 'AFTER_EXAM',
        }, {
          subjectCode: 'CASE_ANALYSIS',
          subjectType: 'CASE',
          displayOrder: 2,
          durationMinutes: 150,
          fullScore: 75,
          passScore: 45,
          questionTypes: ['CASE'],
          earliestSubmitMinutes: 30,
          linkedGroup: 'HIGH_PM_LINKED_1',
          sequence: 2,
          feedbackPolicy: 'AFTER_EXAM',
        }],
        verification: {
          sourceUrl: 'https://official.example/rule',
          verifiedAt: '2026-07-01T00:00:00+08:00',
          sourceTitle: 'official notice',
          sourceHash: `sha256:${'a'.repeat(64)}`,
        },
      }),
    })
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [
        rule(301, '2026-H1', '2026-01-01T00:00:00+08:00', '2026-07-01T00:00:00+08:00'),
        rule(302, '2026-H2', '2026-07-01T00:00:00+08:00', '2027-01-01T00:00:00+08:00'),
      ],
      pageNo: 1,
      pageSize: 100,
      total: 2,
      hasNext: false,
    }) as never)

    const result = await new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H2')

    expect(result).toMatchObject({
      ruleId: '302',
      qualificationId: '101',
      periodCode: '2026-H2',
      ruleVersion: '2026-H2-v1',
      sourceVerifiedAt: '2026-07-01T00:00:00+08:00',
      sharedForceSubmitMinutes: 240,
      subjects: [
        expect.objectContaining({
          earliestSubmitMinutes: 120,
          feedbackPolicy: 'AFTER_EXAM',
          linkedGroup: 'HIGH_PM_LINKED_1',
          sequence: 1,
        }),
        expect.objectContaining({
          earliestSubmitMinutes: 30,
          feedbackPolicy: 'AFTER_EXAM',
          linkedGroup: 'HIGH_PM_LINKED_1',
          sequence: 2,
        }),
      ],
    })
  })

  it('returns unavailable for a disabled or expired requested-period rule', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-16T00:00:00+08:00'))
    const records = [
      {
        id: 301,
        qualificationId: 101,
        ruleCode: 'RULE-H1',
        ruleVersion: '2026-H1-v1',
        status: 'PUBLISHED',
        ruleJson: JSON.stringify({
          schemaVersion: '1.0',
          ruleVersion: '2026-H1-v1',
          qualificationCode: 'HIGH_PM',
          examCycle: '2026',
          periodCode: '2026-H1',
          effectiveFrom: '2026-01-01T00:00:00+08:00',
          effectiveTo: '2026-07-01T00:00:00+08:00',
          enabled: true,
          totalDurationMinutes: 150,
          deadlinePolicy: 'INDEPENDENT_SUBJECTS',
          subjects: [],
          verification: {},
        }),
      },
    ]
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: records,
      pageNo: 1,
      pageSize: 100,
      total: 1,
      hasNext: false,
    }) as never)

    await expect(new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H1'))
      .resolves.toBeNull()
  })

  it('rejects malformed or ambiguous active requested-period rules', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-16T00:00:00+08:00'))
    const validJson = {
      schemaVersion: '1.0',
      ruleVersion: '2026-H2-v1',
      qualificationCode: 'HIGH_PM',
      examCycle: '2026',
      periodCode: '2026-H2',
      effectiveFrom: '2026-07-01T00:00:00+08:00',
      effectiveTo: '2027-01-01T00:00:00+08:00',
      enabled: true,
      totalDurationMinutes: 150,
      deadlinePolicy: 'INDEPENDENT_SUBJECTS',
      subjects: [{
        subjectCode: 'COMPREHENSIVE_KNOWLEDGE',
        subjectType: 'COMPREHENSIVE',
        displayOrder: 1,
        durationMinutes: 150,
        fullScore: 75,
        passScore: 45,
        questionTypes: ['SINGLE_CHOICE'],
        earliestSubmitMinutes: 120,
        forceSubmitMinutes: 150,
        feedbackPolicy: 'AFTER_EXAM',
      }],
      verification: {
        sourceUrl: 'https://official.example/rule',
        verifiedAt: '2026-07-01T00:00:00+08:00',
        sourceTitle: 'official notice',
        sourceHash: `sha256:${'a'.repeat(64)}`,
      },
    }
    const record = (id: number, ruleJson: object) => ({
      id,
      qualificationId: 101,
      ruleCode: `RULE-${id}`,
      ruleVersion: '2026-H2-v1',
      status: 'PUBLISHED',
      ruleJson: JSON.stringify(ruleJson),
    })
    const get = vi.spyOn(platformApiClient, 'get')
    get.mockResolvedValueOnce(envelope({
      items: [record(401, { ...validJson, verification: {} })],
    }) as never)
    await expect(new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H2'))
      .rejects.toThrow('verification')

    get.mockResolvedValueOnce(envelope({
      items: [record(402, validJson), record(403, validJson)],
    }) as never)
    await expect(new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H2'))
      .rejects.toThrow('多条有效考试规则')
  })

  it('preserves independent submit and feedback fields at their inclusive boundaries', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-16T00:00:00+08:00'))
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [ruleRecord(independentRule({
        subjects: [independentSubject({
          earliestSubmitMinutes: 0,
          forceSubmitMinutes: 150,
          feedbackPolicy: 'AFTER_QUESTION',
        })],
      }))],
    }) as never)

    const result = await new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H2')

    expect(result).not.toHaveProperty('sharedForceSubmitMinutes')
    expect(result?.subjects).toEqual([
      expect.objectContaining({
        earliestSubmitMinutes: 0,
        forceSubmitMinutes: 150,
        feedbackPolicy: 'AFTER_QUESTION',
      }),
    ])
    expect(result?.subjects[0]).not.toHaveProperty('linkedGroup')
    expect(result?.subjects[0]).not.toHaveProperty('sequence')
  })

  it.each(contractViolationVectors)('$name', async ({ ruleJson, expectedError }) => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-16T00:00:00+08:00'))
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [ruleRecord(ruleJson)],
    }) as never)

    await expect(new HttpPlatformFoundationGateway().getActiveExamRule('101', '2026-H2'))
      .rejects.toThrow(expectedError)
  })
})
