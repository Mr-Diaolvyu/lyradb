export interface SqlStatement {
    sql: string
    start: number
    end: number
}

/** 只负责编辑器分句；每句仍由服务端 SQL AST 与授权规则最终校验。 */
export function splitSqlStatements(input: string): SqlStatement[] {
    const result: SqlStatement[] = []
    let start = 0
    let quote = ''
    let dollar = ''
    let lineComment = false
    let blockComment = false
    for (let i = 0; i < input.length; i++) {
        const ch = input[i]
        const next = input[i + 1]
        if (lineComment) {
            if (ch === '\n') lineComment = false
            continue
        }
        if (blockComment) {
            if (ch === '*' && next === '/') { blockComment = false; i++ }
            continue
        }
        if (dollar) {
            if (input.startsWith(dollar, i)) { i += dollar.length - 1; dollar = '' }
            continue
        }
        if (quote) {
            const closing = quote === '[' ? ']' : quote
            if (ch === closing) {
                if (next === closing) i++
                else quote = ''
            } else if (ch === '\\' && (quote === "'" || quote === '"')) i++
            continue
        }
        if (ch === '-' && next === '-') { lineComment = true; i++; continue }
        if (ch === '/' && next === '*') { blockComment = true; i++; continue }
        if (ch === "'" || ch === '"' || ch === '`' || ch === '[') { quote = ch; continue }
        if (ch === '$') {
            const match = input.slice(i).match(/^\$[A-Za-z_0-9]*\$/)
            if (match) { dollar = match[0]; i += dollar.length - 1; continue }
        }
        if (ch === ';') {
            const sql = input.slice(start, i).trim()
            if (sql && !commentsOnly(sql)) result.push({ sql, start, end: i + 1 })
            start = i + 1
        }
    }
    const sql = input.slice(start).trim()
    if (sql && !commentsOnly(sql)) result.push({ sql, start, end: input.length })
    return result
}

export function statementAtCursor(input: string, offset: number): string {
    const statements = splitSqlStatements(input)
    if (!statements.length) return ''
    return statements.find(item => offset >= item.start && offset < item.end)?.sql
        || statements.filter(item => item.end <= offset).at(-1)?.sql
        || statements[0].sql
}

function commentsOnly(sql: string): boolean {
    return !sql.replace(/--[^\n]*(?:\n|$)/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '').trim()
}
