import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createReport, suggestCategory } from '../api/lostFound'
import { CreateReportPage } from './CreateReportPage'

vi.mock('../api/lostFound', () => ({
  createReport: vi.fn(),
  suggestCategory: vi.fn(),
}))

function itemNameField() {
  return screen.getByRole('textbox', { name: /item name/i })
}

function categoryField() {
  // 页面上仅一个 combobox（Category Select）；aria-labelledby 的 name 在 jsdom 不解析
  return screen.getByRole('combobox')
}

function renderPage() {
  return render(
    <MemoryRouter>
      <CreateReportPage reportType="LOST" />
    </MemoryRouter>,
  )
}

describe('CreateReportPage auto-categorization', () => {
  beforeEach(() => {
    vi.mocked(createReport).mockReset()
    vi.mocked(suggestCategory).mockReset()
  })

  afterEach(() => cleanup())

  it('fills the category from the suggestion when the item name blurs', async () => {
    vi.mocked(suggestCategory).mockResolvedValue('WALLET_PURSE')
    renderPage()

    fireEvent.change(itemNameField(), { target: { value: '钱包' } })
    fireEvent.blur(itemNameField())

    await waitFor(() => expect(categoryField()).toHaveTextContent('Wallet / Purse'))
    expect(suggestCategory).toHaveBeenCalledWith('钱包')
  })

  it('does not override a manually selected category', async () => {
    vi.mocked(suggestCategory).mockResolvedValue('ELECTRONICS')
    renderPage()

    fireEvent.mouseDown(categoryField())
    fireEvent.click(await screen.findByText('Other'))

    fireEvent.change(itemNameField(), { target: { value: '手机' } })
    fireEvent.blur(itemNameField())

    await waitFor(() => expect(categoryField()).toHaveTextContent('Other'))
    expect(suggestCategory).not.toHaveBeenCalled()
  })

  it('keeps the default category when the agent is unsure', async () => {
    vi.mocked(suggestCategory).mockResolvedValue(null)
    renderPage()

    fireEvent.change(itemNameField(), { target: { value: '神秘物品' } })
    fireEvent.blur(itemNameField())

    await waitFor(() => expect(suggestCategory).toHaveBeenCalledWith('神秘物品'))
    expect(categoryField()).toHaveTextContent('Electronics')
  })

  it('fails silently when the suggestion request errors', async () => {
    vi.mocked(suggestCategory).mockRejectedValue(new Error('boom'))
    renderPage()

    fireEvent.change(itemNameField(), { target: { value: '神秘物品' } })
    fireEvent.blur(itemNameField())

    await waitFor(() => expect(suggestCategory).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).toBeNull()
    expect(categoryField()).toHaveTextContent('Electronics')
  })
})
