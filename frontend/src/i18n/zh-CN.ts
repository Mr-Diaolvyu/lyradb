/**
 * 简体中文语言包（默认语言）
 */
export default {
    common: {
        loading: '加载中...',
        confirm: '确定',
        cancel: '取消',
        save: '保存',
        delete: '删除',
        edit: '编辑',
        export: '导出',
        import: '导入',
        search: '搜索...',
        saved: '已保存',
        deleted: '已删除',
        failed: '操作失败',
        unknownError: '未知错误'
    },
    header: {
        newConnection: '新建连接',
        newQuery: '新建查询',
        selectDatabase: '选择数据库',
        execute: '执行 (Ctrl+Enter)',
        explain: '计划',
        runBackground: '后台执行',
        erDiagram: 'ER 图',
        migration: '迁移',
        language: '语言'
    },
    sideNav: {
        exportConnections: '导出连接',
        importConnections: '导入连接',
        newConnection: '新建连接',
        noExportable: '没有可导出的连接',
        exported: '已导出 {count} 个连接配置',
        exportFailed: '导出失败: {msg}'
    },
    statusBar: {
        notConnected: '未连接',
        rows: '{count} 行',
        truncated: '结果已截断',
        driverNotLoaded: '驱动未加载',
        driverReady: '{name} 驱动就绪',
        tabs: '{count} 个标签页'
    },
    tasks: {
        title: '后台任务',
        empty: '暂无后台任务',
        viewResult: '查看结果',
        cancel: '取消',
        remove: '移除',
        rows: '{rows} 行',
        status: {
            RUNNING: '执行中',
            DONE: '已完成',
            ERROR: '失败',
            CANCELLED: '已取消'
        }
    },
    reports: {
        title: '报表订阅',
        create: '新建订阅',
        editTitle: '编辑订阅',
        empty: '暂无数据',
        name: '订阅名称',
        connection: '连接',
        schedule: '执行周期',
        lastRun: '最近执行',
        enabled: '启用',
        actions: '操作',
        trigger: '立即执行',
        runs: '执行记录',
        runsTitle: '执行记录 - {name}',
        sqlHint: '仅支持 SELECT / WITH 查询',
        defaultDb: '默认数据库',
        typeHourly: '每小时',
        typeDaily: '每天',
        typeWeekly: '每周',
        weekday: '星期',
        runHour: '执行小时',
        runMinute: '执行分钟',
        formIncomplete: '请补全名称、连接、SQL 与 Webhook 地址',
        deleteConfirm: '删除该订阅及其执行记录？',
        triggerDone: '执行成功，{rows} 行已推送',
        runAt: '执行时间',
        result: '结果',
        success: '成功',
        failed: '失败',
        rowCount: '行数',
        elapsed: '耗时',
        pushStatus: '推送状态',
        error: '错误信息',
        labelHourly: '每小时 :{mm}',
        labelDaily: '每天 {hh}:{mm}',
        weekdays: {
            w1: '周一', w2: '周二', w3: '周三', w4: '周四', w5: '周五', w6: '周六', w7: '周日'
        }
    },
    palette: {
        placeholder: '搜索命令、表、连接、历史... ( > 命令 / # 表 / @ 连接 )',
        hint: '↑↓ 选择 · Enter 打开 · Esc 关闭',
        searching: '搜索中...',
        empty: '无匹配结果',
        connected: '已连接',
        group: {
            commands: '命令',
            connections: '连接',
            tables: '表',
            history: '历史'
        },
        cmd: {
            'cmd-new-query': '新建查询',
            'cmd-new-connection': '新建连接',
            'cmd-history': '打开查询历史',
            'cmd-theme': '切换深浅主题',
            'cmd-tasks': '打开后台任务面板'
        }
    },
    chart: {
        noNumeric: '结果集中没有可绘制的数值列',
        bar: '柱状图',
        line: '折线图',
        pie: '饼图',
        truncated: '仅展示前 {max} 行'
    }
}
