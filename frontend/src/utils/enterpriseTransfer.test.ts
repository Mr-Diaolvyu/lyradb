import { describe, expect, it } from 'vitest'
import type { ApprovalRequest, ConnectionImportPreviewItem } from '@/api/ent'
import {
    buildImportDecisions,
    CONNECTION_IMPORT_TEMPLATE_FILE_NAME,
    formatMetadataPreview,
    isExcelConnectionImportFile,
    isSupportedConnectionImportFile,
    hasMetadataScope,
    normalizeMetadataSelection,
    parseDataSourceExportPayload,
    safeDownloadStem,
} from './enterpriseTransfer'

function approval(payload: unknown): ApprovalRequest {
    return {
        id: 'approval-1',
        operationType: 'DATASOURCE_EXPORT',
        payloadJson: JSON.stringify(payload),
        status: 'APPROVED',
    }
}

describe('parseDataSourceExportPayload', () => {
    it('normalizes the server-bound immutable data source scope', () => {
        expect(parseDataSourceExportPayload(approval({
            dataSourceRefs: [
                { id: 'b', displayName: '分析库' },
                { id: 'a', displayName: '生产库' },
            ],
            credentialMode: 'PASSWORD_ENCRYPTED',
            plaintextRiskConfirmed: false,
        }))).toEqual({
            dataSourceRefs: [
                { id: 'a', displayName: '生产库' },
                { id: 'b', displayName: '分析库' },
            ],
            credentialMode: 'PASSWORD_ENCRYPTED',
            plaintextRiskConfirmed: false,
        })
    })

    it('rejects plaintext export without explicit risk confirmation', () => {
        expect(parseDataSourceExportPayload(approval({
            dataSourceRefs: [{ id: 'a', displayName: '生产库' }],
            credentialMode: 'PLAINTEXT',
            plaintextRiskConfirmed: false,
        }))).toBeNull()
    })

    it('rejects duplicate or incomplete immutable source references', () => {
        expect(parseDataSourceExportPayload(approval({
            dataSourceRefs: [
                { id: 'a', displayName: '生产库' },
                { id: 'a', displayName: '伪造名称' },
            ],
            credentialMode: 'OMIT',
            plaintextRiskConfirmed: false,
        }))).toBeNull()
        expect(parseDataSourceExportPayload(approval({
            dataSourceRefs: [{ id: 'a', displayName: ' ' }],
            credentialMode: 'OMIT',
            plaintextRiskConfirmed: false,
        }))).toBeNull()
    })
})

describe('buildImportDecisions', () => {
    const items: ConnectionImportPreviewItem[] = [
        { entryKey: 'new', displayName: 'new source', dbType: 'mysql', parameterKeys: [], credentialKeys: [], credentialsIncluded: false, conflict: false },
        { entryKey: 'existing', displayName: 'existing source', dbType: 'mysql', parameterKeys: [], credentialKeys: [], credentialsIncluded: false, conflict: true },
    ]

    it('imports non-conflicting entries directly and skips unresolved conflicts by default', () => {
        expect(buildImportDecisions(items, {})).toEqual([
            { entryKey: 'new', action: 'OVERWRITE', newDisplayName: undefined },
            { entryKey: 'existing', action: 'SKIP', newDisplayName: undefined },
        ])
    })

    it('trims rename targets and rejects an empty target', () => {
        expect(buildImportDecisions(items.slice(0, 1), {
            new: { action: 'RENAME', renameTo: '  renamed  ' },
        })).toEqual([{ entryKey: 'new', action: 'RENAME', newDisplayName: 'renamed' }])
        expect(() => buildImportDecisions(items.slice(0, 1), {
            new: { action: 'RENAME', renameTo: '   ' },
        })).toThrow('missing rename target')
    })
})

describe('metadata helpers', () => {
    it('deduplicates and sorts explicit metadata scope', () => {
        expect(normalizeMetadataSelection({
            grantedSourceName: ' source ',
            database: ' db ',
            schemas: [' public ', 'audit', 'public'],
            tables: ['users', ' orders ', 'users'],
        })).toEqual({
            grantedSourceName: 'source',
            database: 'db',
            schemas: ['audit', 'public'],
            tables: ['orders', 'users'],
        })
    })

    it('requires at least one explicit metadata range', () => {
        expect(hasMetadataScope({ grantedSourceName: 'source' })).toBe(false)
        expect(hasMetadataScope({ grantedSourceName: 'source', database: 'db' })).toBe(true)
        expect(hasMetadataScope({ grantedSourceName: 'source', schemas: ['  '] })).toBe(false)
        expect(hasMetadataScope({ grantedSourceName: 'source', tables: ['orders'] })).toBe(false)
        expect(hasMetadataScope({ grantedSourceName: 'source', tables: ['public.orders'] })).toBe(true)
    })

    it('formats structured table previews without treating them as executable markup', () => {
        expect(formatMetadataPreview([{
            database: 'sales',
            schema: 'public',
            table: 'orders',
            type: 'TABLE',
            columns: ['id', 'amount'],
        }])).toBe('sales.public.orders [TABLE]\n  id, amount')
        expect(formatMetadataPreview([{
            table: 'empty_table',
            columns: [],
        }])).toBe('empty_table\n  （无字段）')
    })

    it('creates a safe deterministic download stem', () => {
        expect(safeDownloadStem(' Finance / Prod ')).toBe('Finance-Prod')
        expect(safeDownloadStem('中文')).toBe('lyradb')
    })
})
describe('connection import file helpers', () => {
    it('publishes the stable Excel template filename', () => {
        expect(CONNECTION_IMPORT_TEMPLATE_FILE_NAME).toBe('LyraDB-连接导入模板.xlsx')
    })

    it('accepts supported import formats case-insensitively', () => {
        expect(isSupportedConnectionImportFile('connections.xlsx')).toBe(true)
        expect(isSupportedConnectionImportFile('connections.JSON')).toBe(true)
        expect(isSupportedConnectionImportFile('connections.lyradb')).toBe(true)
        expect(isSupportedConnectionImportFile('connections.csv')).toBe(false)
    })

    it('identifies Excel imports for password-field behavior', () => {
        expect(isExcelConnectionImportFile('connections.XLSX')).toBe(true)
        expect(isExcelConnectionImportFile('connections.json')).toBe(false)
        expect(isExcelConnectionImportFile()).toBe(false)
    })
})
