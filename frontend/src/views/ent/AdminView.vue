<template>
  <div class="page">
    <div class="page-title"><h2>管理</h2><span class="page-sub">数据源 · 授权 · 用户 · 模型与智库治理（仅管理员）</span></div>

    <el-tabs v-model="tab" @tab-change="load">
      <!-- 数据源 -->
      <el-tab-pane label="数据源" name="ds">
        <div class="bar">
          <el-button type="primary" :icon="Plus" @click="openCreateDataSource">注册数据源</el-button>
          <el-button :icon="Download" :disabled="!selectedDataSources.length" @click="openExport">申请导出所选连接</el-button>
          <el-button :loading="batchTesting" :disabled="!selectedDataSources.length || Boolean(testingDataSourceId)" @click="startBatchTest">批量检测连通性</el-button>
          <el-button :icon="Upload" @click="openImportFile">导入连接</el-button>
          <el-button :icon="Download" @click="downloadImportTemplate">下载 Excel 模板</el-button>
          <input ref="importFileInput" class="sr-only" type="file" accept=".json,.lyradb,.xlsx" aria-label="选择连接导入文件" @change="onImportFile" />
          <span v-if="selectedDataSources.length" class="selection-count">已选择 {{ selectedDataSources.length }} 项</span>
        </div>
        <el-alert
          v-if="dataSourceTestFeedback.message"
          :title="dataSourceTestFeedback.message"
          :type="dataSourceTestFeedback.type"
          :closable="!testingDataSourceId"
          show-icon
          class="data-source-test-feedback"
          @close="clearDataSourceTestFeedback"
        />
        <el-alert v-if="batchTest" type="info" :closable="false" class="data-source-test-feedback"
          :title="`批量检测：${batchTest.items.filter(item => item.state === 'DONE' || item.state === 'ERROR').length}/${batchTest.items.length} 已完成`" />
        <el-table v-if="batchTest" :data="batchTest.items" size="small" max-height="190" class="data-source-test-feedback">
          <el-table-column prop="displayName" label="数据源" />
          <el-table-column label="进度" width="100"><template #default="{ row }">{{ row.state }}</template></el-table-column>
          <el-table-column label="结果"><template #default="{ row }">{{ row.result?.message || '等待检测' }}</template></el-table-column>
        </el-table>
        <el-table :data="dataSources" border size="small" empty-text="无" @selection-change="onDataSourceSelection">
          <el-table-column type="selection" width="44" />
          <el-table-column prop="displayName" label="名称" width="160" />
          <el-table-column prop="dbType" label="类型" width="120" />
          <el-table-column label="最近连通性" width="175">
            <template #default="{ row }">
              <el-tag :type="testStatusType(row.lastTestStatus)" size="small">{{ testStatusLabel(row.lastTestStatus) }}</el-tag>
              <small v-if="row.lastTestedAt" class="test-time">{{ fmt(row.lastTestedAt) }}</small>
            </template>
          </el-table-column>
          <el-table-column label="参数（已掩码）" show-overflow-tooltip>
            <template #default="{ row }">{{ summaryParams(row.params) }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="160"><template #default="{ row }">{{ fmt(row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button size="small" :disabled="Boolean(testingDataSourceId)" @click="openEditDataSource(row)">编辑</el-button>
              <el-button
                size="small"
                :loading="testingDataSourceId === row.id"
                :disabled="Boolean(testingDataSourceId) && testingDataSourceId !== row.id"
                @click="testDs(row)"
              >
                测试
              </el-button>
              <el-button
                size="small"
                type="danger"
                :disabled="Boolean(testingDataSourceId)"
                @click="delDs(row.id)"
              >删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 授权 -->
      <el-tab-pane label="授权" name="grants">
        <div class="bar">
          <el-button type="primary" :icon="Plus" @click="grantCreate.visible = true">分配授权</el-button>
          <el-button @click="openBatchGrant">批量授权</el-button>
        </div>
        <el-table :data="grants" border size="small" empty-text="当前空间暂无授权">
          <el-table-column prop="grantedSourceName" label="逻辑数据源" min-width="145" show-overflow-tooltip />
          <el-table-column label="授权给" min-width="175">
            <template #default="{ row }">{{ grantUserName(row) }}</template>
          </el-table-column>
          <el-table-column label="对应连接" min-width="165">
            <template #default="{ row }">{{ dsName(row.dataSourceId) }}</template>
          </el-table-column>
          <el-table-column label="访问范围" min-width="265">
            <template #default="{ row }"><span :title="row.allowedTables">{{ grantScopeSummary(row) }}</span></template>
          </el-table-column>
          <el-table-column label="查询能力" width="145">
            <template #default="{ row }">
              <el-tag :type="row.sqlCapability === 'DML_ALLOWED' ? 'warning' : 'info'" size="small">
                {{ row.sqlCapability === 'DML_ALLOWED' ? '可写' : '只读' }}
              </el-tag>
              <span class="grant-row-limit">{{ row.maxRowsPerQuery }} 行/次</span>
            </template>
          </el-table-column>
          <el-table-column label="有效期" width="120">
            <template #default="{ row }">
              <el-tag v-if="grantExpired(row)" size="small" type="danger">已过期</el-tag>
              <span v-else :title="row.expiresAt ? fmt(row.expiresAt) : '未设置到期时间'">{{ row.expiresAt ? '限时授权' : '长期有效' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="175" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openGrantDetail(row)">查看配置</el-button>
              <el-button size="small" type="danger" @click="delGrant(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 用户 -->
      <el-tab-pane v-if="auth.hasRole('PLATFORM_ADMIN')" label="用户" name="users">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="userCreate.visible = true">新建用户</el-button></div>
        <el-table :data="users" border size="small" empty-text="无">
          <el-table-column prop="username" label="用户名" width="140" />
          <el-table-column prop="displayName" label="显示名" width="140" />
          <el-table-column prop="email" label="邮箱" />
          <el-table-column label="角色" min-width="280"><template #default="{ row }"><el-tag v-for="r in row.roles" :key="r" size="small" class="role-tag">{{ roleLabel(r) }}</el-tag></template></el-table-column>
          <el-table-column prop="enabled" label="状态" width="80"><template #default="{ row }">{{ row.enabled ? '启用' : '已冻结' }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openUserEditor(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- AI Provider -->
      <el-tab-pane label="模型与 AI" name="ai">
        <div class="bar"><el-button type="primary" :icon="Plus" @click="aiCreate.visible = true">配置 Provider</el-button></div>
        <p class="ai-test-note">“测试连接”会使用已保存的配置向模型发送一次短请求，可能产生少量 Token 费用。</p>
        <el-table :data="aiProviders" border size="small" empty-text="未配置">
          <el-table-column prop="displayName" label="名称" width="140" />
          <el-table-column prop="providerKey" label="类型" width="100" />
          <el-table-column label="部署" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.deploymentMode === 'PRIVATE' ? 'warning' : 'info'">{{ row.deploymentMode }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="baseUrl" label="Base URL" show-overflow-tooltip />
          <el-table-column prop="model" label="模型" width="160" />
          <el-table-column label="KEY" width="100"><template #default="{ row }">{{ row.apiKey ? '已配置' : '空' }}</template></el-table-column>
          <el-table-column label="默认" width="80"><template #default="{ row }">{{ row.isDefault ? '是' : '' }}</template></el-table-column>
          <el-table-column label="操作" width="270">
            <template #default="{ row }">
              <el-button size="small" :loading="aiTestingId === row.id" :disabled="Boolean(aiTestingId) && aiTestingId !== row.id" @click="testAiProvider(row)">测试可用性</el-button>
              <el-button size="small" :disabled="row.isDefault" @click="setDefaultAi(row.id)">设默认</el-button>
              <el-button size="small" type="danger" @click="delAi(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-if="aiTestResult" class="ai-test-result" :type="aiTestResult.success ? 'success' : 'error'"
          :title="aiTestResult.message" :closable="true" @close="aiTestResult = null" />
      </el-tab-pane>

      <!-- 脱敏 -->
      <el-tab-pane label="脱敏" name="masking">
        <div class="bar">
          <el-button type="primary" :icon="Plus" @click="openManualMaskCreate">新建脱敏规则</el-button>
          <el-button @click="maskAi.visible = true">AI 生成规则</el-button>
        </div>
        <el-table :data="maskRules" border size="small" empty-text="无">
          <el-table-column label="数据源" width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ dsName(row.dataSourceId) }}</template>
          </el-table-column>
          <el-table-column prop="tablePattern" label="表匹配" width="140"><template #default="{ row }">{{ row.tablePattern || '不限' }}</template></el-table-column>
          <el-table-column prop="columnPattern" label="列匹配" show-overflow-tooltip />
          <el-table-column prop="maskType" label="方式" width="90"><template #default="{ row }">{{ maskTypeLabel(row.maskType) }}</template></el-table-column>
          <el-table-column prop="remark" label="说明" width="160" show-overflow-tooltip />
          <el-table-column label="启用" width="80">
            <template #default="{ row }"><el-switch :model-value="row.enabled" size="small" @change="toggleMask(row)" /></template>
          </el-table-column>
          <el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" type="danger" @click="delMask(row.id)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="grantDetailVisible" title="授权配置详情" size="520px">
      <el-descriptions v-if="grantDetail" :column="1" border>
        <el-descriptions-item label="逻辑数据源">{{ grantDetail.grantedSourceName }}</el-descriptions-item>
        <el-descriptions-item label="授权给">{{ grantUserName(grantDetail) }}</el-descriptions-item>
        <el-descriptions-item label="对应连接">{{ dsName(grantDetail.dataSourceId) }}</el-descriptions-item>
        <el-descriptions-item label="查询能力">{{ grantDetail.sqlCapability === 'DML_ALLOWED' ? '允许读写' : '只读查询' }}</el-descriptions-item>
        <el-descriptions-item label="每次行数上限">{{ grantDetail.maxRowsPerQuery }} 行</el-descriptions-item>
        <el-descriptions-item label="允许的 Schema"><div class="grant-rule-list"><el-tag v-for="rule in grantRules(grantDetail.allowedSchemas)" :key="rule" size="small" effect="plain">{{ rule }}</el-tag><span v-if="!grantDetail.allowedSchemas">未配置</span></div></el-descriptions-item>
        <el-descriptions-item label="允许的表"><div class="grant-rule-list"><el-tag v-for="rule in grantRules(grantDetail.allowedTables)" :key="rule" size="small" effect="plain">{{ rule === '*.*' ? '*.*（授权 Schema 下全部表）' : rule }}</el-tag><span v-if="!grantDetail.allowedTables">未配置</span></div></el-descriptions-item>
        <el-descriptions-item label="排除的表"><div class="grant-rule-list"><el-tag v-for="rule in grantRules(grantDetail.blockedTables)" :key="rule" size="small" effect="plain" type="danger">{{ rule }}</el-tag><span v-if="!grantDetail.blockedTables">无</span></div></el-descriptions-item>
        <el-descriptions-item label="导出限制">{{ grantDetail.exportApprovedOnly ? '须审批' : '按授权策略执行' }}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{ grantDetail.expiresAt ? fmt(grantDetail.expiresAt) : '未设置到期时间' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert class="grant-detail-hint" type="info" :closable="false"
        title="*.* 表示授权范围内的表通配规则；实际可访问范围还受 Schema、排除表和到期时间共同限制。" />
    </el-drawer>

    <!-- 数据源创建/编辑 -->
    <el-dialog
      v-model="dsEditor.visible"
      :title="dsEditor.mode === 'edit' ? '编辑数据源' : '注册数据源'"
      width="680"
      destroy-on-close
      @closed="resetDataSourceEditor"
    >
      <el-skeleton v-if="dsEditor.loading" :rows="7" animated />
      <el-form v-else label-width="130px">
        <el-form-item label="数据库类型" required>
          <el-select
            v-model="dsEditor.form.dbType"
            style="width:100%"
            :disabled="dsEditor.mode === 'edit' || dsEditor.busy"
            @change="onDbTypeChange"
          >
            <el-option v-for="t in dbTypes" :key="t.dbType" :label="t.displayName" :value="t.dbType" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="dsEditor.form.displayName" :disabled="dsEditor.busy" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="dsEditor.form.description" type="textarea" :rows="2" :disabled="dsEditor.busy" />
        </el-form-item>
        <el-alert
          v-if="dsEditor.mode === 'edit'"
          title="已保存凭据默认不下发。查看或复制时会按管理员权限单字段解密并记录审计；仅查看不会修改连接。"
          type="info"
          :closable="false"
          show-icon
          class="credential-note"
        />
        <el-form-item
          v-for="field in dsEditor.fields"
          :key="field.name"
          :label="field.label"
          :required="field.required"
        >
          <el-switch
            v-if="field.type === 'boolean'"
            v-model="dsEditor.form.params[field.name]"
            :disabled="dsEditor.busy"
            @change="markDataSourceParamDirty(field.name)"
          />
          <el-select
            v-else-if="field.type === 'select'"
            v-model="dsEditor.form.params[field.name]"
            style="width:100%"
            :disabled="dsEditor.busy"
            @change="markDataSourceParamDirty(field.name)"
          >
            <el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input-number
            v-else-if="field.type === 'number'"
            v-model="dsEditor.form.params[field.name]"
            style="width:100%"
            :disabled="dsEditor.busy"
            @change="markDataSourceParamDirty(field.name)"
          />
          <div v-else-if="isCredentialEditorField(field)" class="credential-editor">
            <el-input
              v-model="dsEditor.form.params[field.name]"
              :type="dsEditor.credentialVisible[field.name] ? 'text' : 'password'"
              :placeholder="credentialPlaceholder(field)"
              autocomplete="new-password"
              :disabled="dsEditor.busy"
              @input="markDataSourceParamDirty(field.name)"
            />
            <el-tag v-if="isStoredCredential(field) && !dsEditor.dirtyParams[field.name]" size="small" type="info">已保存</el-tag>
            <el-button
              :loading="dsEditor.credentialLoadingField === field.name"
              :disabled="dsEditor.busy"
              @click="toggleDataSourceCredential(field)"
            >{{ credentialToggleLabel(field) }}</el-button>
            <el-button
              :loading="dsEditor.credentialLoadingField === field.name"
              :disabled="dsEditor.busy"
              @click="copyDataSourceCredential(field)"
            >复制</el-button>
          </div>
          <el-input
            v-else
            v-model="dsEditor.form.params[field.name]"
            :disabled="dsEditor.busy"
            @input="markDataSourceParamDirty(field.name)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dsEditor.visible = false">取消</el-button>
        <el-button type="primary" :loading="dsEditor.busy" :disabled="dsEditor.loading" @click="saveDataSource">保存</el-button>
      </template>
    </el-dialog>

    <!-- 授权创建 -->
    <el-dialog v-model="grantCreate.visible" title="分配授权" width="520">
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="grantCreate.form.dataSourceId" style="width:100%" @change="onGrantSourceChange">
            <el-option v-for="d in dataSources" :key="d.id" :label="`${d.displayName} (${d.dbType})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="授予用户">
          <el-select v-model="grantCreate.form.userId" filterable style="width:100%">
            <el-option v-for="u in eligibleUsers" :key="u.id" :label="u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="逻辑名"><el-input v-model="grantCreate.form.grantedSourceName" /></el-form-item>
        <el-form-item label="授权范围" required>
          <GrantScopePicker
            :data-source-id="grantCreate.form.dataSourceId"
            v-model:schemas="grantCreate.form.allowedSchemas"
            v-model:tables="grantCreate.form.allowedTables"
          />
        </el-form-item>
        <el-form-item label="黑名单表"><el-input v-model="grantCreate.form.blockedTables" placeholder="如 sales.user_secret" /></el-form-item>
        <el-form-item label="能力">
          <el-radio-group v-model="grantCreate.form.sqlCapability">
            <el-radio value="READ_ONLY">只读</el-radio><el-radio value="DML_ALLOWED">可写</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="grantCreate.visible=false">取消</el-button><el-button type="primary" :loading="grantCreate.busy" @click="createGrant">分配</el-button></template>
    </el-dialog>

    <el-dialog v-model="batchGrant.visible" title="批量授权数据源" width="900" destroy-on-close>
      <el-form label-width="95px">
        <el-form-item label="授予用户">
          <el-select v-model="batchGrant.userIds" multiple filterable style="width:100%" placeholder="选择当前工作空间的用户">
            <el-option v-for="u in eligibleUsers" :key="u.id" :label="`${u.displayName} (${u.username})`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源">
          <el-select v-model="batchGrant.sourceIds" multiple filterable style="width:100%" @change="syncBatchGrantSources">
            <el-option v-for="d in dataSources" :key="d.id" :label="`${d.displayName} (${d.dbType})`" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" title="每个数据源单独设置授权范围；提交前预览所有用户与数据源组合，任何冲突都会阻止整批创建。" />
      <div v-for="item in batchGrant.sources" :key="item.dataSourceId" class="batch-source">
        <strong>{{ sourceName(item.dataSourceId) }}</strong>
        <el-input v-model="item.grantedSourceName" placeholder="逻辑名" aria-label="逻辑名" />
        <GrantScopePicker
          class="batch-grant-scope"
          :data-source-id="item.dataSourceId"
          v-model:schemas="item.allowedSchemas"
          v-model:tables="item.allowedTables"
        />
        <el-input v-model="item.blockedTables" placeholder="黑名单表（可留空）" aria-label="黑名单表" />
        <el-select v-model="item.sqlCapability" aria-label="SQL 能力">
          <el-option label="只读" value="READ_ONLY" /><el-option label="可写" value="DML_ALLOWED" />
        </el-select>
        <el-input-number v-model="item.maxRowsPerQuery" :min="1" :max="100000" aria-label="查询行数上限" />
        <el-date-picker v-model="item.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="有效期（可选）" />
      </div>
      <template v-if="batchGrant.preview">
        <el-alert :type="batchGrant.preview.valid ? 'success' : 'error'" :closable="false"
          :title="`预览 ${batchGrant.preview.count} 条授权，${batchGrant.preview.errors.length} 项冲突`" />
        <el-table :data="batchGrant.preview.items" size="small" max-height="240">
          <el-table-column label="用户"><template #default="{ row }">{{ eligibleUsers.find(u => u.id === row.userId)?.username || row.userId }}</template></el-table-column>
          <el-table-column label="数据源"><template #default="{ row }">{{ sourceName(row.dataSourceId) }}</template></el-table-column>
          <el-table-column prop="grantedSourceName" label="逻辑名" />
          <el-table-column prop="allowedSchemas" label="Schema 范围" show-overflow-tooltip />
          <el-table-column prop="allowedTables" label="表范围" show-overflow-tooltip />
          <el-table-column prop="blockedTables" label="排除表" show-overflow-tooltip />
          <el-table-column label="能力"><template #default="{ row }">{{ row.sqlCapability === 'DML_ALLOWED' ? '可写' : '只读' }}</template></el-table-column>
          <el-table-column prop="maxRowsPerQuery" label="行数上限" width="95" />
          <el-table-column label="有效期"><template #default="{ row }">{{ row.expiresAt ? fmt(row.expiresAt) : '长期' }}</template></el-table-column>
          <el-table-column label="检查结果"><template #default="{ row }">{{ row.error || '可创建' }}</template></el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="batchGrant.visible = false">取消</el-button>
        <el-button :loading="batchGrant.busy" @click="previewBatchGrant">预览矩阵</el-button>
        <el-button type="primary" :loading="batchGrant.busy" :disabled="!batchGrant.preview?.valid" @click="submitBatchGrant">整批创建</el-button>
      </template>
    </el-dialog>

    <!-- 用户创建与编辑 -->
    <el-dialog v-model="userCreate.visible" title="新建用户" width="460" @closed="userCreate.form.password = ''">
      <el-form label-width="100px">
        <el-form-item label="用户名"><el-input v-model="userCreate.form.username" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="userCreate.form.password" type="password" show-password autocomplete="new-password" />
          <div class="user-password-hint">12–128 位，须包含大写字母、小写字母、数字和特殊字符，且不能包含用户名。</div>
        </el-form-item>
        <el-form-item label="显示名"><el-input v-model="userCreate.form.displayName" /></el-form-item>
        <el-form-item label="角色">
          <el-checkbox-group v-model="userCreate.form.roles">
            <el-checkbox value="PLATFORM_ADMIN">平台管理员</el-checkbox>
            <el-checkbox value="DS_ADMIN">数据源管理员</el-checkbox>
            <el-checkbox value="STEWARD">数据管家</el-checkbox>
            <el-checkbox value="ANALYST">分析师</el-checkbox>
            <el-checkbox value="AUDITOR">审计员</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="userCreate.visible=false">取消</el-button><el-button type="primary" :loading="userCreate.busy" @click="createUser">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="userEditor.visible" :title="`编辑用户 · ${editedUser?.displayName || editedUser?.username || ''}`"
      width="min(680px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!userEditorBusy"
      :show-close="!userEditorBusy" @closed="resetUserEditor">
      <template v-if="editedUser">
        <el-descriptions :column="2" border class="user-editor-summary">
          <el-descriptions-item label="用户名">{{ editedUser.username }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ editedUser.enabled ? '启用' : '已冻结' }}</el-descriptions-item>
        </el-descriptions>
        <el-tabs v-model="userEditor.section" class="user-editor-tabs">
          <el-tab-pane label="角色权限" name="roles">
            <el-alert v-if="!canEditUserRoles" type="info" :closable="false"
              title="不能修改自己的角色；只能为当前工作空间的成员配置角色。" />
            <el-alert v-else type="info" :closable="false"
              title="平台管理员在所有工作空间生效；其他角色只对当前企业空间生效。保存后该用户需要重新登录。" />
            <el-checkbox-group v-model="userRoles.roles" class="user-role-options" :disabled="!canEditUserRoles || userRoles.busy">
              <el-checkbox value="PLATFORM_ADMIN">平台管理员（全局）</el-checkbox>
              <el-checkbox value="DS_ADMIN">数据源管理员</el-checkbox>
              <el-checkbox value="STEWARD">数据管家</el-checkbox>
              <el-checkbox value="ANALYST">分析师</el-checkbox>
              <el-checkbox value="AUDITOR">审计员</el-checkbox>
            </el-checkbox-group>
            <div class="user-editor-actions"><el-button type="primary" :loading="userRoles.busy"
              :disabled="!canEditUserRoles" @click="saveUserRoles">保存角色</el-button></div>
          </el-tab-pane>
          <el-tab-pane label="账号与密码" name="credentials">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="当前密码">不可查看（系统仅保存 BCrypt 哈希）</el-descriptions-item>
            </el-descriptions>
            <el-alert class="user-credential-note" type="info" :closable="false"
              title="重置后，用户的现有登录会话立即失效。新密码只在本弹窗中显示，请及时交给用户。" />
            <el-form label-width="90px">
              <el-form-item label="新密码">
                <el-input v-model="userCredentials.newPassword" type="password" show-password
                  autocomplete="new-password" @input="userCredentials.resetDone = false" />
              </el-form-item>
            </el-form>
            <div class="bar">
              <el-button @click="generateUserPassword">生成强密码</el-button>
              <el-button :disabled="!userCredentials.resetDone" @click="copyUserPassword">复制已重置密码</el-button>
            </div>
            <div class="user-password-hint">12–128 位，须包含大写字母、小写字母、数字和特殊字符，且不能包含用户名。</div>
            <div class="user-editor-actions"><el-button type="primary" :loading="userCredentials.busy"
              @click="resetUserPassword">重置密码</el-button></div>
          </el-tab-pane>
          <el-tab-pane label="脚本移交" name="scripts">
            <el-alert type="info" :closable="false"
              title="仅移交当前工作空间的全部脚本。接收人须已启用且拥有同逻辑名、同数据源、同范围的有效授权；SQL 内容保持加密存储。" />
            <el-alert v-if="userTransfer.error" type="warning" :title="userTransfer.error" :closable="false" class="user-transfer-error" />
            <el-button v-if="userTransfer.error" text @click="loadUserScripts">重试加载</el-button>
            <el-table :data="userTransfer.scripts" size="small" max-height="240" v-loading="userTransfer.loading" empty-text="当前工作空间无脚本">
              <el-table-column prop="title" label="脚本" />
              <el-table-column prop="grantedSourceName" label="逻辑数据源" />
              <el-table-column label="更新时间" width="165"><template #default="{ row }">{{ fmt(row.updatedAt) }}</template></el-table-column>
            </el-table>
            <el-form label-width="90px" class="user-transfer-form">
              <el-form-item label="接收用户">
                <el-select v-model="userTransfer.targetUserId" filterable style="width:100%" placeholder="选择当前工作空间的用户">
                  <el-option v-for="u in transferTargets" :key="u.id"
                    :label="`${u.displayName || u.username} (${u.username})`" :value="u.id" />
                </el-select>
              </el-form-item>
            </el-form>
            <div class="user-editor-actions"><el-button type="primary" :loading="userTransfer.busy"
              :disabled="userTransfer.loading || !userTransfer.scripts.length || !userTransfer.targetUserId"
              @click="transferUserScripts">移交全部 {{ userTransfer.scripts.length }} 个脚本</el-button></div>
          </el-tab-pane>
          <el-tab-pane label="账号状态" name="status">
            <el-alert v-if="editedUser.username === auth.user?.username" type="info" :closable="false"
              title="不能冻结或删除当前登录账号。" />
            <el-alert v-else type="info" :closable="false"
              title="冻结后用户的现有会话立即失效；解冻后可以重新登录。" />
            <div class="user-editor-actions"><el-button :loading="userActionBusy === editedUser.id"
              :disabled="editedUser.username === auth.user?.username"
              @click="toggleUserFrozen(editedUser)">{{ editedUser.enabled ? '冻结账号' : '解冻账号' }}</el-button></div>
            <div class="user-editor-danger">
              <strong>删除用户</strong>
              <p>逻辑删除后禁止登录，历史记录保留，用户名不可复用。请先移交需要保留的脚本。</p>
              <el-button type="danger" :loading="userActionBusy === editedUser.id"
                :disabled="editedUser.username === auth.user?.username" @click="deleteUser(editedUser)">删除用户</el-button>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
      <template #footer><el-button :disabled="userEditorBusy" @click="userEditor.visible = false">关闭</el-button></template>
    </el-dialog>

    <!-- AI Provider 配置 -->
    <el-dialog v-model="aiCreate.visible" title="配置 AI Provider" width="520">
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-select v-model="aiCreate.form.providerKey" style="width:100%" @change="onAiPreset">
            <el-option v-for="(p, k) in aiPresets" :key="k" :label="p.displayName" :value="k as string" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名"><el-input v-model="aiCreate.form.displayName" /></el-form-item>
        <el-form-item label="部署模式">
          <el-radio-group v-model="aiCreate.form.deploymentMode">
            <el-radio value="PUBLIC">公网 Provider</el-radio>
            <el-radio value="PRIVATE">私有模型</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="aiCreate.form.deploymentMode === 'PRIVATE'"
          title="私有模型必须由管理员在服务端显式启用，并把目标主机加入精确白名单；通配符不会生效。"
          type="warning"
          :closable="false"
          show-icon
          class="provider-alert"
        />
        <el-alert
          v-if="aiCreate.form.providerKey === 'bailian_token'"
          title="请使用百炼 Token Plan 专属 API Key；Base URL 与模型已预填，可按套餐支持范围调整模型。普通百炼 API Key 请选“阿里云百炼”。"
          type="info"
          :closable="false"
          show-icon
          class="provider-alert"
        />
        <el-form-item label="Base URL"><el-input v-model="aiCreate.form.baseUrl" /></el-form-item>
        <el-form-item label="模型"><el-input v-model="aiCreate.form.model" /></el-form-item>
        <el-form-item :label="aiCreate.form.deploymentMode === 'PRIVATE' ? 'API KEY（可选）' : 'API KEY'">
          <el-input v-model="aiCreate.form.apiKey" type="password" show-password />
        </el-form-item>
        <el-form-item label="设为默认"><el-switch v-model="aiCreate.form.isDefault" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="aiCreate.visible=false">取消</el-button><el-button type="primary" :loading="aiCreate.busy" @click="createAi">保存</el-button></template>
    </el-dialog>

    <!-- AI 脱敏候选 -->
    <el-dialog v-model="maskAi.visible" title="AI 生成脱敏候选规则" width="560">
      <el-alert type="info" :closable="false" class="mask-ai-note"
        title="只向当前工作空间默认 AI 模型发送数据源名称与您的描述，不发送表数据或连接凭据。生成后需核对并手动保存；仅在 LyraDB 展示端脱敏，不修改源数据库。" />
      <el-form label-width="90px">
        <el-form-item label="数据源" required>
          <el-select v-model="maskAi.dataSourceId" filterable style="width:100%" placeholder="选择目标数据源">
            <el-option v-for="d in dataSources" :key="d.id" :label="`${d.displayName} (${d.dbType})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="脱敏要求" required>
          <el-input v-model="maskAi.instruction" type="textarea" :rows="4" maxlength="500" show-word-limit
            placeholder="例如：对该数据源所有表的手机号字段做展示端摘要脱敏" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="maskAi.visible = false">取消</el-button>
        <el-button type="primary" :loading="maskAi.busy" @click="generateMaskRule">生成候选</el-button>
      </template>
    </el-dialog>

    <!-- 脱敏规则创建 -->
    <el-dialog v-model="maskCreate.visible" title="新建脱敏规则" width="520">
      <el-alert v-if="maskCreate.aiExplanation" type="warning" :closable="false" class="mask-ai-note"
        :title="`AI 候选：${maskCreate.aiExplanation}。请核对实际列名与匹配范围，保存后仅在 LyraDB 展示端生效。`" />
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="maskCreate.form.dataSourceId" clearable placeholder="空 = 全局规则" style="width:100%">
            <el-option v-for="d in dataSources" :key="d.id" :label="`${d.displayName} (${d.dbType})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="表匹配"><el-input v-model="maskCreate.form.tablePattern" placeholder="user_*，空 = 不限表" /></el-form-item>
        <el-form-item label="列匹配"><el-input v-model="maskCreate.form.columnPattern" placeholder="phone,mobile,phone_*，逗号分隔；仅支持末尾 *" /></el-form-item>
        <el-form-item label="脱敏方式">
          <el-radio-group v-model="maskCreate.form.maskType">
            <el-radio value="FULL">全遮盖</el-radio><el-radio value="PARTIAL">保留首尾</el-radio><el-radio value="HASH">不可逆摘要</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="maskCreate.form.remark" placeholder="如：手机号展示端脱敏" /></el-form-item>
      </el-form>
      <div class="user-password-hint">规则仅处理 LyraDB 返回与导出的结果，不会更新源数据库；摘要无法反向解密。</div>
      <template #footer><el-button @click="maskCreate.visible=false">取消</el-button><el-button type="primary" :loading="maskCreate.busy" @click="createMask">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="connectionExport.visible" title="申请导出连接配置" width="600" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="已选连接">
          <div class="selected-list">{{ selectedDataSourceNames.join('、') }}</div>
        </el-form-item>
        <el-form-item label="凭据处理">
          <el-radio-group v-model="connectionExport.mode">
            <el-radio value="OMIT">不导出凭据</el-radio>
            <el-radio value="PASSWORD_ENCRYPTED">使用密码加密</el-radio>
            <el-radio value="PLAINTEXT">明文导出</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="connectionExport.mode === 'PASSWORD_ENCRYPTED'"
          title="审批通过并下载时再输入加密密码；密码不会写入审批单或保存到服务器。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="connectionExport.mode === 'PLAINTEXT'"
          title="高风险：导出文件会包含可直接使用的数据库凭据。审批人与下载人都会看到风险提示。"
          type="error"
          :closable="false"
          show-icon
        />
        <el-form-item v-if="connectionExport.mode === 'PLAINTEXT'" class="risk-confirm">
          <el-checkbox v-model="connectionExport.plaintextConfirmed">我了解并确认导出明文凭据</el-checkbox>
        </el-form-item>
        <el-form-item label="申请理由">
          <el-input v-model="connectionExport.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="connectionExport.visible = false">取消</el-button>
        <el-button type="primary" :loading="connectionExport.busy" @click="submitConnectionExport">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="connectionImport.visible" title="导入连接配置" width="860" destroy-on-close @closed="resetConnectionImport">
      <div class="import-toolbar">
        <span class="file-name">{{ connectionImport.file?.name || '尚未选择文件' }}</span>
        <el-input
          v-model="connectionImport.password"
          type="password"
          show-password
          clearable
          :disabled="isExcelImport"
          placeholder="加密 JSON 包密码（Excel 模板无需填写）"
          aria-label="连接包密码"
        />
        <el-button type="primary" :loading="connectionImport.previewing" :disabled="!connectionImport.file" @click="previewConnectionImport">解析并预览</el-button>
        <el-button v-if="connectionImport.previewing" @click="cancelImportPreview">取消解析</el-button>
      </div>
      <el-alert v-if="connectionImport.error" :title="connectionImport.error" type="error" :closable="false" show-icon />
      <template v-if="connectionImport.preview">
        <el-alert
          :title="`凭据模式：${credentialPolicyLabel(connectionImport.preview.credentialPolicy)}${connectionImport.preview.riskCode ? ` · 风险标识：${connectionImport.preview.riskCode}` : ''}`"
          type="info"
          :closable="false"
          show-icon
        />

        <el-table :data="connectionImport.preview.items" border size="small" max-height="420" empty-text="导入文件中没有可导入连接">
          <el-table-column prop="displayName" label="连接名" min-width="150" />
          <el-table-column prop="dbType" label="类型" width="100" />
          <el-table-column label="配置键" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.parameterKeys.join('、') || '—' }}</template>
          </el-table-column>
          <el-table-column label="凭据" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.credentialsIncluded ? (row.credentialKeys.join('、') || '已包含') : '未包含' }}</template>
          </el-table-column>
          <el-table-column label="冲突" min-width="150">
            <template #default="{ row }">
              <el-tag :type="row.conflict ? 'warning' : 'success'" size="small">{{ row.conflict ? (row.existingDisplayName ? `已存在：${row.existingDisplayName}` : '存在同名连接') : '无' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理方式" width="150">
            <template #default="{ row }">
              <el-select v-model="importChoices[row.entryKey].action" size="small" aria-label="冲突处理方式">
                <el-option label="跳过" value="SKIP" />
                <el-option label="重命名导入" value="RENAME" />
                <el-option :label="row.conflict ? '覆盖' : '直接导入'" value="OVERWRITE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="导入名称" min-width="170">
            <template #default="{ row }">
              <el-input
                v-if="importChoices[row.entryKey].action === 'RENAME'"
                v-model="importChoices[row.entryKey].renameTo"
                size="small"
                maxlength="120"
                aria-label="重命名后的连接名"
              />
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="connectionImport.visible = false">取消</el-button>
        <el-button type="primary" :loading="connectionImport.applying" :disabled="!connectionImport.preview" @click="applyConnectionImport">确认导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Upload } from '@element-plus/icons-vue'
import {
  entApi,
  type AdminDataSource,
  type AdminDataSourceSaveRequest,
  type AdminGrant,
  type AdminUser,
  type AdminUserScript,
  type BatchGrantRequest,
  type BatchGrantSource,
  type BatchGrantPreview,
  type DataSourceTestBatch,
  type ConnectionImportPreview,
  type CredentialExportMode,
  type ImportConflictAction,
  type MaskingRule,
} from '@/api/ent'
import {
  buildImportDecisions,
  CONNECTION_IMPORT_TEMPLATE_FILE_NAME,
  isExcelConnectionImportFile,
  isSupportedConnectionImportFile,
} from '@/utils/enterpriseTransfer'
import { saveBlob } from '@/utils/download'
import { driverApi } from '@/api/driver'
import {
  buildAdminDataSourceFields,
  buildAdminDataSourceFormParams,
  buildAdminDataSourceParamPayload,
  firstMissingRequiredField,
  isAdminCredentialField,
  isStoredAdminCredential,
} from '@/utils/adminDataSourceEditor'
import {
  ADMIN_DATA_SOURCE_TEST_PHASE_LABELS,
  runAdminDataSourceTest,
  type AdminDataSourceTestPhase,
} from '@/utils/adminDataSourceTest'
import type { DatabaseType, FormField } from '@/types/driver'
import type { AiProviderDeploymentMode, AiProviderView } from '@/types/ai'
import { useAuthStore } from '@/stores/auth'
import GrantScopePicker from '@/components/admin/GrantScopePicker.vue'

const auth = useAuthStore()
const tab = ref('ds')
const dataSources = ref<AdminDataSource[]>([])
const grants = ref<AdminGrant[]>([])
const grantDetail = ref<AdminGrant | null>(null)
const grantDetailVisible = ref(false)
const users = ref<AdminUser[]>([])
const eligibleUsers = ref<Array<{ id: string; username: string; displayName: string }>>([])
const dbTypes = ref<DatabaseType[]>([])
const aiProviders = ref<AiProviderView[]>([])
const aiTestingId = ref('')
const aiTestResult = ref<{ success: boolean; message: string } | null>(null)
const aiPresets = ref<Record<string, any>>({})
const maskRules = ref<MaskingRule[]>([])
const selectedDataSources = ref<AdminDataSource[]>([])
const importFileInput = ref<HTMLInputElement | null>(null)
const connectionExport = reactive({
  visible: false,
  busy: false,
  mode: 'OMIT' as CredentialExportMode,
  plaintextConfirmed: false,
  reason: '',
})
const connectionImport = reactive({
  visible: false,
  file: null as File | null,
  password: '',
  preview: null as ConnectionImportPreview | null,
  previewing: false,
  applying: false,
  error: '',
})
const isExcelImport = computed(() =>
  isExcelConnectionImportFile(connectionImport.file?.name),
)
const importChoices = reactive<Record<string, { action: ImportConflictAction; renameTo: string }>>({})
let importPreviewController: AbortController | null = null

type DataSourceTestFeedbackType = 'success' | 'info' | 'warning' | 'error'
const testingDataSourceId = ref('')
const batchTesting = ref(false)
const batchTest = ref<DataSourceTestBatch | null>(null)
let batchPollTimer: number | null = null
const dataSourceTestFeedback = reactive({
  type: 'info' as DataSourceTestFeedbackType, message: '',
})
const dsEditor = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  loading: false,
  busy: false,
  fields: [] as FormField[],
  originalParams: {} as Record<string, any>,
  dirtyParams: {} as Record<string, boolean>,
  credentialVisible: {} as Record<string, boolean>,
  credentialLoaded: {} as Record<string, boolean>,
  credentialLoadingField: '',
  form: { id: '', dbType: '', displayName: '', description: '', params: {} as Record<string, any> },
})
const credentialHideTimers = new Map<string, number>()
const grantCreate = reactive({ visible: false, busy: false, form: { dataSourceId: '', userId: '', grantedSourceName: '', allowedSchemas: '', allowedTables: '', blockedTables: '', sqlCapability: 'READ_ONLY' } })
const batchGrant = reactive({
  visible: false, busy: false,
  userIds: [] as string[], sourceIds: [] as string[],
  sources: [] as BatchGrantSource[],
  preview: null as BatchGrantPreview | null,
  previewPayload: '',
})
watch(() => [batchGrant.userIds, batchGrant.sources], () => {
  batchGrant.preview = null
  batchGrant.previewPayload = ''
}, { deep: true })
const userCreate = reactive({ visible: false, busy: false, form: { username: '', password: '', displayName: '', roles: ['ANALYST'] } })
const userEditor = reactive({ visible: false, userId: '', section: 'roles' as 'roles' | 'credentials' | 'scripts' | 'status' })
const editedUser = computed(() => users.value.find(user => user.id === userEditor.userId) || null)
const canEditUserRoles = computed(() => Boolean(editedUser.value
  && editedUser.value.username !== auth.user?.username
  && editedUser.value.workspaceIds.includes(auth.user?.currentWorkspaceId || '')))
const userRoles = reactive({ busy: false, username: '', roles: [] as string[] })
const userActionBusy = ref('')
const userCredentials = reactive({ username: '', newPassword: '', resetDone: false, busy: false })
const userTransfer = reactive({
  loading: false, loaded: false, busy: false, userId: '', username: '', error: '',
  targetUserId: '', scripts: [] as AdminUserScript[],
})
const userEditorBusy = computed(() => userRoles.busy || userCredentials.busy
  || userTransfer.busy || Boolean(userActionBusy.value))
const transferTargets = computed(() => users.value.filter(user => user.enabled
  && user.id !== userTransfer.userId
  && user.workspaceIds.includes(auth.user?.currentWorkspaceId || '')))
watch(() => userEditor.section, section => {
  if (userEditor.visible && section === 'scripts') void loadUserScripts()
})
const aiCreate = reactive({
  visible: false, busy: false,
  form: { providerKey: 'deepseek', displayName: '', baseUrl: '', model: '', apiKey: '', isDefault: true, deploymentMode: 'PUBLIC' as AiProviderDeploymentMode },
})
const maskCreate = reactive({ visible: false, busy: false, aiExplanation: '', form: { dataSourceId: '', tablePattern: '', columnPattern: '', maskType: 'PARTIAL', remark: '' } })
const maskAi = reactive({ visible: false, busy: false, dataSourceId: '', instruction: '' })

async function load() {
  try {
    if (tab.value === 'ds') dataSources.value = await entApi.adminDataSources()
    else if (tab.value === 'grants') {
      [grants.value, dataSources.value, eligibleUsers.value] = await Promise.all([
        entApi.adminGrants(''), entApi.adminDataSources(), entApi.adminEligibleGrantUsers(),
      ])
    }
    else if (tab.value === 'users') users.value = await entApi.adminUsers()
    else if (tab.value === 'ai') aiProviders.value = await entApi.adminAiProviders()
    else if (tab.value === 'masking') {
      maskRules.value = await entApi.adminMaskingRules()
      if (!dataSources.value.length) dataSources.value = await entApi.adminDataSources()
    }
  } catch (error: any) { ElMessage.error(error.message || '管理数据加载失败') }
}
onMounted(async () => {
  dbTypes.value = await driverApi.getSupportedTypes()
  aiPresets.value = await entApi.aiPresets()
  load()
})
onUnmounted(() => {
  if (batchPollTimer !== null) window.clearTimeout(batchPollTimer)
})

function sourceName(id: string) {
  return dataSources.value.find(source => source.id === id)?.displayName || id
}

function onGrantSourceChange() {
  grantCreate.form.allowedSchemas = ''
  grantCreate.form.allowedTables = ''
}

function testStatusLabel(status?: AdminDataSource['lastTestStatus']) {
  return ({ NOT_TESTED: '未检测', CONNECTED: '连通', FAILED: '连接失败',
    DRIVER_UNAVAILABLE: '驱动未就绪', STALE: '结果已过期' })[status || 'NOT_TESTED']
}

function testStatusType(status?: AdminDataSource['lastTestStatus']): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'CONNECTED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'DRIVER_UNAVAILABLE' || status === 'STALE') return 'warning'
  return 'info'
}

async function pollBatchTest(id: string) {
  try {
    const status = await entApi.adminDataSourceTestBatch(id)
    batchTest.value = status
    if (status.state === 'DONE') {
      batchTesting.value = false
      dataSources.value = await entApi.adminDataSources()
      return
    }
    batchPollTimer = window.setTimeout(() => { void pollBatchTest(id) }, 1000)
  } catch (error: any) {
    batchTesting.value = false
    ElMessage.error(error.message || '无法读取批量检测进度')
  }
}

async function startBatchTest() {
  const ids = selectedDataSources.value.map(source => source.id)
  if (!ids.length || ids.length > 100) {
    ElMessage.warning('请选择 1-100 个数据源')
    return
  }
  batchTesting.value = true
  try {
    batchTest.value = await entApi.adminStartDataSourceTestBatch(ids)
    await pollBatchTest(batchTest.value.id)
  } catch (error: any) {
    batchTesting.value = false
    ElMessage.error(error.message || '无法启动批量检测')
  }
}

function openBatchGrant() {
  batchGrant.userIds = []
  batchGrant.sourceIds = selectedDataSources.value.map(source => source.id)
  batchGrant.sources = []
  syncBatchGrantSources()
  batchGrant.visible = true
}

function syncBatchGrantSources() {
  batchGrant.sources = batchGrant.sourceIds.map(id => {
    const previous = batchGrant.sources.find(source => source.dataSourceId === id)
    if (previous) return previous
    return {
      dataSourceId: id, grantedSourceName: sourceName(id),
      allowedSchemas: '', allowedTables: '', blockedTables: '',
      sqlCapability: 'READ_ONLY' as const, maxRowsPerQuery: 10000,
    }
  })
}

function grantBatchRequest(): BatchGrantRequest {
  return { userIds: [...batchGrant.userIds],
    sources: batchGrant.sources.map(source => ({ ...source })) }
}

async function previewBatchGrant() {
  const request = grantBatchRequest()
  if (!request.userIds.length || !request.sources.length) {
    ElMessage.warning('请选择用户和数据源')
    return
  }
  batchGrant.busy = true
  try {
    batchGrant.preview = await entApi.adminPreviewGrantBatch(request)
    batchGrant.previewPayload = JSON.stringify(request)
  } catch (error: any) {
    ElMessage.error(error.message || '批量授权预览失败')
  } finally { batchGrant.busy = false }
}

async function submitBatchGrant() {
  if (!batchGrant.preview?.valid || !batchGrant.previewPayload) return
  batchGrant.busy = true
  try {
    const result = await entApi.adminCreateGrantBatch(
      JSON.parse(batchGrant.previewPayload) as BatchGrantRequest,
    )
    ElMessage.success(`已创建 ${result.count} 条授权`)
    batchGrant.visible = false
    await load()
  } catch (error: any) {
    batchGrant.preview = null
    ElMessage.error(error.message || '批量授权失败，未创建任何授权')
  } finally { batchGrant.busy = false }
}

function resetDataSourceEditor() {
  for (const timer of credentialHideTimers.values()) window.clearTimeout(timer)
  credentialHideTimers.clear()
  dsEditor.visible = false
  dsEditor.mode = 'create'
  dsEditor.loading = false
  dsEditor.busy = false
  dsEditor.fields = []
  dsEditor.originalParams = {}
  dsEditor.dirtyParams = {}
  dsEditor.credentialVisible = {}
  dsEditor.credentialLoaded = {}
  dsEditor.credentialLoadingField = ''
  dsEditor.form = { id: '', dbType: '', displayName: '', description: '', params: {} }
}

function openCreateDataSource() {
  resetDataSourceEditor()
  dsEditor.visible = true
}

async function openEditDataSource(row: AdminDataSource) {
  resetDataSourceEditor()
  dsEditor.mode = 'edit'
  dsEditor.visible = true
  dsEditor.loading = true
  try {
    const source = await entApi.adminDataSource(row.id)
    const driver = await driverApi.getDriver(source.dbType)
    dsEditor.fields = buildAdminDataSourceFields(
      driver.connectionFormFields || [], source.params || {},
    )
    dsEditor.originalParams = { ...(source.params || {}) }
    dsEditor.form = {
      id: source.id,
      dbType: source.dbType,
      displayName: source.displayName,
      description: source.description || '',
      params: buildAdminDataSourceFormParams(
        dsEditor.fields, dsEditor.originalParams,
      ),
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载数据源失败')
    dsEditor.visible = false
  } finally {
    dsEditor.loading = false
  }
}

async function onDbTypeChange() {
  if (!dsEditor.form.dbType || dsEditor.mode === 'edit') return
  dsEditor.loading = true
  try {
    const driver = await driverApi.getDriver(dsEditor.form.dbType)
    dsEditor.fields = buildAdminDataSourceFields(driver.connectionFormFields || [])
    dsEditor.form.params = buildAdminDataSourceFormParams(dsEditor.fields)
    dsEditor.dirtyParams = {}
    const type = dbTypes.value.find(item => item.dbType === dsEditor.form.dbType)
    if (!dsEditor.form.displayName) {
      dsEditor.form.displayName = type?.displayName || driver.displayName || ''
    }
  } catch (e: any) {
    dsEditor.fields = []
    dsEditor.form.params = {}
    ElMessage.error(e.message || '加载数据库连接字段失败')
  } finally {
    dsEditor.loading = false
  }
}

function markDataSourceParamDirty(name: string) {
  dsEditor.dirtyParams[name] = true
}

function isCredentialEditorField(field: FormField) {
  return isAdminCredentialField(field, dsEditor.originalParams)
}

function isStoredCredential(field: FormField) {
  return dsEditor.mode === 'edit'
    && isStoredAdminCredential(field, dsEditor.originalParams)
}

function credentialPlaceholder(field: FormField) {
  if (isStoredCredential(field) && !dsEditor.dirtyParams[field.name]) {
    return '已保存；输入新值可替换，查看或复制会记录审计'
  }
  return '请输入凭据'
}

function credentialToggleLabel(field: FormField) {
  if (dsEditor.credentialVisible[field.name]) return '隐藏'
  if (isStoredCredential(field) && !dsEditor.credentialLoaded[field.name]
    && !dsEditor.dirtyParams[field.name]) return '查看'
  return '显示'
}

async function loadCredentialValue(field: FormField): Promise<string> {
  const current = String(dsEditor.form.params[field.name] ?? '')
  if (dsEditor.mode !== 'edit' || !isStoredCredential(field)
    || dsEditor.dirtyParams[field.name]
    || dsEditor.credentialLoaded[field.name]) {
    return current
  }
  dsEditor.credentialLoadingField = field.name
  try {
    const result = await entApi.adminRevealDataSourceCredential(
      dsEditor.form.id, field.name,
    )
    if (result.field !== field.name) throw new Error('服务端返回的凭据字段不匹配')
    dsEditor.form.params[field.name] = result.value
    dsEditor.credentialLoaded[field.name] = true
    return result.value
  } finally {
    dsEditor.credentialLoadingField = ''
  }
}

function scheduleCredentialHide(fieldName: string) {
  const existing = credentialHideTimers.get(fieldName)
  if (existing) window.clearTimeout(existing)
  credentialHideTimers.set(fieldName, window.setTimeout(() => {
    dsEditor.credentialVisible[fieldName] = false
    credentialHideTimers.delete(fieldName)
  }, 30_000))
}

async function toggleDataSourceCredential(field: FormField) {
  if (dsEditor.credentialVisible[field.name]) {
    dsEditor.credentialVisible[field.name] = false
    return
  }
  try {
    await loadCredentialValue(field)
    dsEditor.credentialVisible[field.name] = true
    scheduleCredentialHide(field.name)
  } catch (e: any) {
    ElMessage.error(e.message || '查看凭据失败')
  }
}

async function writeClipboard(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('浏览器拒绝访问剪贴板')
}

async function copyDataSourceCredential(field: FormField) {
  try {
    const value = await loadCredentialValue(field)
    if (!value) {
      ElMessage.warning('当前凭据为空')
      return
    }
    await writeClipboard(value)
    ElMessage.success(`${field.label} 已复制，请注意保管`)
  } catch (e: any) {
    ElMessage.error(e.message || '复制凭据失败')
  }
}

async function saveDataSource() {
  if (!dsEditor.form.dbType) { ElMessage.warning('请选择数据库类型'); return }
  if (!dsEditor.form.displayName.trim()) { ElMessage.warning('显示名不能为空'); return }
  const missing = firstMissingRequiredField(
    dsEditor.fields,
    dsEditor.form.params,
    dsEditor.originalParams,
    dsEditor.dirtyParams,
    dsEditor.mode === 'edit',
  )
  if (missing) { ElMessage.warning(`${missing.label}不能为空`); return }

  const params = buildAdminDataSourceParamPayload(
    dsEditor.fields,
    dsEditor.form.params,
    dsEditor.dirtyParams,
    dsEditor.mode === 'edit',
  )
  const body: AdminDataSourceSaveRequest = {
    displayName: dsEditor.form.displayName.trim(),
    description: dsEditor.form.description.trim(),
  }
  dsEditor.busy = true
  try {
    if (dsEditor.mode === 'edit') {
      if (Object.keys(params).length) body.params = params
      await entApi.adminUpdateDataSource(dsEditor.form.id, body)
      ElMessage.success('数据源已更新')
    } else {
      body.dbType = dsEditor.form.dbType
      body.params = params
      await entApi.adminCreateDataSource(body)
      ElMessage.success('数据源已注册')
    }
    dsEditor.visible = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    dsEditor.busy = false
  }
}
async function testDs(source: AdminDataSource) {
  if (testingDataSourceId.value) return
  testingDataSourceId.value = source.id

  const updatePhase = (phase: AdminDataSourceTestPhase) => {
    dataSourceTestFeedback.type = phase === 'DOWNLOADING_DRIVER'
      ? 'warning' : 'info'
    dataSourceTestFeedback.message =
      `${source.displayName}：${ADMIN_DATA_SOURCE_TEST_PHASE_LABELS[phase]}`
  }

  try {
    const result = await runAdminDataSourceTest(
      source,
      {
        getDriverStatus: dbType => driverApi.getDriverStatus(dbType),
        downloadDriver: dbType => driverApi.downloadDriver(dbType),
        testDataSource: id => entApi.adminTestDataSource(id),
      },
      updatePhase,
    )
    const message = `${source.displayName}：${result.message || (result.success ? '连接成功' : '连接失败')}（${result.elapsedMs} ms）`
    dataSourceTestFeedback.type = result.success ? 'success' : 'error'
    dataSourceTestFeedback.message = message
    if (result.success) {
      ElMessage.success(message)
    } else {
      ElMessage.error(message)
    }
  } catch (error: unknown) {
    const reason = error instanceof Error && error.message
      ? error.message : '连接测试失败'
    const message = `${source.displayName}：${reason}`
    dataSourceTestFeedback.type = 'error'
    dataSourceTestFeedback.message = message
    ElMessage.error(message)
  } finally {
    testingDataSourceId.value = ''
    dataSources.value = await entApi.adminDataSources().catch(() => dataSources.value)
  }
}
function clearDataSourceTestFeedback() {
  if (!testingDataSourceId.value) {
    dataSourceTestFeedback.message = ''
  }
}
async function delDs(id: string) {
  try { await ElMessageBox.confirm('删除该数据源？', '确认', { type: 'warning' }) }
  catch { return }
  await entApi.adminDeleteDataSource(id); ElMessage.success('已删除'); load()
}

async function createGrant() {
  if (!grantCreate.form.dataSourceId || !grantCreate.form.userId || !grantCreate.form.grantedSourceName) { ElMessage.warning('请补全'); return }
  if (!grantCreate.form.allowedSchemas.trim()) { ElMessage.warning('必须填写至少一个允许的 Schema'); return }
  if (!grantCreate.form.allowedTables.trim()) { ElMessage.warning('必须填写至少一个 schema.table 或 catalog.schema.table（可在表名段使用受控通配）；空值表示不授权任何表'); return }
  grantCreate.busy = true
  try {
    await entApi.adminCreateGrant(grantCreate.form)
    ElMessage.success('已分配'); grantCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '失败') }
  finally { grantCreate.busy = false }
}
async function delGrant(id: string) {
  await entApi.adminDeleteGrant(id); ElMessage.success('已删除'); load()
}

function grantUserName(grant: AdminGrant): string {
  const userId = grant.userId
  if (!userId) return '未指定用户'
  if (grant.granteeUsername) {
    return `${grant.granteeDisplayName || grant.granteeUsername}（${grant.granteeUsername}）`
  }
  const user = eligibleUsers.value.find(item => item.id === userId)
    || users.value.find(item => item.id === userId)
  return user ? `${user.displayName || user.username}（${user.username}）` : `用户 ID：${userId}`
}

function grantScopeSummary(grant: AdminGrant): string {
  const schemas = grantRules(grant.allowedSchemas)
  const tables = grantRules(grant.allowedTables)
  const blocked = grantRules(grant.blockedTables)
  const schemaLabel = schemas.length === 1 ? `Schema ${schemas[0]}` : `${schemas.length} 个 Schema`
  const tableLabel = tables.includes('*.*') ? '范围内全部表' : `${tables.length} 项表规则`
  return `${schemaLabel} · ${tableLabel}${blocked.length ? ` · 排除 ${blocked.length} 项` : ''}`
}

function grantRules(value?: string): string[] {
  return (value || '').split(',').map(rule => rule.trim()).filter(Boolean)
}

function grantExpired(grant: AdminGrant): boolean {
  return Boolean(grant.expiresAt && new Date(grant.expiresAt).getTime() <= Date.now())
}

function openGrantDetail(grant: AdminGrant) {
  grantDetail.value = grant
  grantDetailVisible.value = true
}

async function createUser() {
  if (!userCreate.form.username || !userCreate.form.password) { ElMessage.warning('用户名/密码必填'); return }
  userCreate.busy = true
  try {
    await entApi.adminCreateUser(userCreate.form)
    ElMessage.success('已创建'); userCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '失败') }
  finally { userCreate.busy = false }
}

function openUserEditor(user: AdminUser) {
  userEditor.userId = user.id
  userEditor.section = user.username !== auth.user?.username
    && user.workspaceIds.includes(auth.user?.currentWorkspaceId || '') ? 'roles' : 'credentials'
  userRoles.username = user.username
  userRoles.roles = [...user.roles]
  userCredentials.username = user.username
  userCredentials.newPassword = ''
  userCredentials.resetDone = false
  userTransfer.userId = user.id
  userTransfer.username = user.username
  userTransfer.targetUserId = ''
  userTransfer.scripts = []
  userTransfer.loaded = false
  userTransfer.error = ''
  userEditor.visible = true
}

function resetUserEditor() {
  userEditor.userId = ''
  userEditor.section = 'roles'
  userRoles.username = ''
  userRoles.roles = []
  resetUserCredentials()
  userTransfer.userId = ''
  userTransfer.username = ''
  userTransfer.targetUserId = ''
  userTransfer.scripts = []
  userTransfer.loading = false
  userTransfer.loaded = false
  userTransfer.error = ''
}

async function saveUserRoles() {
  if (!canEditUserRoles.value) return
  if (!userRoles.roles.some(role => role !== 'PLATFORM_ADMIN')) {
    ElMessage.warning('请至少选择一个当前工作空间角色')
    return
  }
  userRoles.busy = true
  try {
    await entApi.adminUpdateUserRoles(userRoles.username, userRoles.roles)
    const refreshed = await entApi.adminUsers()
    const updated = refreshed.find(user => user.username === userRoles.username)
    if (!updated || userRoles.roles.some(role => !updated.roles.includes(role))
        || updated.roles.includes('PLATFORM_ADMIN') !== userRoles.roles.includes('PLATFORM_ADMIN')) {
      throw new Error('角色已提交，但列表回读不一致，请刷新后核对')
    }
    users.value = refreshed
    ElMessage.success('角色已更新，该用户的旧会话已失效')
  } catch (error: any) {
    ElMessage.error(error.message || '修改角色失败')
  } finally { userRoles.busy = false }
}

function roleLabel(role: string) {
  return ({ PLATFORM_ADMIN: '平台管理员', DS_ADMIN: '数据源管理员',
    STEWARD: '数据管家', ANALYST: '分析师', AUDITOR: '审计员' } as Record<string, string>)[role] || role
}

function resetUserCredentials() {
  userCredentials.username = ''
  userCredentials.newPassword = ''
  userCredentials.resetDone = false
}

function generateUserPassword() {
  const groups = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '!@#$%&*?']
  const random = new Uint32Array(24)
  window.crypto.getRandomValues(random)
  const chars = groups.map((group, index) => group[random[index] % group.length])
  const all = groups.join('')
  for (let index = 4; index < 20; index++) chars.push(all[random[index] % all.length])
  for (let index = chars.length - 1; index > 0; index--) {
    const other = random[index + 4] % (index + 1)
    ;[chars[index], chars[other]] = [chars[other], chars[index]]
  }
  userCredentials.newPassword = chars.join('')
  userCredentials.resetDone = false
}

async function resetUserPassword() {
  if (!userCredentials.newPassword) { ElMessage.warning('请填写或生成新密码'); return }
  userCredentials.busy = true
  try {
    await entApi.adminResetUserPassword(userCredentials.username, userCredentials.newPassword)
    userCredentials.resetDone = true
    ElMessage.success('密码已重置，原有会话已失效')
  } catch (error: any) { ElMessage.error(error.message || '重置密码失败') }
  finally { userCredentials.busy = false }
}

async function copyUserPassword() {
  try {
    await navigator.clipboard.writeText(userCredentials.newPassword)
    ElMessage.success('新密码已复制')
  } catch { ElMessage.error('复制失败，请手动复制') }
}

async function toggleUserFrozen(user: AdminUser) {
  const freeze = user.enabled
  if (freeze) {
    try { await ElMessageBox.confirm(`冻结 ${user.username} 后其现有会话将失效。`, '确认冻结', { type: 'warning' }) }
    catch { return }
  }
  userActionBusy.value = user.id
  try {
    await entApi.adminFreezeUser(user.id, freeze)
    ElMessage.success(freeze ? '用户已冻结' : '用户已解冻')
    await load()
  } catch (error: any) { ElMessage.error(error.message || '更新用户状态失败') }
  finally { userActionBusy.value = '' }
}

async function deleteUser(user: AdminUser) {
  try {
    await ElMessageBox.confirm(
      `逻辑删除 ${user.username} 后立即禁止登录并从用户列表隐藏，历史记录保留、用户名不可复用；如有脚本须先移交。`,
      '确认删除用户', { type: 'warning', confirmButtonText: '确认删除' },
    )
  } catch { return }
  userActionBusy.value = user.id
  try {
    await entApi.adminDeleteUser(user.id)
    ElMessage.success('用户已逻辑删除')
    await load()
    userEditor.visible = false
  } catch (error: any) { ElMessage.error(error.message || '删除用户失败') }
  finally { userActionBusy.value = '' }
}

async function loadUserScripts() {
  if (!userTransfer.userId || userTransfer.loading || userTransfer.loaded) return
  const userId = userTransfer.userId
  userTransfer.loading = true
  userTransfer.error = ''
  try {
    const scripts = await entApi.adminUserScripts(userId)
    if (userTransfer.userId === userId) {
      userTransfer.scripts = scripts
      userTransfer.loaded = true
    }
  } catch (error: any) {
    if (userTransfer.userId === userId) userTransfer.error = error.message || '读取脚本清单失败'
  } finally {
    if (userTransfer.userId === userId) userTransfer.loading = false
  }
}

async function transferUserScripts() {
  if (!userTransfer.targetUserId) return
  userTransfer.busy = true
  try {
    const result = await entApi.adminTransferUserScripts(userTransfer.userId, userTransfer.targetUserId)
    ElMessage.success(`已移交 ${result.count} 个脚本`)
    userTransfer.targetUserId = ''
    userTransfer.loaded = false
    await loadUserScripts()
  } catch (error: any) { ElMessage.error(error.message || '脚本移交失败') }
  finally { userTransfer.busy = false }
}

function onAiPreset() {
  const p = aiPresets.value[aiCreate.form.providerKey]
  if (p) {
    aiCreate.form.displayName = p.displayName
    aiCreate.form.baseUrl = p.baseUrl
    aiCreate.form.model = p.model
  }
}
async function createAi() {
  if (!aiCreate.form.baseUrl) { ElMessage.warning('Base URL 必填'); return }
  if (aiCreate.form.deploymentMode === 'PUBLIC' && !aiCreate.form.apiKey) {
    ElMessage.warning('公网 Provider 的 API KEY 必填'); return
  }
  aiCreate.busy = true
  try {
    await entApi.adminCreateAiProvider(aiCreate.form)
    ElMessage.success('已保存'); aiCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '失败') }
  finally { aiCreate.busy = false }
}
async function setDefaultAi(id: string) {
  await entApi.adminSetDefaultAiProvider(id); ElMessage.success('已设默认'); load()
}
async function testAiProvider(provider: AiProviderView) {
  if (!provider.id || aiTestingId.value) return
  aiTestingId.value = provider.id
  aiTestResult.value = null
  try {
    const outcome = await entApi.adminTestAiProvider(provider.id)
    aiTestResult.value = {
      success: outcome.success,
      message: `${provider.displayName}：${outcome.message}${outcome.success && outcome.elapsedMs !== undefined ? `（${outcome.elapsedMs} ms）` : ''}`,
    }
  } catch (error: any) {
    aiTestResult.value = { success: false, message: `${provider.displayName}：${error.message || '测试请求失败'}` }
  } finally {
    aiTestingId.value = ''
  }
}
async function delAi(id: string) {
  try { await ElMessageBox.confirm('删除该 Provider？', '确认', { type: 'warning' }) } catch { return }
  await entApi.adminDeleteAiProvider(id); ElMessage.success('已删除'); load()
}

function dsName(id?: string) {
  if (!id) return '全局'
  return dataSources.value.find(d => d.id === id)?.displayName || id
}
function maskTypeLabel(t: string) {
  return t === 'FULL' ? '全遮盖' : t === 'HASH' ? '不可逆摘要' : '保留首尾'
}
function openManualMaskCreate() {
  maskCreate.aiExplanation = ''
  maskCreate.form = { dataSourceId: '', tablePattern: '', columnPattern: '', maskType: 'PARTIAL', remark: '' }
  maskCreate.visible = true
}
async function generateMaskRule() {
  if (!maskAi.dataSourceId || !maskAi.instruction.trim()) {
    ElMessage.warning('请选择数据源并描述脱敏要求')
    return
  }
  maskAi.busy = true
  try {
    const draft = await entApi.adminGenerateMaskingRule(maskAi.dataSourceId, maskAi.instruction.trim())
    maskCreate.form = {
      dataSourceId: draft.dataSourceId,
      tablePattern: draft.tablePattern,
      columnPattern: draft.columnPattern,
      maskType: draft.maskType,
      remark: draft.remark,
    }
    maskCreate.aiExplanation = draft.explanation || '请核对字段名及作用范围'
    maskAi.visible = false
    maskCreate.visible = true
  } catch (error: any) { ElMessage.error(error.message || 'AI 生成规则失败') }
  finally { maskAi.busy = false }
}
async function createMask() {
  if (!maskCreate.form.columnPattern.trim()) { ElMessage.warning('列匹配必填'); return }
  maskCreate.busy = true
  try {
    await entApi.adminSaveMaskingRule({ ...maskCreate.form, dataSourceId: maskCreate.form.dataSourceId || undefined, enabled: true })
    ElMessage.success('已保存'); maskCreate.visible = false; load()
  } catch (e: any) { ElMessage.error(e.message || '保存失败') }
  finally { maskCreate.busy = false }
}
async function toggleMask(row: MaskingRule) {
  try {
    await entApi.adminSaveMaskingRule({ ...row, enabled: !row.enabled })
    load()
  } catch (e: any) { ElMessage.error(e.message || '操作失败') }
}
async function delMask(id: string) {
  try { await ElMessageBox.confirm('删除该脱敏规则？', '确认', { type: 'warning' }) } catch { return }
  await entApi.adminDeleteMaskingRule(id); ElMessage.success('已删除'); load()
}

function credentialPolicyLabel(policy: CredentialExportMode): string {
  if (policy === 'PLAINTEXT') return '明文凭据'
  if (policy === 'PASSWORD_ENCRYPTED') return '密码加密凭据'
  return '不含凭据'
}
function onDataSourceSelection(rows: AdminDataSource[]) {
  selectedDataSources.value = rows
}

const selectedDataSourceNames = computed(() => selectedDataSources.value.map(row => row.displayName))

function openExport() {
  connectionExport.mode = 'OMIT'
  connectionExport.plaintextConfirmed = false
  connectionExport.reason = ''
  connectionExport.visible = true
}

async function submitConnectionExport() {
  if (!selectedDataSources.value.length) {
    ElMessage.warning('请先选择需要导出的连接')
    return
  }
  if (connectionExport.mode === 'PLAINTEXT' && !connectionExport.plaintextConfirmed) {
    ElMessage.warning('请确认已了解明文凭据风险')
    return
  }
  if (connectionExport.mode === 'PLAINTEXT') {
    try {
      await ElMessageBox.confirm(
        '明文导出会把数据库凭据直接写入文件。提交后仍需审批，审批通过下载时还会再次确认。是否提交？',
        '再次确认明文导出风险',
        { type: 'error', confirmButtonText: '确认提交', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  connectionExport.busy = true
  try {
    await entApi.adminRequestDataSourceExport({
      dataSourceIds: selectedDataSources.value.map(row => row.id),
      credentialMode: connectionExport.mode,
      plaintextRiskConfirmed: connectionExport.mode === 'PLAINTEXT' && connectionExport.plaintextConfirmed,
      reason: connectionExport.reason.trim() || undefined,
    })
    ElMessage.success('连接导出申请已提交，请前往审批中心查看进度')
    connectionExport.visible = false
  } catch (e: any) {
    ElMessage.error(e.message || '提交连接导出申请失败')
  } finally {
    connectionExport.busy = false
  }
}

async function downloadImportTemplate() {
  try {
    const blob = await entApi.adminDownloadDataSourceImportTemplate()
    await saveBlob(blob, CONNECTION_IMPORT_TEMPLATE_FILE_NAME)
    ElMessage.success('Excel 连接导入模板已下载')
  } catch (e: any) {
    ElMessage.error(e.message || '模板下载失败')
  }
}

function openImportFile() {
  importFileInput.value?.click()
}

function onImportFile(event: Event) {
  const inputElement = event.target as HTMLInputElement
  const file = inputElement.files?.[0]
  inputElement.value = ''
  if (!file) return
  if (!isSupportedConnectionImportFile(file.name)) {
    ElMessage.error('请选择 .xlsx 或 LyraDB JSON 连接文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('连接导入文件不得超过 10 MiB')
    return
  }
  resetConnectionImport()
  connectionImport.file = file
  connectionImport.visible = true
}

async function previewConnectionImport() {
  if (!connectionImport.file) return
  importPreviewController?.abort()
  const controller = new AbortController()
  importPreviewController = controller
  const file = connectionImport.file
  const password = connectionImport.password || undefined
  connectionImport.previewing = true
  connectionImport.error = ''
  try {
    const preview = await entApi.adminPreviewDataSourceImport(file, password, controller.signal)
    if (importPreviewController !== controller) return
    connectionImport.password = ''
    connectionImport.preview = preview
    for (const key of Object.keys(importChoices)) delete importChoices[key]
    for (const item of preview.items) {
      importChoices[item.entryKey] = {
        action: item.conflict ? 'SKIP' : 'OVERWRITE',
        renameTo: item.displayName,
      }
    }
  } catch (e: any) {
    if (importPreviewController === controller) {
      connectionImport.error = controller.signal.aborted ? '已取消解析' : (e.message || '连接导入文件解析失败')
    }
  } finally {
    if (importPreviewController === controller) {
      connectionImport.previewing = false
      importPreviewController = null
    }
  }
}

function cancelImportPreview() {
  importPreviewController?.abort()
}

async function applyConnectionImport() {
  if (!connectionImport.preview) return
  let decisions
  try {
    decisions = buildImportDecisions(connectionImport.preview.items, importChoices)
  } catch {
    ElMessage.warning('重命名导入时必须填写新连接名')
    return
  }
  connectionImport.applying = true
  try {
    const result = await entApi.adminApplyDataSourceImport(connectionImport.preview.previewToken, decisions)
    ElMessage.success(`导入完成：新增 ${result.created}，覆盖 ${result.overwritten}，跳过 ${result.skipped}`)
    connectionImport.visible = false
    await load()
  } catch (e: any) {
    connectionImport.error = e.message || '连接导入失败'
  } finally {
    connectionImport.applying = false
  }
}

function resetConnectionImport() {
  importPreviewController?.abort()
  importPreviewController = null
  connectionImport.file = null
  connectionImport.password = ''
  connectionImport.preview = null
  connectionImport.previewing = false
  connectionImport.applying = false
  connectionImport.error = ''
  for (const key of Object.keys(importChoices)) delete importChoices[key]
}
function summaryParams(p: any) {
  if (!p) return ''
  return Object.entries(p).map(([k, v]) => `${k}=${v}`).join(', ')
}
function fmt(d?: string) { return d ? new Date(d).toLocaleString() : '' }
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; }
.page-title { margin-bottom: 12px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted); }
.bar { margin-bottom: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.bar :deep(.el-button + .el-button) { margin-left: 0; }
.selection-count, .file-name { color: var(--color-text-muted); font-size: 12px; }
.grant-row-limit { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 11px; white-space: nowrap; }
.grant-rule-list { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; max-height: 180px; overflow: auto; }
.grant-rule-list :deep(.el-tag) { max-width: 100%; font-family: var(--font-mono); height: auto; min-height: 22px; white-space: normal; overflow-wrap: anywhere; }
.grant-detail-hint { margin-top: 16px; }
.role-tag { margin: 2px 5px 2px 0; }
.user-editor-summary { margin-bottom: 12px; }
.user-editor-tabs { min-height: 250px; }
.user-editor-actions { display: flex; justify-content: flex-end; margin-top: 16px; }
.user-editor-danger { margin-top: 24px; padding: 16px; border: 1px solid var(--color-destructive); border-radius: 10px; }
.user-editor-danger p { margin: 8px 0 14px; color: var(--color-text-muted); font-size: 12px; line-height: 1.6; }
.user-transfer-error { margin-top: 12px; }
.user-role-options { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 18px; }
.user-role-options :deep(.el-checkbox) { margin-right: 0; }
.ai-test-note { margin: -2px 0 12px; color: var(--color-text-muted); font-size: 12px; }
.ai-test-result { margin-top: 12px; }
.selected-list { max-height: 96px; overflow: auto; }
.data-source-test-feedback { margin-bottom: 10px; }
.test-time { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 10px; }
.batch-source { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 12px 0; padding: 12px; border: 1px solid var(--color-panel-border); border-radius: 8px; }
.batch-source strong { grid-column: 1 / -1; }
.batch-grant-scope { grid-column: 1 / -1; }
.credential-note { margin-bottom: 14px; }
.credential-editor { display: flex; align-items: center; gap: 8px; width: 100%; }
.credential-editor :deep(.el-input) { flex: 1; }
.user-password-hint { width: 100%; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.user-credential-note { margin: 12px 0; }
.user-transfer-form { margin-top: 14px; }
.mask-ai-note { margin-bottom: 14px; }
.import-toolbar { display: grid; grid-template-columns: minmax(140px, 1fr) minmax(220px, 1.5fr) auto auto; gap: 8px; align-items: center; margin-bottom: 12px; }
.risk-confirm { margin-top: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 768px) {
  .import-toolbar { grid-template-columns: 1fr; }
  .batch-source { grid-template-columns: 1fr; }
  .user-role-options { grid-template-columns: 1fr; }
}
</style>
