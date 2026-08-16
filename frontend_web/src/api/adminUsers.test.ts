import { afterEach, describe, expect, it, vi } from 'vitest'
import { listAdminUsers, type AdminUser } from './adminUsers'
import { apiClient } from './client'

const usersFixture: AdminUser[] = [
  { id: 1, email: 'admin@nus.edu.sg', role: 'ADMIN' },
  { id: 2, email: 'student@nus.edu.sg', role: 'STUDENT' },
]

describe('admin Users API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('loads all Admin users and returns response data', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: usersFixture })

    const result = await listAdminUsers()

    expect(get).toHaveBeenCalledWith('/admin/users')
    expect(result).toBe(usersFixture)
  })

  it('propagates API errors', async () => {
    const error = new Error('User request failed')
    vi.spyOn(apiClient, 'get').mockRejectedValue(error)

    await expect(listAdminUsers()).rejects.toBe(error)
  })
})
