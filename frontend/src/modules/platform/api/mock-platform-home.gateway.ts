import type { PlatformHomeGateway } from './platform-home.gateway'

export class MockPlatformHomeGateway implements PlatformHomeGateway {
  async getModuleHomeSnapshot() {
    return {
      sourceLabel: 'Mock占位数据',
      recentTasks: [
        {
          id: 'mock-practice-task',
          title: '项目进度管理专项练习',
          product: 'practice' as const,
          description: '完成 18 / 30 题',
          updatedAt: '今天 14:20',
          routePath: '/app/practice/home',
          progress: '18 / 30',
          score: '72%',
        },
        {
          id: 'mock-essay-task',
          title: '范围管理论文提纲',
          product: 'essay' as const,
          description: '已完成提纲，等待继续编辑',
          updatedAt: '昨天 20:16',
          routePath: '/app/essay/home',
          progress: '3 / 6',
          score: '待评阅',
        },
        {
          id: 'mock-practice-review',
          title: '综合知识错题复习',
          product: 'practice' as const,
          description: '待复习 12 题',
          updatedAt: '5 月 18 日',
          routePath: '/app/practice/home',
          progress: '45 / 75',
          score: '68%',
        },
        {
          id: 'mock-essay-review',
          title: '项目绩效域论文修改',
          product: 'essay' as const,
          description: '等待查看评阅建议',
          updatedAt: '5 月 17 日',
          routePath: '/app/essay/home',
          progress: '2 / 5',
          score: '修改中',
        },
      ],
      stats: [
        { id: 'study-days', label: '本周学习', value: '5 天' },
        { id: 'practice-count', label: '累计练习', value: '326 题' },
        { id: 'essay-count', label: '论文草稿', value: '4 篇' },
        { id: 'study-hours', label: '累计学习', value: '128.6 小时' },
        { id: 'streak', label: '连续学习', value: '18 天' },
      ],
    }
  }
}
