import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { LostFoundFaqPage } from './LostFoundFaqPage'

function renderPage(initialEntry = '/lost-found/faq') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <LostFoundFaqPage />
    </MemoryRouter>,
  )
}

afterEach(() => cleanup())

describe('LostFoundFaqPage', () => {
  it('renders the FAQ title and Chinese content by default', () => {
    renderPage()

    expect(screen.getByRole('heading', { name: /FAQ/ })).toBeInTheDocument()
    expect(screen.getByText('如何发布失物报告？')).toBeInTheDocument()
  })

  it('shows a back link to the personal center when opened from the profile page', () => {
    renderPage('/lost-found/faq?from=profile')

    expect(screen.getByRole('link', { name: /Back to personal center/ })).toHaveAttribute('href', '/lost-found/profile')
  })

  it('switches to English content', () => {
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'English' }))

    expect(screen.getByText('How do I post a lost-item report?')).toBeInTheDocument()
  })
})
