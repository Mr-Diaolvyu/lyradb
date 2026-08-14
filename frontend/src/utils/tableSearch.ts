import type {
  AiTableRecommendation,
  EnterpriseMetadataTable,
} from '@/api/ent'

export function filterMetadataTables(
  tables: EnterpriseMetadataTable[],
  query: string,
  schema = '',
): EnterpriseMetadataTable[] {
  const keyword = query.trim().toLocaleLowerCase()
  return tables.filter((table) => {
    if (schema && table.schema !== schema) return false
    if (!keyword) return true
    return table.name.toLocaleLowerCase().includes(keyword)
      || table.schema.toLocaleLowerCase().includes(keyword)
      || table.qualifiedName.toLocaleLowerCase().includes(keyword)
      || (table.remarks || '').toLocaleLowerCase().includes(keyword)
  })
}

export function resolveRecommendedTables(
  tables: EnterpriseMetadataTable[],
  recommendations: AiTableRecommendation[],
  schema = '',
): EnterpriseMetadataTable[] {
  const byPath = new Map(
    tables.map(table => [table.qualifiedName.toLocaleLowerCase(), table]),
  )
  const seen = new Set<string>()
  const result: EnterpriseMetadataTable[] = []
  for (const recommendation of recommendations) {
    const key = recommendation.path.trim().toLocaleLowerCase()
    const table = byPath.get(key)
    if (!table || seen.has(key) || (schema && table.schema !== schema)) continue
    seen.add(key)
    result.push(table)
  }
  return result
}

export function recommendationReasons(
  recommendations: AiTableRecommendation[],
): Map<string, AiTableRecommendation> {
  return new Map(recommendations.map(item => [
    item.path.trim().toLocaleLowerCase(),
    item,
  ]))
}
