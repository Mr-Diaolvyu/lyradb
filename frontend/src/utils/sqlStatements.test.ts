import { describe, expect, it } from 'vitest'
import { splitSqlStatements, statementAtCursor } from './sqlStatements'

describe('企业 SQL 工作区分句', () => {
  it('跳过字符串、注释和 PostgreSQL dollar 字符串内的分号', () => {
    const script = "SELECT ';' AS v; /* ; */ SELECT $$a;b$$ AS v; -- ;\nSELECT 3"
    expect(splitSqlStatements(script).map(item => item.sql)).toEqual([
      "SELECT ';' AS v",
      '/* ; */ SELECT $$a;b$$ AS v',
      '-- ;\nSELECT 3',
    ])
  })

  it('在光标所在语句执行，纯注释不产生语句', () => {
    const script = 'SELECT 1; -- note\nSELECT 2;'
    expect(statementAtCursor(script, script.indexOf('2'))).toBe('-- note\nSELECT 2')
    expect(splitSqlStatements('-- comment\n/* block */')).toEqual([])
  })
})
