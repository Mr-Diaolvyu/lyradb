/**
 * English (US) locale messages
 */
export default {
    common: {
        loading: 'Loading...',
        confirm: 'OK',
        cancel: 'Cancel',
        save: 'Save',
        delete: 'Delete',
        edit: 'Edit',
        export: 'Export',
        import: 'Import',
        search: 'Search...',
        saved: 'Saved',
        deleted: 'Deleted',
        failed: 'Operation failed',
        unknownError: 'Unknown error'
    },
    header: {
        newConnection: 'New Connection',
        newQuery: 'New Query',
        selectDatabase: 'Select Database',
        execute: 'Run (Ctrl+Enter)',
        explain: 'Explain',
        runBackground: 'Run in Background',
        erDiagram: 'ER Diagram',
        migration: 'Migrate',
        language: 'Language'
    },
    appearance: {
        title: 'Appearance',
        theme: 'Theme',
        light: 'Light',
        dark: 'Dark',
        system: 'System',
        accent: 'Accent Color',
        navy: 'Navy',
        emerald: 'Emerald',
        amber: 'Amber',
        violet: 'Violet',
        density: 'Row Density',
        comfortable: 'Comfortable',
        compact: 'Compact'
    },
    filter: {
        title: 'Filters',
        column: 'Column',
        value: 'Value',
        addCondition: 'Add Condition',
        apply: 'Apply',
        clear: 'Clear',
        cleared: 'Filters cleared',
        previewEmpty: 'Fill in conditions to preview the WHERE clause',
        noResult: 'Run a query first, then filter by result columns'
    },
    sideNav: {
        exportConnections: 'Export Connections',
        importConnections: 'Import Connections',
        newConnection: 'New Connection',
        noExportable: 'No connections to export',
        exported: 'Exported {count} connection profile(s)',
        exportFailed: 'Export failed: {msg}'
    },
    statusBar: {
        notConnected: 'Not Connected',
        rows: '{count} rows',
        truncated: 'Result Truncated',
        driverNotLoaded: 'Driver Not Loaded',
        driverReady: '{name} Driver Ready',
        tabs: '{count} tab(s)'
    },
    tasks: {
        title: 'Background Tasks',
        empty: 'No background tasks',
        viewResult: 'View Result',
        cancel: 'Cancel',
        remove: 'Remove',
        rows: '{rows} rows',
        status: {
            RUNNING: 'Running',
            DONE: 'Done',
            ERROR: 'Error',
            CANCELLED: 'Cancelled'
        }
    },
    reports: {
        title: 'Report Subscriptions',
        create: 'New Subscription',
        editTitle: 'Edit Subscription',
        empty: 'No data',
        name: 'Name',
        connection: 'Connection',
        schedule: 'Schedule',
        lastRun: 'Last Run',
        enabled: 'Enabled',
        actions: 'Actions',
        trigger: 'Run Now',
        runs: 'Runs',
        runsTitle: 'Runs - {name}',
        sqlHint: 'Only SELECT / WITH queries are allowed',
        defaultDb: 'Default Database',
        typeHourly: 'Hourly',
        typeDaily: 'Daily',
        typeWeekly: 'Weekly',
        weekday: 'Weekday',
        runHour: 'Run Hour',
        runMinute: 'Run Minute',
        formIncomplete: 'Please fill in name, connection, SQL and webhook URL',
        deleteConfirm: 'Delete this subscription and its run history?',
        triggerDone: 'Executed successfully, {rows} rows pushed',
        runAt: 'Run At',
        result: 'Result',
        success: 'Success',
        failed: 'Failed',
        rowCount: 'Rows',
        elapsed: 'Elapsed',
        pushStatus: 'Push Status',
        error: 'Error',
        labelHourly: 'Hourly at :{mm}',
        labelDaily: 'Daily at {hh}:{mm}',
        weekdays: {
            w1: 'Mon', w2: 'Tue', w3: 'Wed', w4: 'Thu', w5: 'Fri', w6: 'Sat', w7: 'Sun'
        }
    },
    palette: {
        placeholder: 'Search commands, tables, connections, history... ( > cmd / # table / @ conn )',
        hint: '↑↓ Navigate · Enter Open · Esc Close',
        searching: 'Searching...',
        empty: 'No results',
        connected: 'Connected',
        group: {
            commands: 'Commands',
            connections: 'Connections',
            tables: 'Tables',
            history: 'History'
        },
        cmd: {
            'cmd-new-query': 'New Query',
            'cmd-new-connection': 'New Connection',
            'cmd-history': 'Open Query History',
            'cmd-theme': 'Toggle Theme',
            'cmd-tasks': 'Open Background Tasks'
        }
    },
    chart: {
        noNumeric: 'No numeric columns to plot in the result set',
        bar: 'Bar',
        line: 'Line',
        pie: 'Pie',
        truncated: 'Showing first {max} rows only'
    }
}
