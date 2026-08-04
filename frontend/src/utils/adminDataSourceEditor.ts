import type { FormField } from '@/types/driver'

export const MASKED_ADMIN_CREDENTIAL = '********'

export type AdminDataSourceDirtyParams = Record<string, boolean>

function owns(value: Record<string, any>, key: string): boolean {
    return Object.prototype.hasOwnProperty.call(value, key)
}

function looksSensitive(name: string): boolean {
    return /password|passphrase|accesskeysecret|api.?key|private.?key|token|secret/i
        .test(name)
}

export function isAdminCredentialField(
    field: FormField,
    existingParams: Record<string, any> = {},
): boolean {
    return field.type === 'password'
        || existingParams[field.name] === MASKED_ADMIN_CREDENTIAL
        || looksSensitive(field.name)
}

export function isStoredAdminCredential(
    field: FormField,
    existingParams: Record<string, any>,
): boolean {
    return isAdminCredentialField(field, existingParams)
        && existingParams[field.name] === MASKED_ADMIN_CREDENTIAL
}

export function buildAdminDataSourceFields(
    driverFields: FormField[],
    existingParams: Record<string, any> = {},
): FormField[] {
    const fields = driverFields.map(field => ({ ...field }))
    const known = new Set(fields.map(field => field.name))
    for (const name of Object.keys(existingParams)) {
        if (known.has(name)) continue
        const credential = existingParams[name] === MASKED_ADMIN_CREDENTIAL
            || looksSensitive(name)
        fields.push({
            name,
            label: name,
            type: credential ? 'password' : 'text',
            required: false,
            defaultValue: credential ? '' : existingParams[name],
        })
    }
    return fields
}

export function buildAdminDataSourceFormParams(
    fields: FormField[],
    existingParams: Record<string, any> = {},
): Record<string, any> {
    const result: Record<string, any> = {}
    for (const field of fields) {
        if (owns(existingParams, field.name)) {
            const value = existingParams[field.name]
            result[field.name] = isAdminCredentialField(field, existingParams)
                && value === MASKED_ADMIN_CREDENTIAL ? '' : value
        } else if (field.defaultValue !== undefined) {
            result[field.name] = field.defaultValue
        } else {
            result[field.name] = field.type === 'boolean' ? false : ''
        }
    }
    return result
}

export function buildAdminDataSourceParamPayload(
    fields: FormField[],
    formParams: Record<string, any>,
    dirtyParams: AdminDataSourceDirtyParams,
    editing: boolean,
): Record<string, any> {
    const result: Record<string, any> = {}
    for (const field of fields) {
        if (editing && !dirtyParams[field.name]) continue
        result[field.name] = formParams[field.name] ?? ''
    }
    return result
}

export function firstMissingRequiredField(
    fields: FormField[],
    formParams: Record<string, any>,
    existingParams: Record<string, any>,
    dirtyParams: AdminDataSourceDirtyParams,
    editing: boolean,
): FormField | undefined {
    return fields.find(field => {
        if (!field.required) return false
        if (editing && isStoredAdminCredential(field, existingParams)
            && !dirtyParams[field.name]) {
            return false
        }
        const value = formParams[field.name]
        return value === null || value === undefined
            || (typeof value === 'string' && value.trim() === '')
    })
}
