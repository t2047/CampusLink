import { describe, expect, it } from 'vitest'
import { PASSWORD_MAX_BYTES, PASSWORD_MIN_LENGTH, isPasswordLengthValid, utf8ByteLength } from './passwordRules'

describe('passwordRules', () => {
  it('exposes shared constants matching the backend', () => {
    expect(PASSWORD_MIN_LENGTH).toBe(6)
    expect(PASSWORD_MAX_BYTES).toBe(72)
  })

  it('counts UTF-8 bytes for multi-byte characters', () => {
    expect(utf8ByteLength('x'.repeat(65))).toBe(65)
    expect(utf8ByteLength('密'.repeat(24))).toBe(72)
    expect(utf8ByteLength('密'.repeat(25))).toBe(75)
  })

  it('validates length: at least 6 chars and at most 72 bytes', () => {
    expect(isPasswordLengthValid('12345')).toBe(false)
    expect(isPasswordLengthValid('123456')).toBe(true)
    expect(isPasswordLengthValid('x'.repeat(65))).toBe(true)
    expect(isPasswordLengthValid('密'.repeat(24))).toBe(true)
    expect(isPasswordLengthValid('密'.repeat(25))).toBe(false)
  })
})
