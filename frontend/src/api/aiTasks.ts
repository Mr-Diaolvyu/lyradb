import apiClient from './index'
import type { AiChatRequest, MetadataSelection, MetadataSnapshotSummary } from './ent'
import type { AiChatResponse } from '@/types/ai'

export interface AiTask {
  id: string
  kind: 'CHAT' | 'FIND_TABLE' | 'METADATA'
  grantedSourceName: string
  message?: string
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  createdAt: number
  finishedAt: number
  metadataAttached: boolean
  metadataSnapshotId?: string
  result?: AiChatResponse | MetadataSnapshotSummary | { message: string; recommendations: Array<{ path: string; reason: string }> }
  error?: string
}
export type AiTaskRequest = Partial<AiChatRequest & MetadataSelection> & {
  requestId: string
  kind: AiTask['kind']
  grantedSourceName: string
}
export const aiTasksApi = {
  list: (): Promise<AiTask[]> => apiClient.get('/ai/tasks'),
  submit: (request: AiTaskRequest): Promise<AiTask> => apiClient.post('/ai/tasks', request),
}
export const taskActive = (task: AiTask) => task.state === 'QUEUED' || task.state === 'RUNNING'
