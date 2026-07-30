import type {
    ApprovalRequest,
    ConnectionImportDecision,
    ConnectionImportPreviewItem,
    CredentialExportMode,
    MetadataSelection,
    MetadataTablePreview,
} from '@/api/ent'

export interface DataSourceExportRef {
    id: string
    displayName: string
}

export interface DataSourceExportPayload {
    dataSourceRefs: DataSourceExportRef[]
    credentialMode: CredentialExportMode
    plaintextRiskConfirmed: boolean
}

const CREDENTIAL_MODES: ReadonlySet<string> = new Set(['OMIT', 'PLAINTEXT', 'PASSWORD_ENCRYPTED'])

export function parseDataSourceExportPayload(row: ApprovalRequest): DataSourceExportPayload | null {
    if (row.operationType !== 'DATASOURCE_EXPORT' || !row.payloadJson) return null
    try {
        const parsed: unknown = JSON.parse(row.payloadJson)
        if (!parsed || typeof parsed !== 'object') return null
        const value = parsed as Record<string, unknown>
        if (!Array.isArray(value.dataSourceRefs) || !value.dataSourceRefs.length) return null
        const dataSourceRefs: DataSourceExportRef[] = []
        const ids = new Set<string>()
        for (const rawRef of value.dataSourceRefs) {
            if (!rawRef || typeof rawRef !== 'object') return null
            const ref = rawRef as Record<string, unknown>
            if (typeof ref.id !== 'string' || !ref.id.trim()) return null
            if (typeof ref.displayName !== 'string' || !ref.displayName.trim()) return null
            const id = ref.id.trim()
            if (ids.has(id)) return null
            ids.add(id)
            dataSourceRefs.push({ id, displayName: ref.displayName.trim() })
        }
        if (typeof value.credentialMode !== 'string' || !CREDENTIAL_MODES.has(value.credentialMode)) return null
        if (typeof value.plaintextRiskConfirmed !== 'boolean') return null
        if (value.credentialMode === 'PLAINTEXT' && !value.plaintextRiskConfirmed) return null
        dataSourceRefs.sort((left, right) => left.id.localeCompare(right.id))
        return {
            dataSourceRefs,
            credentialMode: value.credentialMode as CredentialExportMode,
            plaintextRiskConfirmed: value.plaintextRiskConfirmed,
        }
    } catch {
        return null
    }
}

export function buildImportDecisions(
    items: ConnectionImportPreviewItem[],
    choices: Record<string, { action: ConnectionImportDecision['action']; renameTo?: string }>,
): ConnectionImportDecision[] {
    return items.map(item => {
        const selected = choices[item.entryKey] ?? { action: item.conflict ? 'SKIP' : 'OVERWRITE' as const }
        const renameTo = selected.action === 'RENAME' ? selected.renameTo?.trim() : undefined
        if (selected.action === 'RENAME' && !renameTo) {
            throw new Error(`missing rename target: ${item.entryKey}`)
        }
        return { entryKey: item.entryKey, action: selected.action, newDisplayName: renameTo }
    })
}

export function normalizeMetadataSelection(selection: MetadataSelection): MetadataSelection {
    const clean = (values?: string[]) => values
        ? Array.from(new Set(values.map(value => value.trim()).filter(Boolean))).sort()
        : undefined
    return {
        grantedSourceName: selection.grantedSourceName.trim(),
        database: selection.database?.trim() || undefined,
        schemas: clean(selection.schemas),
        tables: clean(selection.tables),
    }
}

export function safeDownloadStem(value: string): string {
    const normalized = value.trim().replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '')
    return normalized.slice(0, 80) || 'lyradb'
}

export function hasMetadataScope(selection: MetadataSelection): boolean {
    return Boolean(
        selection.database?.trim()
        || selection.schemas?.some(value => value.trim())
        || selection.tables?.some(value => value.trim().includes('.'))
    )
}

export function formatMetadataPreview(items: MetadataTablePreview[]): string {
    if (!items.length) return ''
    return items.map(item => {
        const qualifiedName = [item.database, item.schema, item.table].filter(Boolean).join('.')
        const type = item.type ? ` [${item.type}]` : ''
        const columns = item.columns.length ? `\n  ${item.columns.join(', ')}` : '\n  （无字段）'
        return `${qualifiedName}${type}${columns}`
    }).join('\n\n')
}