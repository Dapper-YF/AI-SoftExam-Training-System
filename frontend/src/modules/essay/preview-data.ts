import type { EssayEditorData, EssayHomeData, EssayReportData } from './types'

export const homePreview: EssayHomeData = {
  workflow: [
    { label: '项目背景', state: 'done' }, { label: '素材库', state: 'done' }, { label: '论文写作', state: 'active' },
    { label: 'AI 批改', state: 'pending' }, { label: '修订', state: 'pending' }, { label: '学习', state: 'pending' },
  ],
  currentDraft: { id: 'draft-preview-1', title: '基于微服务架构的企业级采购管理系统设计与实现', background: '企业数字化采购管理平台建设项目', words: 8726, updatedAt: '2025-05-19 15:32' },
  latestReview: { score: 48, summary: '论文结构基本完整，但论据与项目背景的结合仍需加强。', issues: ['需求分析深度不足，缺少关键业务场景描述', '方案设计与项目背景结合度较低'], reviewedAt: '2025-05-18 20:11' },
  drafts: [
    { id: 'd1', title: '基于微服务架构的企业级采购管理系统设计与实现', words: 8726, updatedAt: '05-19 15:32' },
    { id: 'd2', title: '智慧园区综合管理平台的设计与实现', words: 6412, updatedAt: '05-17 22:10' },
    { id: 'd3', title: '金融行业数据中台建设方案研究', words: 5903, updatedAt: '05-15 18:45' },
    { id: 'd4', title: '医院信息集成平台的设计与优化', words: 4281, updatedAt: '05-13 11:20' },
  ],
  backgrounds: [
    { id: 'b1', title: '企业数字化采购管理平台建设项目', updatedAt: '2025-05-18' },
    { id: 'b2', title: '智慧园区综合管理平台建设项目', updatedAt: '2025-05-16' },
    { id: 'b3', title: '金融数据中台建设项目', updatedAt: '2025-05-14' },
    { id: 'b4', title: '医院信息集成平台建设项目', updatedAt: '2025-05-12' },
  ],
  materialStats: { total: 1248, process: 326, evidence: 198 },
}

export const editorPreview: EssayEditorData = {
  id: 'draft-preview-1', title: '数字化转型背景下制造企业项目管理能力提升研究与实践', version: 12,
  qualification: '高级项目经理', topic: '项目管理实践',
  outline: [
    { id: 'o1', label: '第1章 绪论', words: 432, level: 0 }, { id: 'o2', label: '第2章 理论基础与相关研究', words: 568, level: 0 },
    { id: 'o3', label: '第3章 项目管理现状与问题分析', words: 882, level: 0 }, { id: 'o31', label: '3.1 企业概况与项目背景', words: 286, level: 1 },
    { id: 'o32', label: '3.2 现状调研与问题识别', words: 312, level: 1 }, { id: 'o33', label: '3.3 问题根因分析', words: 284, level: 1 },
    { id: 'o4', label: '第4章 项目管理优化方案设计', words: 1024, level: 0 }, { id: 'o5', label: '第5章 方案实施与过程管理', words: 1156, level: 0 },
  ],
  background: '本项目围绕某中型制造企业数字化转型中的研发项目管理改进展开，企业面临项目进度波动、需求变更频繁和跨部门协同效率低等问题。',
  materials: [
    { id: 'm1', title: '项目现状调研报告V1.2.docx', type: '过程实践' }, { id: 'm2', title: '近三年项目数据统计表.xlsx', type: '工具应用' },
    { id: 'm3', title: '组织架构与流程说明.pdf', type: '证据' }, { id: 'm4', title: '项目管理制度（2024版）.docx', type: '过程实践' },
  ],
  paragraphs: [
    { id: 'p-1', outlineId: 'o3', text: '第3章  项目管理现状与问题分析' },
    { id: 'p-2', outlineId: 'o31', text: '3.1 企业概况与项目背景\n\n本企业主要从事智能装备的研发与制造，近年来积极推进数字化转型，承接了多项新产品研发与产线升级项目。随着业务规模扩大和项目数量增加，传统的项目管理方式在计划管控、资源配置、风险应对等方面逐渐暴露出不足，影响了项目交付质量与客户满意度。' },
    { id: 'p-3', outlineId: 'o32', text: '3.2 现状调研与问题识别\n\n为全面了解项目管理现状，项目组通过问卷调查、访谈、数据分析等方式开展了系统调研。调查覆盖研发、采购、生产、质量等关键部门的项目管理相关人员，其中项目经理占28%，核心骨干占36%，共回收有效问卷86份，并对12位项目经理及核心骨干进行了深度访谈。' },
    { id: 'p-4', outlineId: 'o33', text: '主要问题包括：计划管理不够科学；需求变更管理不规范；跨部门协同效率偏低；风险识别与应对不足。\n\n3.3 问题根因分析\n\n基于鱼骨图法及5Why分析法，从方法、流程、组织、人员、工具等维度对上述问题进行分析。' },
  ],
  suggestions: [
    { id: 's1', paragraphId: 'p-3', category: '内容充实', reason: '建议补充调研样本结构与数据分析方法，以增强严谨性。', content: '为全面了解项目管理现状，项目组通过问卷调查、访谈、数据分析等方式开展了系统调研。调查覆盖研发、采购、生产、质量等关键部门，其中项目经理占28%、核心骨干占36%，共回收有效问卷86份，并对12位项目经理及核心骨干进行了深度访谈。' },
    { id: 's2', paragraphId: 'p-3', category: '逻辑优化', reason: '“主要问题包括”前增加过渡句，增强段落衔接。', content: '综合问卷结果、访谈记录与项目数据交叉验证，当前项目管理问题主要集中在以下四个方面。' },
  ],
}

export const reportPreview: EssayReportData = {
  reviewTaskId: 'review-task-preview-1', resultId: 'result-preview-1', draftId: 'draft-preview-1', title: '智慧园区项目进度管理优化研究', score: 72, previousDelta: 8,
  rubricVersion: 'V2.1（2024-04-01）', reviewedAt: '2025-05-19 15:32:20',
  summary: '论文选题具有现实意义，结构基本合理，论证思路较清晰，数据分析有一定深度。但部分论点支撑、案例引用规范性及语言表达仍有提升空间。',
  taskState: 'SUCCEEDED',
  dimensions: [
    { label: '选题与立意', score: 16, total: 20, tone: 'primary' }, { label: '结构与逻辑', score: 14, total: 20, tone: 'amber' },
    { label: '论证与分析', score: 16, total: 20, tone: 'primary' }, { label: '专业性与深度', score: 15, total: 20, tone: 'teal' },
    { label: '语言与规范', score: 11, total: 20, tone: 'coral' },
  ],
  paragraphs: [
    { id: 'rp-1', index: 1, text: '随着智慧城市建设的不断推进，智慧园区作为城市数字化的重要载体，其项目管理水平直接影响园区建设成效和运营效率。然而，当前智慧园区项目在进度管理过程中普遍存在计划不够科学、执行监控不足、资源协调困难等问题，导致项目延期现象较为突出，影响了投资效益和用户体验。', issueIds: ['issue-1', 'issue-2'] },
    { id: 'rp-2', index: 2, text: '本文以某智慧园区建设项目为例，通过文献研究、案例分析和问卷调查等方法，系统梳理了项目进度管理的关键影响因素，构建了进度管理优化的理论框架，并提出了针对性的优化策略。', issueIds: ['issue-3'] },
    { id: 'rp-3', index: 3, text: '研究结果表明，科学的进度计划与合理的资源配置是保障项目按期完成的基础；建立动态监控机制和风险预警机制能够有效应对进度偏差；加强沟通协同与信息化工具应用可显著提升进度管理效率。', issueIds: ['issue-4', 'issue-5'] },
    { id: 'rp-4', index: 4, text: '本文的研究对类似智慧园区项目的进度管理实践具有一定的参考价值，同时也为相关理论研究提供了实践支撑。', issueIds: [] },
  ],
  issues: [
    { id: 'issue-1', paragraphId: 'rp-1', severity: '严重问题', title: '论断缺乏充分依据', deduction: -3, source: '存在计划不够科学、执行监控不足、资源协调困难等问题', reason: '未说明问题的具体表现或数据佐证，论断显得笼统，缺乏充分支持。', suggestion: '结合项目实际调研数据，具体描述问题表现及影响范围，增强论断的说服力。', rewrites: ['存在计划不够科学、执行监控不足及资源协调困难等问题。', '调研显示，42%的里程碑存在延期，跨部门资源确认平均耗时3.6天。', '基于计划偏差与资源响应数据，可进一步量化三类问题的影响。'] },
    { id: 'issue-2', paragraphId: 'rp-1', severity: '一般问题', title: '结论表述过于宽泛', deduction: -2, source: '影响了投资效益和用户体验', reason: '影响对象和范围不够明确。', suggestion: '说明延期对投资回报周期和园区用户交付体验的具体影响。', rewrites: ['拉长了投资回报周期并影响园区用户按期入驻。', '造成阶段验收延迟与配套服务上线滞后。', '使投资回收测算与用户入驻计划出现连锁偏差。'] },
    { id: 'issue-3', paragraphId: 'rp-2', severity: '已优化建议', title: '术语表达不够严谨', deduction: 0, source: '构建了进度管理优化的理论框架', reason: '建议明确框架的适用边界。', suggestion: '补充框架面向智慧园区项目的范围限定。', rewrites: ['构建了面向智慧园区建设项目的进度管理优化框架。', '形成了适用于多参与方园区建设的进度优化框架。', '提出了覆盖计划、监控与协同的园区进度优化框架。'] },
    { id: 'issue-4', paragraphId: 'rp-3', severity: '一般问题', title: '序号格式使用不规范', deduction: -1, source: '科学的进度计划与合理的资源配置', reason: '并列结论可使用规范序号提升可读性。', suggestion: '按“第一、第二、第三”组织结论。', rewrites: ['第一，完善计划与资源配置。', '第二，建立动态监控与预警机制。', '第三，强化沟通协同与工具应用。'] },
    { id: 'issue-5', paragraphId: 'rp-3', severity: '严重问题', title: '数据支撑不足', deduction: -3, source: '可显著提升进度管理效率', reason: '“显著提升”缺少对比数据。', suggestion: '补充优化前后的周期或偏差率数据。', rewrites: ['试点后里程碑按期完成率由68%提升至86%。', '跨部门确认耗时由3.6天缩短至1.8天。', '月度计划偏差率下降了12个百分点。'] },
  ],
}
