import type { ActiveExamRule, FoundationKnowledgeNode, Qualification, Subject } from '../types'

export interface PlatformFoundationGateway {
  listQualifications(): Promise<Qualification[]>
  listSubjects(qualificationId: string): Promise<Subject[]>
  listChapterNodes(subjectId: string): Promise<FoundationKnowledgeNode[]>
  listSyllabusNodes(subjectId: string): Promise<FoundationKnowledgeNode[]>
  listKnowledgeTags?(qualificationId: string, subjectId: string): Promise<FoundationKnowledgeNode[]>
  getActiveExamRule?(qualificationId: string, periodCode: string): Promise<ActiveExamRule | null>
}
