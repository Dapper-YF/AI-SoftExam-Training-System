export type PortalFixtureSource = 'DEMO'

export type PortalNewsCategory = '报名动态' | '政策资讯' | '备考资讯'

export interface PortalNewsItem {
  id: string
  source: PortalFixtureSource
  category: PortalNewsCategory
  title: string
  badge?: '置顶' | '提示'
  displayDate: '示例'
}

export interface PortalResourceItem {
  id: string
  source: PortalFixtureSource
  title: string
  tag: '大纲' | '教程' | '图谱' | '模板' | '真题' | '计划'
  displayDate: '示例'
}

export interface PortalTimelineItem {
  id: string
  source: PortalFixtureSource
  title: string
  schedule: '以官方公告为准'
  status: '待公布'
}

export const portalNews: PortalNewsItem[] = [
  { id: 'registration-guide', source: 'DEMO', category: '报名动态', title: '软考报名入口与材料准备清单', badge: '置顶', displayDate: '示例' },
  { id: 'registration-conditions', source: 'DEMO', category: '报名动态', title: '各地区报名条件与常见问题整理', badge: '置顶', displayDate: '示例' },
  { id: 'exam-arrangement', source: 'DEMO', category: '报名动态', title: '信息系统项目管理师考试安排说明', badge: '提示', displayDate: '示例' },
  { id: 'registration-materials', source: 'DEMO', category: '报名动态', title: '报名照片与资格信息填写注意事项', displayDate: '示例' },
  { id: 'registration-check', source: 'DEMO', category: '报名动态', title: '报名信息提交前的核对清单', displayDate: '示例' },
  { id: 'registration-faq', source: 'DEMO', category: '报名动态', title: '软考报名常见问题与处理建议', displayDate: '示例' },
  { id: 'certificate-policy', source: 'DEMO', category: '政策资讯', title: '软考证书领取与信息核验注意事项', displayDate: '示例' },
  { id: 'exam-discipline', source: 'DEMO', category: '政策资讯', title: '考场规则与应试纪律要点整理', displayDate: '示例' },
  { id: 'qualification-policy', source: 'DEMO', category: '政策资讯', title: '职业资格与专业方向选择说明', displayDate: '示例' },
  { id: 'score-policy', source: 'DEMO', category: '政策资讯', title: '成绩查询与复核流程常见问题', displayDate: '示例' },
  { id: 'certificate-check', source: 'DEMO', category: '政策资讯', title: '证书信息查验与领取材料清单', displayDate: '示例' },
  { id: 'policy-reading', source: 'DEMO', category: '政策资讯', title: '如何阅读考试通知中的关键条款', displayDate: '示例' },
  { id: 'essay-topic', source: 'DEMO', category: '备考资讯', title: '高项论文写作：选题方向与结构检查', displayDate: '示例' },
  { id: 'past-paper-guide', source: 'DEMO', category: '备考资讯', title: '历年真题使用方法与阶段复盘建议', displayDate: '示例' },
  { id: 'case-analysis', source: 'DEMO', category: '备考资讯', title: '案例分析题的审题与答题步骤', displayDate: '示例' },
  { id: 'knowledge-review', source: 'DEMO', category: '备考资讯', title: '知识点专项复习与错题回顾方法', displayDate: '示例' },
  { id: 'study-rhythm', source: 'DEMO', category: '备考资讯', title: '在职备考的阶段节奏与时间安排', displayDate: '示例' },
  { id: 'mock-review', source: 'DEMO', category: '备考资讯', title: '模拟考试后的复盘清单与调整建议', displayDate: '示例' },
]

export const portalResources: PortalResourceItem[] = [
  { id: 'exam-outline', source: 'DEMO', title: '信息系统项目管理师考试大纲（示例条目）', tag: '大纲', displayDate: '示例' },
  { id: 'course-notes', source: 'DEMO', title: '综合知识核心概念梳理与复习笔记', tag: '教程', displayDate: '示例' },
  { id: 'knowledge-map', source: 'DEMO', title: '高项知识点图谱与章节索引', tag: '图谱', displayDate: '示例' },
  { id: 'essay-template', source: 'DEMO', title: '论文写作框架与案例复盘模板', tag: '模板', displayDate: '示例' },
  { id: 'paper-index', source: 'DEMO', title: '历年真题整理与答题路径说明', tag: '真题', displayDate: '示例' },
  { id: 'review-plan', source: 'DEMO', title: '阶段复习计划表（可打印示例）', tag: '计划', displayDate: '示例' },
]

export const portalTimeline: PortalTimelineItem[] = [
  { id: 'registration', source: 'DEMO', title: '报名时间', schedule: '以官方公告为准', status: '待公布' },
  { id: 'admission-ticket', source: 'DEMO', title: '准考证打印', schedule: '以官方公告为准', status: '待公布' },
  { id: 'exam', source: 'DEMO', title: '考试时间', schedule: '以官方公告为准', status: '待公布' },
  { id: 'result', source: 'DEMO', title: '成绩查询', schedule: '以官方公告为准', status: '待公布' },
]
