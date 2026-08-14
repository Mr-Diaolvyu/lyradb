import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { metadataApi } from '@/api/metadata'
import { useEditorStore } from './editor'
import type { TableInspection } from '@/types/metadata'

vi.mock('@/api/metadata', () => ({
  metadataApi: {
    inspectTable: vi.fn(),
    getTablePartitions: vi.fn(),
  },
  queryApi: {},
}))

const inspection: TableInspection = {
  schema: 'warehouse_project',
  table: 'fact_customer_behavior_with_a_very_long_physical_name',
  objectType: 'TABLE',
  columns: [],
  constraints: [],
  preview: null,
  previewSql: '',
  ddl: '',
  errors: {},
  dbType: 'MAXCOMPUTE',
  partitioned: false,
  previewRequiresPartition: false,
}

describe('editor table tabs', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(metadataApi.inspectTable).mockResolvedValue(inspection)
  })

  it('switches active content and focuses an existing long-title tab on reopen', async () => {
    const store = useEditorStore()
    const first = await store.createTableDetailTab(
      'connection-1',
      'fact_customer_behavior_with_a_very_long_physical_name',
      'warehouse_project',
    )
    const second = await store.createTableDetailTab(
      'connection-1',
      'fact_order_detail_with_another_very_long_physical_name',
      'warehouse_project',
    )

    expect(store.activeTabId).toBe(second)
    store.setActiveTab(first)
    expect(store.activeTabId).toBe(first)
    expect(store.activeTab?.title).toContain(
      'fact_customer_behavior_with_a_very_long_physical_name',
    )

    const reopened = await store.createTableDetailTab(
      'CONNECTION-1',
      'FACT_CUSTOMER_BEHAVIOR_WITH_A_VERY_LONG_PHYSICAL_NAME',
      'WAREHOUSE_PROJECT',
    )
    expect(reopened).toBe(first)
    expect(store.tabs).toHaveLength(2)
    expect(store.activeTabId).toBe(first)
  })
})
