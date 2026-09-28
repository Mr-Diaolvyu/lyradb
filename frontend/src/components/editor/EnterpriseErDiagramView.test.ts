// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import EnterpriseErDiagramView from './EnterpriseErDiagramView.vue'
import { entApi } from '@/api/ent'

vi.mock('@/api/ent', () => ({ entApi: {
  metadataCatalog: vi.fn(), metadataColumns: vi.fn(), erDiagram: vi.fn(), lineage: vi.fn(),
} }))
vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<i />' }, VueFlow: { template: '<div />' },
  Position: { Left: 'left', Right: 'right' }, MarkerType: { ArrowClosed: 'closed' },
}))
vi.mock('@vue-flow/background', () => ({ Background: { template: '<i />' } }))
vi.mock('@vue-flow/controls', () => ({ Controls: { template: '<i />' } }))

afterEach(() => { vi.clearAllMocks(); vi.useRealTimers() })

async function open(dbType: string) {
  vi.mocked(entApi.metadataCatalog).mockResolvedValue({ grantedSourceName: 'sales', dbType,
    schemas: ['public'], tables: [{ name: 'orders', schema: 'public', namespace: 'public',
      qualifiedName: 'public.orders', type: 'TABLE' }], truncated: false, refreshedAt: 1 })
  vi.mocked(entApi.erDiagram).mockResolvedValue({ sourceName: 'sales', dbType, schema: 'public', tables: [], edges: [], truncated: false })
  vi.mocked(entApi.lineage).mockResolvedValue({ sourceName: 'sales', dbType, schema: 'public', tables: [], edges: [], truncated: false })
  const wrapper = shallowMount(EnterpriseErDiagramView, {
    props: { visible: false, grants: [{ id: 'g', grantedSourceName: 'sales', dbType } as any] },
    global: { stubs: { 'el-dialog': { template: '<div><slot /></div>' } } },
  })
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}

describe('企业图谱加载边界', () => {
  it('普通数据库自动请求完整授权范围，不再要求手选 24 张表', async () => {
    const wrapper = await open('POSTGRESQL')
    expect(entApi.erDiagram).toHaveBeenCalledWith('sales', 'public', [])
    expect(entApi.lineage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('MaxCompute 默认手动探查，并在关闭窗口后停止定时探查', async () => {
    const wrapper = await open('MAXCOMPUTE')
    expect(entApi.erDiagram).not.toHaveBeenCalled()
    expect(entApi.lineage).not.toHaveBeenCalled()
    const vm = wrapper.vm as any
    vm.selectedTableNames = ['orders']
    await vm.loadDiagram()
    expect(entApi.lineage).toHaveBeenCalledWith(expect.objectContaining({ tables: ['orders'], direction: 'BOTH' }))
    vi.useFakeTimers()
    vm.probePolicy = 'EVERY_30_MINUTES'
    await wrapper.vm.$nextTick()
    await wrapper.setProps({ visible: false })
    await vi.advanceTimersByTimeAsync(30 * 60_000)
    expect(entApi.lineage).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('字段模式必须选择真实字段才发送血缘请求', async () => {
    const wrapper = await open('MAXCOMPUTE')
    const vm = wrapper.vm as any
    vm.selectedTableNames = ['orders']
    vm.lineageKind = 'COLUMN'
    await vm.loadDiagram()
    expect(entApi.lineage).not.toHaveBeenCalled()
    vm.lineageColumn = 'id'
    await vm.loadDiagram()
    expect(entApi.lineage).toHaveBeenCalledWith(expect.objectContaining({ column: 'id', tables: ['orders'] }))
    wrapper.unmount()
  })
})
