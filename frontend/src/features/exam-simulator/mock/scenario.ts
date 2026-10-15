import type {
  DemoExamContext,
  ExamCandidate,
  ExamEntryCode,
  ExamPaper,
  ExamQuestion,
  ExamRules,
} from '../types'
import {
  SHARED_DEMO_ADMISSION_TICKET,
  SHARED_DEMO_CREDENTIAL_HINT,
  SHARED_DEMO_IDENTITY_NUMBER,
} from '../config/shared-demo-credential'

export const demoExamContext: DemoExamContext = {
  stationNo: '010',
  admissionTicket: SHARED_DEMO_ADMISSION_TICKET,
  identityNumber: SHARED_DEMO_IDENTITY_NUMBER,
  hint: SHARED_DEMO_CREDENTIAL_HINT,
}

export function getStartDelaySeconds(): number {
  const configured = Number(import.meta.env.VITE_EXAM_SIMULATOR_START_DELAY_SECONDS ?? 15)
  return Number.isFinite(configured) && configured >= 0 ? configured : 15
}

export function createMockCandidate(entryCode: ExamEntryCode): ExamCandidate {
  return {
    maskedAdmissionTicket: '1111********',
    name: '张晓明',
    gender: '男',
    qualification: '信息系统项目管理师',
    subject: entryCode === 'ESSAY' ? '论文' : '综合知识 + 案例分析',
    maskedIdentityNumber: '111111********1111',
    stationNo: demoExamContext.stationNo,
  }
}

export const mockRules: ExamRules = {
  version: '2026-HN-P0-v1',
  region: '模拟考区',
  examYear: 2026,
  requireScrollToEnd: false,
  tabs: [
    {
      code: 'RULES',
      title: '考场规则',
      paragraphs: [
        '一、应试人员须按模拟场次要求进入本系统，核对准考证号、有效证件号、姓名、考试资格和机位号。发现信息有误时，应返回登录页重新核对。',
        '二、本次考试采用计算机化作答。考试开始后，系统按照服务器时间连续计时，不因页面刷新、切换标签页或延迟进入而暂停。',
        '三、应试人员须独立完成作答，不得通过任何方式查看答案、解析或 AI 答疑内容。',
        '四、综合知识与案例分析采用联考计时。综合知识交卷后，剩余联考时间继续用于案例分析，科目切换不会重置倒计时。',
        '五、考试结束时系统自动交卷。主动交卷须遵守当前场次配置的最早交卷时间，并完成未答题提醒和交卷确认。',
        '六、作答过程中如出现保存失败或试卷加载异常，请保留当前页面并按提示联系监考人员，不得反复登录或重新开始考试。',
        '七、本系统用于机考流程演练，题型、题量、分值和文字内容不代表正式考试安排，正式考试以当次准考证和主管部门通知为准。',
      ],
    },
    {
      code: 'GUIDE',
      title: '操作指南',
      paragraphs: [
        '一、左侧答题卡用于切换题目。白色表示未作答，蓝色表示已作答，橙色表示当前题，题号右上角标记表示该题已被标记。',
        '二、选择客观题答案后，系统会保存当前答案。请留意顶栏的保存状态；只有保存成功后，答题卡才显示为已答。',
        '三、使用“上一题”“下一题”或答题卡切换题目。切换前系统会完成当前答案保存。',
        '四、计算器、画图、输入法和设置入口在本阶段仅作位置演示，暂不可用，不影响选择题和文本题作答。',
        '五、点击“交卷”后，系统将检查未答题和标记题。交卷成功后不可返回修改答案。',
      ],
    },
  ],
}

const choicePrompts = [
  '“新型基础设施”主要包括信息基础设施、融合基础设施和创新基础设施三个方面。其中信息基础设施包括（ ）。',
  '关于项目范围管理过程的描述，正确的是（ ）。',
  '在项目进度控制中，用于展示活动逻辑关系的工具是（ ）。',
  '配置管理活动中，建立和维护产品完整性的过程是（ ）。',
]

function createChoiceQuestion(index: number): ExamQuestion {
  const prompt = choicePrompts[index % choicePrompts.length] ?? choicePrompts[0]!
  return {
    questionId: 10_000 + index,
    subQuestionId: 20_000 + index,
    displayNo: index + 1,
    questionType: 'SINGLE_CHOICE',
    groupName: '单项选择题',
    prompt,
    score: 1,
    options: [
      { key: 'A', content: '通信基础设施、智能交通基础设施和新技术基础设施' },
      { key: 'B', content: '通信网络基础设施、新技术基础设施和算力基础设施' },
      { key: 'C', content: '智能交通基础设施、智慧能源基础设施和科教基础设施' },
      { key: 'D', content: '算力基础设施、传统基础设施和公共服务基础设施' },
    ],
  }
}

export function createMockPaper(entryCode: ExamEntryCode): ExamPaper {
  if (entryCode === 'ESSAY') {
    return {
      paperId: 92002,
      title: '2026年信息系统项目管理师论文模拟试卷',
      qualification: '信息系统项目管理师',
      subject: '论文',
      instructions: ['本试卷共 1 道论文题，请按要求完成写作。', '考试过程中不显示答案、解析或写作提示。'],
      questions: [
        {
          questionId: 30_001,
          subQuestionId: 40_001,
          displayNo: 1,
          questionType: 'ESSAY_WRITING',
          groupName: '论文题',
          prompt: '请以“论信息系统项目的范围管理”为题，结合本人参与管理的信息系统项目，论述范围管理过程及实践。',
          score: 75,
          options: [],
          wordLimit: 2500,
        },
      ],
    }
  }

  return {
    paperId: 92001,
    title: '信息系统项目管理师模拟试卷（综合知识）',
    qualification: '信息系统项目管理师',
    subject: '综合知识',
    instructions: ['本试卷共有 60 道单项选择题，请选择一个最恰当的选项。', '考试过程中不显示答案和解析。'],
    questions: Array.from({ length: 60 }, (_, index) => createChoiceQuestion(index)),
  }
}
