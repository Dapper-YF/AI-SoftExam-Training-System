export const SHARED_DEMO_ADMISSION_TICKET = '111111111111'
export const SHARED_DEMO_IDENTITY_NUMBER = '111111111111111111'

export const SHARED_DEMO_CREDENTIAL_HINT =
  '\u8fd9\u662f\u516c\u5f00\u7684\u6a21\u62df\u8003\u8bd5\u8fdb\u5165\u7801\uff0c\u4ec5\u7528\u4e8e\u672c\u5730\u673a\u8003\u6d41\u7a0b\u6f14\u7ec3\u3002'

export function isSharedDemoAdmissionTicket(value: string): boolean {
  return value === SHARED_DEMO_ADMISSION_TICKET
}

export function isSharedDemoIdentityNumber(value: string): boolean {
  return value === SHARED_DEMO_IDENTITY_NUMBER
}

export function isTwelveDigits(value: string): boolean {
  return /^\d{12}$/.test(value)
}

export function isEighteenDigits(value: string): boolean {
  return /^\d{18}$/.test(value)
}
