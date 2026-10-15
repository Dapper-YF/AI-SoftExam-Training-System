import { platformApiClient, unwrapApiResponse } from '@/api/client'

export type ScheduleNodeType = 'REGISTRATION' | 'ADMISSION_TICKET' | 'EXAM' | 'RESULT'
export interface RegionalSchedule { id: string; regionCode: string; regionName: string; examPeriodCode: string; nodeType: ScheduleNodeType; scheduledAt: string | null; note: string | null; status: string }
export async function listRegionalSchedules(regionCode: string, examPeriodCode = '2026-H2'): Promise<RegionalSchedule[]> {
  return unwrapApiResponse<RegionalSchedule[]>(platformApiClient.get('/public/exam-schedules', { params: { regionCode, examPeriodCode } }))
}
