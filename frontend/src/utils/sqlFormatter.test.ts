import { describe, expect, it } from 'vitest'
import { formatSql } from './sqlFormatter'

describe('formatSql', () => {
  it('keeps executable tokens separated when breaking clauses', () => {
    expect(formatSql('SELECT * FROM erp_base.tb_base_masterdata_customer;'))
      .toBe('SELECT *\nFROM erp_base.tb_base_masterdata_customer;')
    expect(formatSql('SELECT id,name FROM users WHERE id = 1 ORDER BY name'))
      .toBe('SELECT id,name\nFROM users\nWHERE id = 1\nORDER BY name')
  })

  it('preserves literals, comments, operators and statement delimiters', () => {
    const sql = "SELECT '-- FROM', a::text FROM t -- keep WHERE\nWHERE note = 'a  b' AND id >= 2;"
    const formatted = formatSql(sql)
    expect(formatted).toContain("'-- FROM'")
    expect(formatted).toContain('a::text')
    expect(formatted).toContain('-- keep WHERE\nWHERE')
    expect(formatted).toContain("'a  b'")
    expect(formatted).toContain('id >= 2;')
    expect(formatted.match(/;/g)).toHaveLength(1)
  })

  it('keeps multiple statements and is stable when run twice', () => {
    const sql = 'SELECT a FROM t; SELECT b FROM u'
    const formatted = formatSql(sql)
    expect(formatted).toBe('SELECT a\nFROM t;\nSELECT b\nFROM u')
    expect(formatSql(formatted)).toBe(formatted)
  })

  it('keeps compound joins together', () => {
    expect(formatSql('SELECT a FROM t LEFT JOIN u ON t.id = u.id'))
      .toBe('SELECT a\nFROM t\nLEFT JOIN u ON t.id = u.id')
  })

  it('does not turn MySQL hash comment text into SQL', () => {
    const sql = 'SELECT a # FROM is only a comment\nFROM t'
    expect(formatSql(sql)).toBe(sql)
  })
})
