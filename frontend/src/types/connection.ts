/**
 * 连接相关类型定义
 */

export interface ConnectionParams {
    [key: string]: any
}

export interface ConnectionDTO {
    id: string
    name: string
    dbType: string
    displayName: string
    params: ConnectionParams
    group?: string
    color?: string
    description?: string
    tags?: string[]
    favorite?: boolean
    sortOrder?: number
    autoConnect?: boolean
    createdAt?: string
    updatedAt?: string
    status: 'CONNECTED' | 'DISCONNECTED' | 'ERROR'
    errorMessage?: string
}

export interface TestConnectionRequest {
    dbType: string
    params: ConnectionParams
}

export interface TestConnectionResult {
    success: boolean
    message: string
}

export interface ConnectResult {
    success: boolean
    message: string
}

export interface ImportResult {
    success: number
    failed: number
    errors: string[]
}
