export interface SqlCompletionTable {
    schema: string
    namespace?: string
    name: string
    qualifiedName?: string
    type?: string
    remarks?: string | null
}

export interface SqlTableReference {
    schema: string | null
    table: string
}

export interface SqlCompletionContext {
    prefix: string
    qualifier: string | null
    references: Record<string, SqlTableReference>
}

const REFERENCE = /\b(?:FROM|JOIN|UPDATE|INTO)\s+((?:[`"]|\[)?[A-Za-z_][\w$]*(?:[`"]|\])?(?:\s*\.\s*(?:[`"]|\[)?[A-Za-z_][\w$]*(?:[`"]|\])?){0,2})(?:\s+(?:AS\s+)?([A-Za-z_][\w$]*))?/gi
const CLAUSE_WORDS = new Set([
    'WHERE', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'FULL', 'CROSS', 'ON',
    'GROUP', 'ORDER', 'HAVING', 'LIMIT', 'OFFSET', 'SET', 'VALUES',
    'RETURNING', 'UNION',
])

/**
 * 提取光标位置的补全前缀，并从整条 SQL 中解析表和别名。
 *
 * 使用整条 SQL 而非仅光标前文本，是为了支持先写 SELECT、后写 FROM 的常见编辑顺序。
 */
export function parseSqlCompletionContext(
    sql: string,
    offset: number,
): SqlCompletionContext {
    const safe = sql || ''
    const caret = Math.max(0, Math.min(offset, safe.length))
    const before = safe.slice(0, caret)
    const qualified = before.match(/([A-Za-z_][\w$]*)\s*\.\s*([A-Za-z_][\w$]*)?$/)
    const word = before.match(/([A-Za-z_][\w$]*)$/)
    return {
        prefix: qualified ? (qualified[2] || '') : (word?.[1] || ''),
        qualifier: qualified?.[1] || null,
        references: parseReferences(safe),
    }
}

export function resolveCompletionTable(
    context: SqlCompletionContext,
    tables: SqlCompletionTable[],
): SqlCompletionTable | null {
    if (!context.qualifier) return null
    const qualifier = context.qualifier.toLocaleLowerCase()
    const reference = context.references[qualifier]
    if (reference) {
        const exact = tables.find(table =>
            table.name.localeCompare(reference.table, undefined, { sensitivity: 'accent' }) === 0
            && (!reference.schema
                || table.schema.toLocaleLowerCase() === reference.schema.toLocaleLowerCase()
                || table.qualifiedName?.toLocaleLowerCase()
                    === `${reference.schema}.${reference.table}`.toLocaleLowerCase()),
        )
        if (exact) return exact
    }
    return tables.find(table =>
        table.name.toLocaleLowerCase() === qualifier
        || table.qualifiedName?.toLocaleLowerCase() === qualifier,
    ) || null
}

function parseReferences(sql: string): Record<string, SqlTableReference> {
    const references: Record<string, SqlTableReference> = {}
    REFERENCE.lastIndex = 0
    let match: RegExpExecArray | null
    while ((match = REFERENCE.exec(sql)) !== null) {
        const parts = match[1]
            .replace(/[`"]|\[|\]|\s/g, '')
            .split('.')
        const table = parts[parts.length - 1]
        if (!table) continue
        const reference: SqlTableReference = {
            schema: parts.length > 1 ? parts.slice(0, -1).join('.') : null,
            table,
        }
        references[table.toLocaleLowerCase()] = reference
        const alias = match[2]
        if (alias && !CLAUSE_WORDS.has(alias.toUpperCase())) {
            references[alias.toLocaleLowerCase()] = reference
        }
    }
    return references
}
