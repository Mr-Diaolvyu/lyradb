/**
 * SQL 格式化（自实现，无第三方依赖）
 *
 * <p>
 * 基础规则：关键字大写、在主要子句前换行、统一空白与缩进、保留字符串字面量不被改写。
 * 不追求完备（不处理嵌套子查询缩进、CTE 展开等），作为 MVP 可用即可（PRD F4）。
 * </p>
 */

const CLAUSE_KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'ORDER BY', 'GROUP BY',
  'HAVING', 'LIMIT', 'OFFSET', 'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN',
  'FULL JOIN', 'JOIN', 'ON', 'UNION', 'UNION ALL', 'INTERSECT', 'EXCEPT',
  'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE FROM', 'CREATE TABLE',
  'ALTER TABLE', 'DROP TABLE', 'WITH',
])

const TOP_LEVEL = new Set([
  'SELECT', 'FROM', 'WHERE', 'ORDER BY', 'GROUP BY', 'HAVING',
  'LIMIT', 'OFFSET', 'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN',
  'FULL JOIN', 'JOIN', 'UNION', 'UNION ALL', 'INTERSECT', 'EXCEPT',
  'INSERT INTO', 'VALUES', 'UPDATE', 'DELETE FROM', 'CREATE TABLE',
  'ALTER TABLE', 'DROP TABLE', 'WITH',
])

function isQuote(ch: string): boolean {
  return ch === "'" || ch === '"' || ch === '`'
}

/** 简易分词，保留字符串/引号标识符的整体性 */
function tokenize(sql: string): string[] {
  const tokens: string[] = []
  let i = 0
  while (i < sql.length) {
    const ch = sql[i]
    if (ch === '-' && sql[i + 1] === '-') {
      // 行注释
      let j = i
      while (j < sql.length && sql[j] !== '\n') j++
      tokens.push(sql.slice(i, j))
      i = j
      continue
    }
    if (ch === '/' && sql[i + 1] === '*') {
      // 块注释
      let j = i + 2
      while (j < sql.length && !(sql[j] === '*' && sql[j + 1] === '/')) j++
      tokens.push(sql.slice(i, Math.min(j + 2, sql.length)))
      i = Math.min(j + 2, sql.length)
      continue
    }
    if (isQuote(ch)) {
      let j = i + 1
      while (j < sql.length) {
        if (isQuote(sql[j])) {
          // 处理双引号转义 ('')
          if (sql[j] === ch && sql[j + 1] === ch) {
            j += 2
            continue
          }
          j++
          break
        }
        j++
      }
      tokens.push(sql.slice(i, j))
      i = j
      continue
    }
    if (/\s/.test(ch)) {
      let j = i
      while (j < sql.length && /\s/.test(sql[j])) j++
      tokens.push(' ')
      i = j
      continue
    }
    if (/[(),;]/.test(ch)) {
      tokens.push(ch)
      i++
      continue
    }
    // 普通标识符/数字/运算符
    let j = i
    while (j < sql.length && !/[\s(),;'"`]/.test(sql[j])) j++
    tokens.push(sql.slice(i, j))
    i = j
  }
  return tokens
}

/** 把连续 token 合成多词关键字（如 ORDER BY）以便识别 */
function composeKeywords(tokens: string[]): { text: string; isSpace: boolean; isPunct: boolean }[] {
  const out: { text: string; isSpace: boolean; isPunct: boolean }[] = []
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k]
    if (t === ' ') {
      out.push({ text: ' ', isSpace: true, isPunct: false })
      continue
    }
    if (/^[(),;]$/.test(t)) {
      out.push({ text: t, isSpace: false, isPunct: true })
      continue
    }
    // 检查双词关键字
    const next = tokens[k + 1]
    const nextNext = tokens[k + 2]
    const two = `${t} ${nextNext || ''}`.trim()
    if (next === ' ' && nextNext && CLAUSE_KEYWORDS.has(`${t} ${nextNext}`.toUpperCase())) {
      out.push({ text: `${t} ${nextNext}`.toUpperCase(), isSpace: false, isPunct: false })
      k += 2
      continue
    }
    out.push({ text: t, isSpace: false, isPunct: false })
  }
  return out
}

export function formatSql(input: string): string {
  if (!input || !input.trim()) return input || ''
  const tokens = tokenize(input)
  const parts = composeKeywords(tokens)

  let out = ''
  let indent = 0
  let prevIsClause = false
  let prevIsOpenParen = false

  const newline = () => '\n' + '  '.repeat(indent)
  const isStringToken = (s: string) => isQuote(s[0])

  for (let k = 0; k < parts.length; k++) {
    const p = parts[k]
    if (p.isSpace) continue
    const upper = p.text.toUpperCase()
    const prev = k > 0 ? parts[k - 1] : null
    const lastChar = out.length ? out[out.length - 1] : ''

    if (p.isPunct) {
      if (p.text === '(') {
        out += '('
        prevIsOpenParen = true
      } else if (p.text === ')') {
        out = out.replace(/\s*$/, '') + ')'
      } else if (p.text === ',') {
        out += ', '
      } else if (p.text === ';') {
        out = out.replace(/\s*$/, '') + ';'
      }
      prevIsClause = false
      continue
    }

    const isClause = TOP_LEVEL.has(upper) || CLAUSE_KEYWORDS.has(upper)

    if (isClause && TOP_LEVEL.has(upper)) {
      // 顶层子句：换行 + 重置缩进
      out = out.replace(/\s*$/, '')
      if (out) out += newline()
      out += upper
      indent = upper === 'SELECT' ? 1 : indent
      prevIsClause = true
      continue
    }

    if (upper === 'AND' || upper === 'OR') {
      out = out.replace(/\s*$/, '')
      out += newline() + upper + ' '
      prevIsClause = true
      continue
    }

    // 普通 token
    const isStr = isStringToken(p.text)
    const text = isStr ? p.text : (isKeyword(p.text) ? p.text.toUpperCase() : p.text)

    if (out === '') {
      out += text
    } else if (lastChar === '(' || lastChar === '\n' || prevIsClause) {
      out += text
    } else if (prev?.isPunct && prev.text === '(') {
      out += text
    } else {
      out += ' ' + text
    }
    prevIsClause = false
    prevIsOpenParen = false
  }

  return out.trim() + (out.endsWith(';') ? '' : ';')
}

const KEYWORD_SET = new Set([
  'SELECT', 'DISTINCT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN',
  'IS', 'NULL', 'AS', 'ORDER', 'BY', 'GROUP', 'HAVING', 'LIMIT', 'OFFSET',
  'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE', 'TABLE',
  'ALTER', 'DROP', 'INDEX', 'VIEW', 'DATABASE', 'JOIN', 'INNER', 'LEFT',
  'RIGHT', 'FULL', 'ON', 'UNION', 'ALL', 'CASE', 'WHEN', 'THEN',
  'ELSE', 'END', 'IF', 'EXISTS', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
  'SHOW', 'TABLES', 'COLUMNS', 'DESCRIBE', 'EXPLAIN', 'WITH', 'RECURSIVE',
  'ASC', 'DESC', 'DEFAULT', 'PRIMARY', 'KEY', 'FOREIGN', 'REFERENCES', 'UNIQUE',
  'CONSTRAINT', 'CHECK', 'CAST', 'CONVERT', 'OVER', 'PARTITION', 'WINDOW',
])

function isKeyword(s: string): boolean {
  return KEYWORD_SET.has(s.toUpperCase())
}
