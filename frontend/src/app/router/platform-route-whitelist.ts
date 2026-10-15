import type { PlatformMenu, PlatformRole } from '@/modules/foundation/types'
import { resolvePlatformAccess, type PlatformAccessContext } from './platform-guards'

interface WhitelistedMenu {
  code: string
  name: string
  routePath: string
  icon: string
  roles: PlatformRole[]
  permissions: string[]
  featureFlag: string
}

const QUESTION_MANAGEMENT_ROLES: PlatformRole[] = [
  'ADMIN',
  'QUESTION_AUTHOR',
  'QUESTION_REVIEWER',
  'QUESTION_PUBLISHER',
]

const menuWhitelist: Record<string, WhitelistedMenu> = {
  'student.home': {
    code: 'student.home',
    name: '学习首页',
    routePath: '/app/home',
    icon: 'HomeFilled',
    roles: ['STUDENT'],
    permissions: ['student:home:view'],
    featureFlag: 'student.portal',
  },
  'admin.home': {
    code: 'admin.home',
    name: '管理首页',
    routePath: '/admin/home',
    icon: 'Grid',
    roles: ['ADMIN'],
    permissions: ['admin:home:view'],
    featureFlag: 'admin.portal',
  },
  'admin.questions': {
    code: 'admin.questions',
    name: '题库管理',
    routePath: '/admin/questions',
    icon: 'Collection',
    roles: QUESTION_MANAGEMENT_ROLES,
    permissions: ['question:read'],
    featureFlag: 'admin.questions',
  },
  'admin.practice-reviews': {
    code: 'admin.practice-reviews',
    name: '主观题评分',
    routePath: '/admin/practice-reviews',
    icon: 'review',
    roles: QUESTION_MANAGEMENT_ROLES,
    permissions: ['question:review'],
    featureFlag: 'admin.questions',
  },
  'admin.practice-taxonomy': {
    code: 'admin.practice-taxonomy',
    name: '章节与知识点',
    routePath: '/admin/practice-taxonomy',
    icon: 'Reading',
    roles: ['ADMIN'],
    permissions: ['admin:home:view'],
    featureFlag: 'admin.questions',
  },
  'admin.papers': {
    code: 'admin.papers', name: '试卷管理', routePath: '/admin/papers', icon: 'Document',
    roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.questions',
  },
  'admin.contents': {
    code: 'admin.contents',
    name: '资讯内容审核',
    routePath: '/admin/contents',
    icon: 'Document',
    roles: ['ADMIN'],
    permissions: ['admin:home:view'],
    featureFlag: 'admin.portal',
  },
  'admin.learning-resources': {
    code: 'admin.learning-resources',
    name: '学习资源管理',
    routePath: '/admin/learning-resources',
    icon: 'Reading',
    roles: ['ADMIN'],
    permissions: ['admin:home:view'],
    featureFlag: 'admin.portal',
  },
  'admin.knowledge-documents': {
    code: 'admin.knowledge-documents',
    name: 'AI 知识库',
    routePath: '/admin/knowledge-documents',
    icon: 'Reading',
    roles: ['ADMIN'],
    permissions: ['admin:home:view'],
    featureFlag: 'admin.portal',
  },
  'admin.exam-schedules': {
    code: 'admin.exam-schedules',
    name: '省级考试时间',
    routePath: '/admin/exam-schedules',
    icon: 'Calendar',
    roles: ['ADMIN'],
    permissions: ['admin:home:view'],
    featureFlag: 'admin.portal',
  },
  'exam.simulator': {
    code: 'exam.simulator',
    name: '机考模拟',
    routePath: '/exam-simulator',
    icon: 'Monitor',
    roles: ['STUDENT'],
    permissions: ['exam-simulator:enter'],
    featureFlag: 'exam.simulator',
  },
  'practice.start': {
    code: 'practice.start',
    name: '刷题练习',
    routePath: '/app/practice/start',
    icon: 'EditPen',
    roles: ['STUDENT'],
    permissions: ['practice:workspace:view'],
    featureFlag: 'practice.workspace',
  },
}

export function sanitizePlatformMenus(
  menus: PlatformMenu[],
  context: PlatformAccessContext,
): PlatformMenu[] {
  const candidates = [...menus]
  if (
    context.permissions.includes('question:read')
    && !candidates.some((menu) => menu.code === 'admin.questions')
  ) {
    candidates.push({
      code: 'admin.questions',
      name: '题库管理',
      routePath: '/admin/questions',
      icon: 'Collection',
    })
  }
  if (
    context.permissions.includes('question:review')
    && !candidates.some((menu) => menu.code === 'admin.practice-reviews')
  ) {
    candidates.push({ code: 'admin.practice-reviews', name: '主观题评分', routePath: '/admin/practice-reviews', icon: 'review' })
  }
  if (
    context.roles.includes('ADMIN')
    && context.permissions.includes('admin:home:view')
    && !candidates.some((menu) => menu.code === 'admin.contents')
  ) {
    candidates.push({ code: 'admin.contents', name: '资讯内容审核', routePath: '/admin/contents', icon: 'Document' })
  }
  if (
    context.roles.includes('ADMIN')
    && context.permissions.includes('admin:home:view')
    && !candidates.some((menu) => menu.code === 'admin.learning-resources')
  ) {
    candidates.push({ code: 'admin.learning-resources', name: '学习资源管理', routePath: '/admin/learning-resources', icon: 'Reading' })
  }
  if (
    context.roles.includes('ADMIN')
    && context.permissions.includes('admin:home:view')
    && !candidates.some((menu) => menu.code === 'admin.knowledge-documents')
  ) {
    candidates.push({ code: 'admin.knowledge-documents', name: 'AI 知识库', routePath: '/admin/knowledge-documents', icon: 'Reading' })
  }

  if (
    context.roles.includes('ADMIN')
    && context.permissions.includes('admin:home:view')
    && !candidates.some((menu) => menu.code === 'admin.practice-taxonomy')
  ) {
    candidates.push({ code: 'admin.practice-taxonomy', name: '章节与知识点', routePath: '/admin/practice-taxonomy', icon: 'Reading' })
  }
  if (context.roles.includes('ADMIN') && context.permissions.includes('admin:home:view') && !candidates.some((menu) => menu.code === 'admin.papers')) {
    candidates.push({ code: 'admin.papers', name: '试卷管理', routePath: '/admin/papers', icon: 'Document' })
  }

  return candidates.flatMap((menu) => {
    const allowed = menuWhitelist[menu.code]
    if (!allowed || allowed.routePath !== menu.routePath) return []
    const denied = resolvePlatformAccess(
      {
        requiresAuth: true,
        roles: allowed.roles,
        permissions: allowed.permissions,
        featureFlag: allowed.featureFlag,
      },
      context,
      allowed.routePath,
    )
    if (denied) return []
    return [{ code: allowed.code, name: allowed.name, routePath: allowed.routePath, icon: allowed.icon }]
  })
}

export function adminShellMenus(menus: PlatformMenu[]): PlatformMenu[] {
  return menus.filter((menu) => menu.routePath.startsWith('/admin/'))
}
