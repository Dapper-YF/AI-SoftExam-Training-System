import type { PracticePaperSummary, PracticeQuestion } from '../types'

export const practicePapers: PracticePaperSummary[] = Array.from({ length: 6 }, (_, index) => ({
  id: String(1001 + index),
  title: `${2025 - index} 年综合知识真题`,
  qualificationId: 'qualification-high-ipmp',
  subjectId: 'ipmp-knowledge',
  paperType: 'REAL_PAPER',
  yearNo: 2025 - index,
  totalScore: 75,
  durationMinutes: 150,
  status: 'PUBLISHED',
}))

interface QuestionSeed {
  knowledgePoint: string
  stem: string
  options: Array<[string, string]>
  correctAnswer: string
  explanation: string
  source: string
}

const stems: QuestionSeed[] = [
  {
    knowledgePoint: '项目范围管理',
    stem: '在项目范围管理中，WBS 是一种重要的工具和技术。在创建 WBS 时，以下哪项不是 WBS 的基本原则？',
    options: [
      ['A', 'WBS 的底层应该支持详细的成本估算'],
      ['B', 'WBS 的底层应该支持责任的分配'],
      ['C', 'WBS 中的工作包应该是可以独立完成的'],
      ['D', 'WBS 的层次应该越多越好，以确保全面覆盖项目工作'],
    ],
    correctAnswer: 'B',
    explanation: '创建 WBS 应遵循可交付成果导向、百分百原则和适度分解。工作包应可估算、可分配、可独立管理，但层级并非越多越好。',
    source: '《信息系统项目管理师教程（第4版）》第6章 项目范围管理 6.2 范围规划 P169',
  },
  {
    knowledgePoint: '项目进度管理',
    stem: '项目经理正在制定进度计划，哪项技术最适合用于识别项目的最长路径？',
    options: [['A', '关键路径法'], ['B', '资源平衡'], ['C', '趋势分析'], ['D', '挣值分析']],
    correctAnswer: 'A',
    explanation: '关键路径法通过计算活动的最早、最晚开始和完成时间，识别决定项目最短工期的最长路径。',
    source: '《信息系统项目管理师教程（第4版）》第8章 项目进度管理',
  },
  {
    knowledgePoint: '项目质量管理',
    stem: '以下哪项属于质量保证活动，而不是质量控制活动？',
    options: [['A', '过程审计'], ['B', '检查可交付成果'], ['C', '缺陷修复确认'], ['D', '统计抽样']],
    correctAnswer: 'A',
    explanation: '质量保证关注过程是否有效，过程审计是典型活动；质量控制关注具体可交付成果。',
    source: '《信息系统项目管理师教程（第4版）》第10章 项目质量管理',
  },
  {
    knowledgePoint: '项目风险管理',
    stem: '对发生概率低、影响较小的威胁，项目团队通常优先采用哪种应对策略？',
    options: [['A', '规避'], ['B', '转移'], ['C', '接受'], ['D', '上报']],
    correctAnswer: 'C',
    explanation: '当威胁的概率和影响都较低时，可采用接受策略，并在必要时设置应急储备。',
    source: '《信息系统项目管理师教程（第4版）》第12章 项目风险管理',
  },
]

export const practiceQuestions: PracticeQuestion[] = Array.from({ length: 80 }, (_, index) => {
  const seed = index === 11 ? stems[0]! : stems[index % stems.length]!
  return {
    questionId: `question-${index + 1}`,
    questionVersionId: `question-version-${index + 1}`,
    subQuestionId: `sub-question-${index + 1}`,
    number: index + 1,
    typeLabel: '单选题',
    questionType: 'SINGLE_CHOICE',
    knowledgePoint: seed.knowledgePoint,
    stem: index === 0 || index === 11 ? seed.stem : `${seed.stem}（第 ${index + 1} 题）`,
    options: seed.options.map(([code, content]) => ({ code, content })),
    settlementStatus: 'UNSETTLED',
    settlementVersion: 0,
    subQuestions: [{
      subQuestionId: `sub-question-${index + 1}`,
      type: 'SINGLE_CHOICE',
      answerFormat: 'OPTION',
      content: '',
      options: seed.options.map(([code, content]) => ({ code, content })),
      answerVersion: 0,
      marked: false,
      standardAnswer: seed.correctAnswer,
      solution: seed.explanation,
    }],
    correctAnswer: seed.correctAnswer,
    explanation: seed.explanation,
    source: seed.source,
  }
})

export const essayPracticeQuestion: PracticeQuestion = {
  questionId: 'essay-2025-05-high-pm-uncertainty-performance-domain',
  questionVersionId: 'essay-2025-05-high-pm-uncertainty-performance-domain-v1',
  subQuestionId: 'essay-2025-05-high-pm-uncertainty-performance-domain-writing',
  number: 1,
  typeLabel: '论文题',
  questionType: 'ESSAY',
  knowledgePoint: '不确定性绩效域',
  stem: '2025年5月信息系统项目管理师论文题（第2批次回忆版）\n\n请以“论信息系统项目的不确定性绩效域”为题进行论述。\n\n1. 概要叙述你参与管理过的信息系统项目（项目的背景、项目规模、发起单位目的、项目内容、组织结构、项目周期、交付的成果等），并说明你在其中承担的工作（项目背景要求本人真实经历，不得抄袭及杜撰）。\n\n2. 请结合你所叙述的信息系统项目，围绕以下要点论述你对信息系统项目不确定性绩效域的认识：\n（1）请指出在绩效域中不确定性绩效域可以帮助实现哪些项目目标？\n（2）从产品或可交付物角度看，不确定性绩效域与其他7个绩效域相互作用；请根据你所描述的项目，描述不确定性绩效域与其他绩效域的相关关系。\n（3）请根据你的描述，写出项目面临的不确定性及应对方法。',
  options: [],
  settlementStatus: 'UNSETTLED',
  settlementVersion: 0,
  subQuestions: [{
    subQuestionId: 'essay-2025-05-high-pm-uncertainty-performance-domain-writing',
    type: 'ESSAY_WRITING',
    answerFormat: 'TEXT',
    content: '请在答题区完成论文。建议按“摘要、正文、结语”组织内容。',
    metadata: { maxWords: 6000, durationMinutes: 120, sourceEdition: '第2批次回忆版' },
    options: [],
    answerVersion: 0,
    marked: false,
    score: 75,
    solution: '本题为主观论文题，提交后进入人工阅卷或后续评分流程。',
  }],
  source: '用户提供：2025年5月信息系统项目管理师论文题（第2批次回忆版）',
}

export const essayPracticePaper: PracticePaperSummary = {
  id: 'paper-essay-2025-05-high-pm-uncertainty-performance-domain',
  title: '2025年5月信息系统项目管理师论文题（第2批次回忆版）',
  qualificationId: 'qualification-high-ipmp',
  subjectId: 'ipmp-essay',
  paperType: 'RECALL_PAPER',
  yearNo: 2025,
  totalScore: 75,
  durationMinutes: 120,
  status: 'PUBLISHED',
}
