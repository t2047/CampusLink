/** 展示名：有昵称用昵称，否则回退 email 前缀（个人中心需求 §6.1）。 */
export function displayName(nickname: string | null | undefined, email: string): string {
  const trimmed = nickname?.trim()
  if (trimmed) return trimmed
  return email.split('@')[0] || email
}
