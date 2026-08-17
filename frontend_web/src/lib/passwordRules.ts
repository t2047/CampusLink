/**
 * 密码规则共享校验（与后端 PasswordRules 一致，ChangePassWord.md）。
 *
 * 最小长度按字符数（6），最大长度按 UTF-8 字节数（72，BCrypt 硬截断上限）——
 * 若只按字符数限制，中文、emoji 等多字节字符会绕过 72 字节上限。
 * 注册 / 管理员创建用户 / 修改密码 / 前端提示共用同一套约束。
 */
export const PASSWORD_MIN_LENGTH = 6
export const PASSWORD_MAX_BYTES = 72

export function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

export function isPasswordLengthValid(value: string): boolean {
  return value.length >= PASSWORD_MIN_LENGTH && utf8ByteLength(value) <= PASSWORD_MAX_BYTES
}
