import type { ActiveExamRule, ExamRuleSubject, FoundationKnowledgeNode, Qualification, Subject } from '../types'
import type { PlatformFoundationGateway } from './platform-foundation.gateway'
import { createMockSoftExamTaxonomy } from './mock-soft-exam-taxonomy.fixture'

export class MockPlatformFoundationGateway implements PlatformFoundationGateway {
  private readonly taxonomy = createMockSoftExamTaxonomy()

  async listQualifications(): Promise<Qualification[]> {
    return structuredClone(this.taxonomy.qualifications)
  }

  async listSubjects(qualificationId: string): Promise<Subject[]> {
    return structuredClone(this.taxonomy.subjectsByQualification[qualificationId] ?? [])
  }

  async listChapterNodes(_subjectId: string): Promise<FoundationKnowledgeNode[]> {
    return []
  }

  async listSyllabusNodes(_subjectId: string): Promise<FoundationKnowledgeNode[]> {
    return []
  }

  async getActiveExamRule(qualificationId: string, periodCode: string): Promise<ActiveExamRule | null> {
    const qualification = this.taxonomy.qualifications.find((item) => item.id === qualificationId)
    if (!qualification?.available) return null
    const subjects = this.taxonomy.subjectsByQualification[qualificationId] ?? []
    const advanced = qualification.levelCode === 'ADVANCED'
    const ruleSubjects: ExamRuleSubject[] = subjects.map((subject, index) => ({
      subjectCode: subject.code,
      subjectType: subject.type,
      displayOrder: subject.sortOrder ?? index + 1,
      durationMinutes: 120,
      fullScore: 75,
      passScore: 45,
      questionTypes: subject.type === 'COMPREHENSIVE'
        ? ['SINGLE_CHOICE']
        : subject.type === 'ESSAY' ? ['ESSAY'] : ['CASE'],
      earliestSubmitMinutes: 30,
      feedbackPolicy: 'AFTER_EXAM',
      linkedGroup: `${qualification.code}_LINKED_1`,
      sequence: index + 1,
    }))
    return {
      ruleId: `preview-rule-${qualification.code}`,
      ruleCode: `${qualification.code}-${periodCode}`,
      ruleVersion: `${periodCode}-preview-v1`,
      schemaVersion: '1.0',
      qualificationId,
      periodCode,
      effectiveFrom: '2026-01-01T00:00:00+08:00',
      effectiveTo: '2026-06-30T23:59:59+08:00',
      totalDurationMinutes: advanced ? 360 : 240,
      deadlinePolicy: 'SHARED_TOTAL_DEADLINE',
      sharedForceSubmitMinutes: 240,
      subjects: ruleSubjects,
      sourceVerifiedAt: '2026-07-13T00:00:00+08:00',
    }
  }
}
