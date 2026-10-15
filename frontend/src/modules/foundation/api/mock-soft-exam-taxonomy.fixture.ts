import type { LevelCode, Qualification, Subject, SubjectType } from '../types'

interface QualificationSeed {
  code: string
  name: string
  levelCode: LevelCode
  sortOrder: number
  available: boolean
}

export interface MockPracticeHomePreview {
  ruleNotice: string
  recentTask: {
    title: string
    tag: string
    progress: string
    percent: string
    savedAt: string
  }
  taskMeta: {
    daily: string
  }
  pastPapers: Array<{ year: string; count: number; status: string }>
  today: {
    practiceCount: string
    practiceTrend: string
    correctCount: string
    correctTrend: string
    duration: string
    durationTrend: string
  }
  review: {
    mistakeCount: string
    favoriteCount: string
    weakPoints: Array<{ rank: number; name: string; count: number }>
  }
}

const qualificationSeeds: QualificationSeed[] = [
  { code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', sortOrder: 10, available: true },
  { code: 'HIGH_SYSTEM_PLANNING_MANAGER', name: '系统规划与管理师', levelCode: 'ADVANCED', sortOrder: 20, available: false },
  { code: 'HIGH_SYSTEM_ANALYST', name: '系统分析师', levelCode: 'ADVANCED', sortOrder: 30, available: true },
  { code: 'HIGH_SYSTEM_ARCHITECT', name: '系统架构设计师', levelCode: 'ADVANCED', sortOrder: 40, available: true },
  { code: 'HIGH_NETWORK_PLANNER', name: '网络规划设计师', levelCode: 'ADVANCED', sortOrder: 50, available: false },
  { code: 'MIDDLE_SOFTWARE_DESIGNER', name: '软件设计师', levelCode: 'INTERMEDIATE', sortOrder: 110, available: true },
  { code: 'MIDDLE_NETWORK_ENGINEER', name: '网络工程师', levelCode: 'INTERMEDIATE', sortOrder: 120, available: true },
  { code: 'MIDDLE_INFORMATION_SYSTEM_SUPERVISOR', name: '信息系统监理师', levelCode: 'INTERMEDIATE', sortOrder: 130, available: true },
  { code: 'MIDDLE_SYSTEM_INTEGRATION_PM', name: '系统集成项目管理工程师', levelCode: 'INTERMEDIATE', sortOrder: 140, available: true },
  { code: 'MIDDLE_EMBEDDED_SYSTEM_DESIGNER', name: '嵌入式系统设计师', levelCode: 'INTERMEDIATE', sortOrder: 150, available: true },
  { code: 'MIDDLE_ECOMMERCE_DESIGNER', name: '电子商务设计师', levelCode: 'INTERMEDIATE', sortOrder: 160, available: true },
  { code: 'MIDDLE_DATABASE_SYSTEM_ENGINEER', name: '数据库系统工程师', levelCode: 'INTERMEDIATE', sortOrder: 170, available: true },
  { code: 'MIDDLE_SOFTWARE_TESTER', name: '软件评测师', levelCode: 'INTERMEDIATE', sortOrder: 180, available: true },
  { code: 'MIDDLE_INFORMATION_SYSTEM_MANAGER', name: '信息系统管理工程师', levelCode: 'INTERMEDIATE', sortOrder: 190, available: false },
  { code: 'MIDDLE_INFORMATION_SECURITY_ENGINEER', name: '信息安全工程师', levelCode: 'INTERMEDIATE', sortOrder: 200, available: false },
  { code: 'MIDDLE_MULTIMEDIA_APPLICATION_DESIGNER', name: '多媒体应用设计师', levelCode: 'INTERMEDIATE', sortOrder: 210, available: false },
  { code: 'LOW_PROGRAMMER', name: '程序员', levelCode: 'PRIMARY', sortOrder: 310, available: false },
  { code: 'LOW_NETWORK_ADMINISTRATOR', name: '网络管理员', levelCode: 'PRIMARY', sortOrder: 320, available: false },
  { code: 'LOW_INFORMATION_SYSTEM_OPERATOR', name: '信息系统运行管理员', levelCode: 'PRIMARY', sortOrder: 330, available: true },
  { code: 'LOW_INFORMATION_PROCESSING_TECHNICIAN', name: '信息处理技术员', levelCode: 'PRIMARY', sortOrder: 340, available: true },
]

const advancedSubjects = [
  { code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', sortOrder: 1 },
  { code: 'CASE_ANALYSIS', name: '案例分析', type: 'CASE', sortOrder: 2 },
  { code: 'ESSAY', name: '论文', type: 'ESSAY', sortOrder: 3 },
] satisfies Array<{ code: string; name: string; type: SubjectType; sortOrder: number }>

const standardSubjects = [
  { code: 'BASIC_KNOWLEDGE', name: '基础知识', type: 'COMPREHENSIVE', sortOrder: 1 },
  { code: 'APPLIED_TECHNOLOGY', name: '应用技术', type: 'APPLICATION', sortOrder: 2 },
] satisfies Array<{ code: string; name: string; type: SubjectType; sortOrder: number }>

function slug(value: string): string {
  return value.toLowerCase().replaceAll('_', '-')
}

function qualificationId(code: string): string {
  return code === 'HIGH_PM' ? 'qualification-high-ipmp' : `qualification-${slug(code)}`
}

function subjectId(qualificationCode: string, subjectCode: string): string {
  if (qualificationCode === 'HIGH_PM') {
    if (subjectCode === 'COMPREHENSIVE_KNOWLEDGE') return 'ipmp-knowledge'
    if (subjectCode === 'CASE_ANALYSIS') return 'ipmp-case'
    if (subjectCode === 'ESSAY') return 'ipmp-essay'
  }
  return `subject-${slug(qualificationCode)}-${slug(subjectCode)}`
}

export function createMockSoftExamTaxonomy(): {
  qualifications: Qualification[]
  subjectsByQualification: Record<string, Subject[]>
} {
  const qualifications: Qualification[] = qualificationSeeds.map((seed) => ({
    id: qualificationId(seed.code),
    code: seed.code,
    name: seed.name,
    levelCode: seed.levelCode,
    status: 'ENABLED',
    sortOrder: seed.sortOrder,
    available: seed.available,
    unavailableReason: seed.available ? undefined : 'NO_PUBLISHED_RULE',
  }))
  const subjectsByQualification: Record<string, Subject[]> = Object.fromEntries(
    qualificationSeeds.map((qualification) => {
      const id = qualificationId(qualification.code)
      const subjectSeeds = qualification.levelCode === 'ADVANCED' ? advancedSubjects : standardSubjects
      return [id, subjectSeeds.map((subject) => ({
        id: subjectId(qualification.code, subject.code),
        qualificationId: id,
        code: subject.code,
        name: subject.name,
        type: subject.type,
        status: 'ENABLED',
        sortOrder: subject.sortOrder,
      }))]
    }),
  )
  return { qualifications, subjectsByQualification }
}

export function createMockPracticeHomePreview(): MockPracticeHomePreview {
  return {
    ruleNotice: 'DEV 规则预览 · active-rule 尚待正式联调',
    recentTask: {
      title: '第 6 章　项目范围管理',
      tag: '章节练习',
      progress: '进度：18 / 45 题',
      percent: '40%',
      savedAt: '上次保存：今天 10:32',
    },
    taskMeta: {
      daily: '按当前资格与默认科目组题',
    },
    pastPapers: [
      { year: '2025 上半年', count: 75, status: '未开始' },
      { year: '2024 下半年', count: 75, status: '继续练习' },
    ],
    today: {
      practiceCount: '45',
      practiceTrend: '较昨日 +12',
      correctCount: '32',
      correctTrend: '正确率 71.11%',
      duration: '68',
      durationTrend: '较昨日 +18 分钟',
    },
    review: {
      mistakeCount: '86',
      favoriteCount: '24',
      weakPoints: [
        { rank: 1, name: '项目范围管理', count: 28 },
        { rank: 2, name: '项目进度管理', count: 19 },
        { rank: 3, name: '风险管理', count: 15 },
      ],
    },
  }
}
