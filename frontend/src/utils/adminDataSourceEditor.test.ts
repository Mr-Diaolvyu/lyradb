import { describe, expect, it } from 'vitest'
import type { FormField } from '@/types/driver'
import {
    buildAdminDataSourceFields,
    buildAdminDataSourceFormParams,
    buildAdminDataSourceParamPayload,
    firstMissingRequiredField,
    isStoredAdminCredential,
} from './adminDataSourceEditor'

const maxComputeFields: FormField[] = [
    { name: 'endpoint', label: 'Endpoint', type: 'text', required: true },
    { name: 'project', label: 'Project', type: 'text', required: true },
    { name: 'accessKeyId', label: 'AccessKey ID', type: 'text', required: true },
    { name: 'accessKeySecret', label: 'AccessKey Secret', type: 'password', required: true },
]

describe('admin data source editor', () => {
    it('uses driver metadata and preserves imported advanced parameters', () => {
        const fields = buildAdminDataSourceFields(maxComputeFields, {
            endpoint: 'https://service.cn-hangzhou.maxcompute.aliyun.com/api',
            project: 'old_jfdw_maxcompute',
            accessKeyId: 'ak-id',
            accessKeySecret: '********',
            quotaName: 'pay-as-you-go',
            clientCredential: '********',
        })

        expect(fields.map(field => field.name)).toEqual([
            'endpoint', 'project', 'accessKeyId', 'accessKeySecret',
            'quotaName', 'clientCredential',
        ])
        expect(fields.find(field => field.name === 'clientCredential')?.type)
            .toBe('password')
    })

    it('initializes stored credentials as blank without losing ordinary values', () => {
        const fields = buildAdminDataSourceFields(maxComputeFields)
        const params = buildAdminDataSourceFormParams(fields, {
            endpoint: 'endpoint',
            project: 'project-a',
            accessKeyId: 'ak-id',
            accessKeySecret: '********',
        })

        expect(params).toMatchObject({
            endpoint: 'endpoint',
            project: 'project-a',
            accessKeyId: 'ak-id',
            accessKeySecret: '',
        })
        expect(isStoredAdminCredential(fields[3], {
            accessKeySecret: '********',
        })).toBe(true)
    })

    it('sends only user-changed fields while edit-time reveal remains non-dirty', () => {
        const fields = buildAdminDataSourceFields(maxComputeFields)
        const params = buildAdminDataSourceFormParams(fields, {
            endpoint: 'old-endpoint',
            project: 'project-a',
            accessKeyId: 'ak-id',
            accessKeySecret: '********',
        })
        params.endpoint = 'new-endpoint'
        params.accessKeySecret = 'revealed-secret'

        expect(buildAdminDataSourceParamPayload(
            fields, params, { endpoint: true }, true,
        )).toEqual({ endpoint: 'new-endpoint' })
        expect(buildAdminDataSourceParamPayload(
            fields, params, { accessKeySecret: true }, true,
        )).toEqual({ accessKeySecret: 'revealed-secret' })
    })

    it('accepts an unchanged stored required credential but validates new forms', () => {
        const fields = buildAdminDataSourceFields(maxComputeFields)
        const existing = { accessKeySecret: '********' }
        const params = buildAdminDataSourceFormParams(fields, existing)

        expect(firstMissingRequiredField(
            fields, params, existing, {}, true,
        )?.name).toBe('endpoint')
        params.endpoint = 'endpoint'
        params.project = 'project'
        params.accessKeyId = 'ak-id'
        expect(firstMissingRequiredField(
            fields, params, existing, {}, true,
        )).toBeUndefined()
        expect(firstMissingRequiredField(
            fields, params, {}, {}, false,
        )?.name).toBe('accessKeySecret')
    })
})
