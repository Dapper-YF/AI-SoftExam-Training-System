import type { ProductNavigation } from '@/types/navigation'

export const productNavigation: Record<ProductNavigation['code'], ProductNavigation> = {
  practice: {
    code: 'practice',
    label: '软考刷题',
    homePath: '/practice',
    items: [
      { code: 'practice.home', label: '题库目录', routePath: '/practice', icon: 'home' },
      { code: 'practice.start', label: '刷题练习', routePath: '/practice', icon: 'library' },
      { code: 'practice.exam', label: '模拟考试', routePath: '/exam-simulator', icon: 'exam' },
      { code: 'practice.review', label: '学习工具', routePath: '/tools', icon: 'review' },
      { code: 'practice.reports', label: '练习历史', routePath: '/app/practice/history', icon: 'report' },
      { code: 'practice.resources', label: '学习资源', routePath: '/app/practice/resources', icon: 'resources' },
    ],
  },
  essay: {
    code: 'essay',
    label: 'AI 论文',
    homePath: '/app/essay/home',
    items: [
      { code: 'essay.home', label: '工作区首页', routePath: '/app/essay/home', icon: 'home' },
      { code: 'essay.editor', label: '论文写作', routePath: '/app/essay/editor', icon: 'edit' },
      { code: 'essay.reports', label: '评阅报告', routePath: '/app/essay/reports', icon: 'report' },
    ],
  },
}
