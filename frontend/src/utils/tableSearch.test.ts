import { describe, expect, it } from 'vitest'
import type {
  AiTableRecommendation,
  EnterpriseMetadataTable,
} from '@/api/ent'
import {
  filterMetadataTables,
  resolveRecommendedTables,
} from './tableSearch'

const tables: EnterpriseMetadataTable[] = [
  {
    schema: 'dwd', namespace: 'dwd', name: 'dwd_project_info',
    qualifiedName: 'dwd.dwd_project_info', type: 'TABLE',
    remarks: '项目基础信息明细表',
  },
  {
    schema: 'dim', namespace: 'dim', name: 'dim_customer',
    qualifiedName: 'dim.dim_customer', type: 'TABLE',
    remarks: '客户维度表',
  },
]

describe('tableSearch', () => {
  it('普通搜索同时匹配中文注释和完整路径', () => {
    expect(filterMetadataTables(tables, '项目').map(item => item.name))
      .toEqual(['dwd_project_info'])
    expect(filterMetadataTables(tables, 'dim.dim_customer').map(item => item.name))
      .toEqual(['dim_customer'])
  })

  it('AI 推荐顺序只接受目录中存在的对象', () => {
    const recommendations: AiTableRecommendation[] = [
      { path: 'dim.dim_customer', reason: '客户主题', confidence: 90 },
      { path: 'secret.hidden', reason: '越权对象', confidence: 100 },
      { path: 'dwd.dwd_project_info', reason: '项目主题', confidence: 80 },
    ]
    expect(resolveRecommendedTables(tables, recommendations)
      .map(item => item.qualifiedName))
      .toEqual(['dim.dim_customer', 'dwd.dwd_project_info'])
  })
})
