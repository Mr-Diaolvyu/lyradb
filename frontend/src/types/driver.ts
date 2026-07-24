/**
 * 驱动相关类型定义
 */

export interface MavenCoordinates {
    groupId: string
    artifactId: string
    version: string
    classifier?: string
}

export interface FormFieldOption {
    label: string
    value: string
}

export interface FormField {
    name: string
    label: string
    type: 'text' | 'password' | 'number' | 'boolean' | 'select'
    required: boolean
    defaultValue?: any
    options?: FormFieldOption[]
}

export interface DriverCapability {
    supportsTransaction: boolean
    supportsDML: boolean
    supportsDDL: boolean
    supportsPartition: boolean
    supportsViews: boolean
    supportsProcedures: boolean
    supportsFunctions: boolean
    supportsIndexes: boolean
    supportsTriggers: boolean
    supportsSSL: boolean
    readOnly: boolean
    supportsMaxComputePartition?: boolean
    supportsDocumentTree?: boolean
    supportsKeyPrefixGrouping?: boolean
}

export interface DriverInfo {
    dbType: string
    displayName: string
    driverType: 'jdbc' | 'nosql'
    driverClass: string
    mavenCoordinates: MavenCoordinates
    connectionUrlTemplate: string
    defaultPort: number
    capabilities: DriverCapability
    connectionFormFields: FormField[]
}

export interface DriverStatus {
    dbType: string
    displayName: string
    downloaded: boolean
}

export interface DatabaseType {
    dbType: string
    displayName: string
    driverType: string
    defaultPort: string
}
