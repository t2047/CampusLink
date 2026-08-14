import type { ReactNode } from 'react'

/**
 * Lightweight Markdown renderer shared by chat UIs.
 *
 * Supports headings, fenced code blocks, ordered/unordered lists, blockquotes,
 * horizontal rules, tables, and inline styles (**bold**, *italic*, ~~strike~~,
 * `code`, URLs). Styled with Tailwind classes (loaded globally via index.css).
 */
export function MarkdownContent({ text }: { text: string }) {
  if (!text) return null
  return <>{renderBlocks(text)}</>
}

/** Markdown 渲染：标题 / 代码块 / 列表（有序·无序）/ 引用 / 分隔线 / 表格 + 行内样式 */
function renderBlocks(text: string): ReactNode {
  const blocks: ReactNode[] = []
  const lines = text.split(/\r?\n/)
  let key = 0

  let para: string[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  let inCode = false
  let codeLang = ''
  let codeLines: string[] = []
  let table: { header: string[]; rows: string[][] } | null = null

  const flushPara = () => {
    if (para.length > 0) {
      blocks.push(
        <p key={key++} className="min-h-[1em]">
          {renderInline(para.join('\n'))}
        </p>,
      )
      para = []
    }
  }

  const flushList = () => {
    if (list) {
      const Tag = list.ordered ? 'ol' : 'ul'
      blocks.push(
        <Tag
          key={key++}
          className={`my-1 ml-4 ${list.ordered ? 'list-decimal' : 'list-disc'} space-y-0.5`}
        >
          {list.items.map((it, i) => (
            <li key={i}>{renderInline(it)}</li>
          ))}
        </Tag>,
      )
      list = null
    }
  }

  const flushCode = () => {
    if (inCode) {
      blocks.push(
        <pre
          key={key++}
          className="my-1.5 overflow-x-auto rounded-lg bg-slate-900 p-3 font-mono text-xs leading-relaxed text-slate-100 dark:bg-slate-900"
        >
          <code className={codeLang ? `language-${codeLang}` : ''}>{codeLines.join('\n')}</code>
        </pre>,
      )
      inCode = false
      codeLang = ''
      codeLines = []
    }
  }

  const flushTable = () => {
    if (table) {
      blocks.push(
        <div key={key++} className="my-1.5 overflow-x-auto">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr>
                {table.header.map((h, i) => (
                  <th
                    key={i}
                    className="border border-slate-300 bg-slate-100 px-2 py-1 text-left font-medium dark:border-slate-600 dark:bg-slate-700"
                  >
                    {renderInline(h)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((row, ri) => (
                <tr key={ri}>
                  {row.map((c, ci) => (
                    <td key={ci} className="border border-slate-300 px-2 py-1 dark:border-slate-600">
                      {renderInline(c)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      )
      table = null
    }
  }

  const flushAll = () => {
    flushPara()
    flushList()
    flushCode()
    flushTable()
  }

  const isTableRow = (line: string) =>
    /^\s*\|/.test(line) && line.replace(/\|/g, '').trim() !== ''
  const isTableSep = (line: string) => /^[\s|:-]+$/.test(line) && line.includes('-')

  for (const line of lines) {
    const trimmed = line.trim()

    // 代码块围栏（```lang）
    const fence = line.match(/^```(\w*)\s*$/)
    if (fence) {
      flushAll()
      if (inCode) flushCode()
      else {
        inCode = true
        codeLang = fence[1]
      }
      continue
    }
    if (inCode) {
      codeLines.push(line)
      continue
    }

    if (trimmed === '') {
      flushAll()
      continue
    }

    // 标题（# ~ ######）
    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      flushAll()
      const level = Math.min(heading[1].length, 6)
      const Tag = `h${level}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6'
      const size =
        level === 1
          ? 'text-xl font-bold'
          : level === 2
            ? 'text-lg font-bold'
            : level === 3
              ? 'text-base font-semibold'
              : 'text-sm font-semibold'
      blocks.push(
        <Tag key={key++} className={`mt-2 mb-1 ${size}`}>
          {renderInline(heading[2])}
        </Tag>,
      )
      continue
    }

    // 引用（>）
    if (/^>\s?/.test(line)) {
      flushAll()
      blocks.push(
        <blockquote
          key={key++}
          className="my-1 border-l-4 border-slate-300 pl-3 text-slate-500 dark:border-slate-600 dark:text-slate-400"
        >
          {renderInline(line.replace(/^>\s?/, ''))}
        </blockquote>,
      )
      continue
    }

    // 分隔线（--- / *** / ___）
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      flushAll()
      blocks.push(<hr key={key++} className="my-2 border-slate-300 dark:border-slate-600" />)
      continue
    }

    // 表格：连续 | 行，第二行是 ---/:-: 分隔行时视为表头
    if (isTableRow(line) || (table && isTableSep(line))) {
      flushPara()
      flushList()
      if (!table) {
        table = { header: splitTableRow(line), rows: [] }
        continue
      }
      if (isTableSep(line)) continue
      table.rows.push(splitTableRow(line))
      continue
    }
    flushTable()

    // 列表（无序 - * • / 有序 1. 1)）
    const ul = line.match(/^\s*[-*•]\s+(.*)$/)
    const ol = line.match(/^\s*\d+[.)]\s+(.*)$/)
    if (ul || ol) {
      flushPara()
      const ordered = !!ol
      const content = (ul ?? ol)![1]
      if (!list || list.ordered !== ordered) {
        flushList()
        list = { ordered, items: [] }
      }
      list.items.push(content)
      continue
    }

    // 普通段落行
    flushList()
    para.push(line)
  }
  flushAll()

  return <>{blocks}</>
}

/** 按 | 切分表格行（去掉首尾管道符）。 */
function splitTableRow(line: string): string[] {
  return line
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((c) => c.trim())
}

function renderInline(text: string): ReactNode {
  const parts: ReactNode[] = []
  // 顺序：**粗体** → *斜体* → ~~删除线~~ → `代码` → URL
  const regex = /(\*\*(.+?)\*\*|\*([^*\n]+)\*|~~(.+?)~~|`([^`]+)`|(https?:\/\/[^\s]+))/g
  let last = 0
  let match: RegExpExecArray | null
  let key = 0

  while ((match = regex.exec(text)) !== null) {
    if (match.index > last) parts.push(text.slice(last, match.index))
    if (match[2]) {
      parts.push(
        <strong key={key++} className="font-semibold">
          {match[2]}
        </strong>,
      )
    } else if (match[3]) {
      parts.push(<em key={key++}>{match[3]}</em>)
    } else if (match[4]) {
      parts.push(
        <del key={key++} className="text-slate-400 dark:text-slate-500">
          {match[4]}
        </del>,
      )
    } else if (match[5]) {
      parts.push(
        <code
          key={key++}
          className="rounded bg-slate-200 px-1.5 py-0.5 font-mono text-[0.85em] text-rose-600 dark:bg-slate-700 dark:text-rose-300"
        >
          {match[5]}
        </code>,
      )
    } else if (match[6]) {
      parts.push(
        <a
          key={key++}
          href={match[6]}
          target="_blank"
          rel="noreferrer"
          className="text-indigo-600 underline dark:text-indigo-400"
        >
          {match[6]}
        </a>,
      )
    }
    last = regex.lastIndex
  }

  if (last < text.length) parts.push(text.slice(last))
  return parts.length > 0 ? parts : text
}
