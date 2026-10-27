// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { ElMessage } from 'element-plus'
import { entApi, type ApprovalRequest } from '@/api/ent'
import { saveBlob } from '@/utils/download'
import ApprovalsView from './ApprovalsView.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: { tab: 'mine' } }) }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ canApprove: false }) }))
vi.mock('@/api/ent', () => ({ entApi: {
  approvals: vi.fn(), approvalDetail: vi.fn(), grantsMine: vi.fn(), export: vi.fn(),
} }))
vi.mock('@/utils/download', () => ({ saveBlob: vi.fn() }))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn() }, ElMessageBox: {},
}))

// 渲染实际加载到表格的审批记录，保留列插槽中的下载按钮及点击处理。
const tableRows = Symbol('approval-table-rows')
const TableStub = defineComponent({
  props: ['data'],
  setup(props, { slots }) {
    provide(tableRows, computed(() => props.data))
    return () => h('div', slots.default?.())
  },
})
const TableColumnStub = defineComponent({
  setup(_, { slots }) {
    const rows = inject<ComputedRef<ApprovalRequest[]>>(tableRows)!
    return () => h('div', rows.value.flatMap(row => slots.default?.({ row }) ?? []))
  },
})
const wrappers: ReturnType<typeof shallowMount>[] = []
const sql = 'select id,\n  amount from dw.orders;'

beforeEach(() => {
  vi.mocked(entApi.grantsMine).mockResolvedValue([])
  vi.mocked(saveBlob).mockResolvedValue(undefined)
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  vi.resetAllMocks()
})

async function open(payload: unknown, status = 'APPROVED') {
  const approval: ApprovalRequest = {
    id: 'approval-1', operationType: 'EXPORT', grantedSourceName: 'sales',
    payloadJson: JSON.stringify(payload), status,
  }
  const { payloadJson: _, ...summary } = approval
  vi.mocked(entApi.approvals).mockResolvedValue([summary])
  vi.mocked(entApi.approvalDetail).mockResolvedValue(approval)
  const wrapper = shallowMount(ApprovalsView, { global: {
    renderStubDefaultSlot: true,
    stubs: {
      'el-tabs': true, 'el-tab-pane': true, 'el-tag': true,
      'el-table': TableStub, 'el-table-column': TableColumnStub,
      'el-button': {
        props: ['disabled', 'loading'],
        template: '<button :disabled="disabled || loading"><slot /></button>',
      },
      'el-dialog': { template: '<div />' },
      'el-form': true, 'el-form-item': true, 'el-select': true,
      'el-option': true, 'el-input': true,
    },
  } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe.each(['csv', 'json'] as const)('已批准的 %s 查询结果下载', format => {
  it.each([
    { label: '服务端省略默认数据库', fields: {}, expectedDatabase: null },
    { label: '默认数据库为空', fields: { defaultDatabase: null }, expectedDatabase: null },
    { label: '指定默认数据库', fields: { defaultDatabase: 'dw' }, expectedDatabase: 'dw' },
  ])('$label 时按审批原文下载并刷新状态', async ({ fields, expectedDatabase }) => {
    const blob = new Blob([format === 'csv' ? 'id,amount\n1,10' : '[{"id":1,"amount":10}]'])
    vi.mocked(entApi.export).mockResolvedValue(blob)
    const wrapper = await open({ sql, format, ...fields })

    await wrapper.findAll('button').find(button => button.text() === '下载')!.trigger('click')
    await flushPromises()

    expect(entApi.approvalDetail).toHaveBeenCalledWith('approval-1')
    expect(entApi.export).toHaveBeenCalledExactlyOnceWith('approval-1', {
      sql, format, defaultDatabase: expectedDatabase,
    })
    expect(saveBlob).toHaveBeenCalledExactlyOnceWith(blob, `lyradb-export-approval-1.${format}`)
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(entApi.approvals).toHaveBeenCalledTimes(2)
  })
})

describe('审批下载校验', () => {
  it.each([
    { sql, format: 'csv', defaultDatabase: 123 },
    { sql, format: 'csv', defaultDatabase: { name: 'dw' } },
    { sql: ' ', format: 'csv' },
    { sql, format: 'xlsx' },
  ])('无效审批内容不得发起下载：%j', async payload => {
    const wrapper = await open(payload)
    await wrapper.findAll('button').find(button => button.text() === '下载')!.trigger('click')
    await flushPromises()

    expect(entApi.export).not.toHaveBeenCalled()
    expect(saveBlob).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('审批内容不完整，无法安全下载')
  })

  it.each(['PENDING', 'DONE'])('%s 状态不展示下载按钮', async status => {
    const wrapper = await open({ sql, format: 'csv' }, status)
    expect(wrapper.findAll('button').some(button => button.text() === '下载')).toBe(false)
    expect(entApi.export).not.toHaveBeenCalled()
  })
})
