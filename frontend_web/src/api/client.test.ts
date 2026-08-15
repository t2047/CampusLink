import { describe, expect, it } from 'vitest'
import { apiErrorMessage } from './client'

describe('apiErrorMessage', () => {
  it('supports the backend validation errors payload', () => {
    const error = {
      isAxiosError: true,
      message: 'Request failed',
      response: { data: { errors: { startDateTime: 'must not be null', endDateTime: 'must not be null' } } },
    }

    expect(apiErrorMessage(error)).toBe('must not be null must not be null')
  })

  it('keeps backwards compatibility with fieldErrors payloads', () => {
    const error = {
      isAxiosError: true,
      message: 'Request failed',
      response: { data: { fieldErrors: { building: 'is required' }, errors: { building: 'different message' } } },
    }

    expect(apiErrorMessage(error)).toBe('is required')
  })
})
