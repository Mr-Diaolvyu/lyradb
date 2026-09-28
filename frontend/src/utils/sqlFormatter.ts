/**
 * 保守的 SQL 排版：只调整关键子句前的空白，不改写任何非空白字符。
 * 因而字符串、注释、标识符、运算符及原有分号均保持原样。
 */
type TokenKind = 'word' | 'quoted' | 'comment' | 'punctuation' | 'operator'
interface Token { text: string; kind: TokenKind; gap: string }

const CLAUSES = new Set([
  'SELECT', 'FROM', 'WHERE', 'HAVING', 'LIMIT', 'OFFSET',
  'JOIN', 'UNION', 'INTERSECT', 'EXCEPT', 'WITH',
  'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP',
])

function tokenize(sql: string): Token[] {
  const result: Token[] = []
  let index = 0
  let gap = ''
  const push = (end: number, kind: TokenKind) => {
    result.push({ text: sql.slice(index, end), kind, gap })
    gap = ''
    index = end
  }

  while (index < sql.length) {
    const character = sql[index]
    if (/\s/.test(character)) {
      gap += character
      index++
      continue
    }
    if (sql.startsWith('--', index) || character === '#') {
      const end = sql.indexOf('\n', index)
      push(end < 0 ? sql.length : end, 'comment')
      continue
    }
    if (sql.startsWith('/*', index)) {
      const end = sql.indexOf('*/', index + 2)
      push(end < 0 ? sql.length : end + 2, 'comment')
      continue
    }
    const dollar = character === '$'
      ? sql.slice(index).match(/^\$[A-Za-z_0-9]*\$/)?.[0] : undefined
    if (dollar) {
      const end = sql.indexOf(dollar, index + dollar.length)
      push(end < 0 ? sql.length : end + dollar.length, 'quoted')
      continue
    }
    if (character === "'" || character === '"' || character === '`' || character === '[') {
      const closing = character === '[' ? ']' : character
      let end = index + 1
      while (end < sql.length) {
        if (sql[end] === '\\' && (character === "'" || character === '"')) {
          end += 2
          continue
        }
        if (sql[end] === closing) {
          if (sql[end + 1] === closing) { end += 2; continue }
          end++
          break
        }
        end++
      }
      push(Math.min(end, sql.length), 'quoted')
      continue
    }
    if (/[A-Za-z_\u0080-\uffff]/.test(character)) {
      let end = index + 1
      while (end < sql.length && /[A-Za-z_0-9$\u0080-\uffff]/.test(sql[end])) end++
      push(end, 'word')
      continue
    }
    if ('(),;.'.includes(character)) {
      push(index + 1, 'punctuation')
      continue
    }
    // 保留运算符与数字的原样组合，避免把 ::、->、>= 等拆坏。
    let end = index + 1
    while (end < sql.length && !/[\sA-Za-z_\u0080-\uffff'"`[\](),;.]/.test(sql[end])
      && !sql.startsWith('--', end) && !sql.startsWith('/*', end)) end++
    push(end, 'operator')
  }
  return result
}

function startsClause(tokens: Token[], index: number): boolean {
  const token = tokens[index]
  if (token.kind !== 'word') return false
  const keyword = token.text.toUpperCase()
  if (keyword === 'GROUP' || keyword === 'ORDER') {
    return tokens[index + 1]?.text.toUpperCase() === 'BY'
  }
  if (keyword === 'LEFT' || keyword === 'RIGHT' || keyword === 'INNER' || keyword === 'FULL') {
    return tokens[index + 1]?.text.toUpperCase() === 'JOIN'
  }
  if (keyword === 'JOIN' && ['LEFT', 'RIGHT', 'INNER', 'FULL']
    .includes(tokens[index - 1]?.text.toUpperCase() || '')) return false
  return CLAUSES.has(keyword) || keyword === 'AND' || keyword === 'OR'
}

export function formatSql(input: string): string {
  if (!input.trim()) return input
  const tokens = tokenize(input)
  let output = ''
  let depth = 0
  let previous: Token | undefined

  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index]
    if (token.text === ')') depth = Math.max(0, depth - 1)
    const lineComment = previous?.kind === 'comment'
      && (previous.text.startsWith('--') || previous.text.startsWith('#'))
    const originalLineBreak = /[\r\n]/.test(token.gap)
    const clause = startsClause(tokens, index)
    const newLine = previous && (lineComment || (clause && previous.text !== '(')
      || previous.text === ';' || originalLineBreak)

    if (newLine) {
      output = output.replace(/[ \t]+$/, '')
      output += '\n' + '  '.repeat(depth)
    } else if (previous && token.gap) {
      output += ' '
    } else if (!previous) {
      output += token.gap
    }
    output += token.text
    if (token.text === '(') depth++
    previous = token
  }
  return output.trimEnd() + input.slice(input.trimEnd().length)
}
