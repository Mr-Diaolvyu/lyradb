import { describe, expect, it } from 'vitest'
import {
    parseSqlCompletionContext,
    resolveCompletionTable,
    type SqlCompletionTable,
} from './sqlCompletion'

const tables: SqlCompletionTable[] = [
    {
        schema: 'erp',
        namespace: 'erp',
        name: 'sales_order',
        qualifiedName: 'erp.sales_order',
    },
    {
        schema: 'public',
        namespace: 'public',
        name: 'app_user',
        qualifiedName: 'public.app_user',
    },
]

describe('SQL 补全上下文', () => {
    it('可在 FROM 位于光标之后时解析别名', () => {
        const sql = 'SELECT o.am FROM erp.sales_order AS o WHERE o.id > 0'
        const context = parseSqlCompletionContext(
            sql,
            sql.indexOf('o.am') + 'o.am'.length,
        )

        expect(context.prefix).toBe('am')
        expect(context.qualifier).toBe('o')
        expect(resolveCompletionTable(context, tables)?.qualifiedName)
            .toBe('erp.sales_order')
    })

    it('不会把 WHERE 当作表别名', () => {
        const sql = 'SELECT app_user. FROM public.app_user WHERE id > 0'
        const context = parseSqlCompletionContext(
            sql,
            sql.indexOf('app_user.') + 'app_user.'.length,
        )

        expect(context.references.where).toBeUndefined()
        expect(resolveCompletionTable(context, tables)?.name)
            .toBe('app_user')
    })
})
