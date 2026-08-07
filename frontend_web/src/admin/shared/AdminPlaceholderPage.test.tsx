import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { AdminPlaceholderPage } from './AdminPlaceholderPage'

describe('AdminPlaceholderPage', () => {
  afterEach(() => cleanup())

  it('renders a coming-soon module and returns to the overview', () => {
    render(
      <MemoryRouter initialEntries={['/admin/facilities']}>
        <Routes>
          <Route
            path="/admin/facilities"
            element={(
              <AdminPlaceholderPage
                title="Facilities"
                description="Facilities administration will be available here."
                status="Coming Soon"
              />
            )}
          />
          <Route path="/admin/dashboard" element={<h1>Dashboard Overview</h1>} />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Facilities' })).toBeInTheDocument()
    expect(screen.getByText('Facilities administration will be available here.')).toBeInTheDocument()
    expect(screen.getByText('Coming Soon')).toBeInTheDocument()

    const returnLink = screen.getByRole('link', { name: 'Return to Overview' })
    expect(returnLink).toHaveAttribute('href', '/admin/dashboard')
    fireEvent.click(returnLink)
    expect(screen.getByRole('heading', { name: 'Dashboard Overview' })).toBeInTheDocument()
  })
})
