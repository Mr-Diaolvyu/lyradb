/**
 * SQL 多方言语册
 *
 * 为 Monaco Editor 注册 MaxCompute 和 ClickHouse 自定义 SQL 方言，
 * 提供各自的语法高亮关键字。
 */
import * as monaco from 'monaco-editor'

let registered = false

/** 根据数据库类型获取 Monaco 语言 ID */
export function getLanguageByDbType(dbType?: string): string {
    if (!dbType) return 'sql'
    switch (dbType.toLowerCase()) {
        case 'maxcompute':
            return 'maxcompute'
        case 'clickhouse':
            return 'clickhouse'
        default:
            return 'sql'
    }
}

/** MaxCompute SQL 关键字 */
const maxcomputeKeywords = [
    'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN',
    'IS', 'NULL', 'AS', 'ORDER', 'BY', 'GROUP', 'HAVING', 'LIMIT', 'OFFSET',
    'INSERT', 'OVERWRITE', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE',
    'CREATE', 'TABLE', 'ALTER', 'DROP', 'INDEX', 'VIEW', 'DATABASE',
    'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'OUTER', 'ON', 'UNION',
    'ALL', 'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'IF',
    'EXISTS', 'SHOW', 'TABLES', 'COLUMNS', 'DESCRIBE', 'EXPLAIN',
    'WITH', 'PARTITION', 'PARTITIONED', 'CLUSTERED', 'SORTED', 'BUCKETS',
    'STORED', 'LIFECYCLE', 'TBLPROPERTIES', 'SERDE', 'INPUTFORMAT',
    'OUTPUTFORMAT', 'LOCATION', 'EXTERNAL', 'TEMPORARY', 'FUNCTION',
    'LATERAL', 'EXPLODE', 'DOUBLE', 'FLOAT', 'BIGINT', 'INT', 'TINYINT',
    'SMALLINT', 'BOOLEAN', 'STRING', 'BINARY', 'TIMESTAMP', 'DATETIME',
    'DATE', 'DECIMAL', 'CHAR', 'VARCHAR', 'STRUCT', 'ARRAY', 'MAP',
    'CASCADE', 'RESTRICT', 'PURGE', 'GRANT', 'REVOKE', 'ROLE', 'ROLES',
]

/** MaxCompute 内置函数 */
const maxcomputeFunctions = [
    'TO_CHAR', 'DATEPART', 'DATE_FORMAT', 'CAST', 'COALESCE', 'CONCAT',
    'SUBSTR', 'SUBSTRING', 'LENGTH', 'TRIM', 'REPLACE', 'REGEXP_REPLACE',
    'REGEXP_EXTRACT', 'SPLIT', 'EXPLODE', 'GET_JSON_OBJECT', 'JSON_TUPLE',
    'SIZE', 'NVL', 'DECODE', 'GREATEST', 'LEAST', 'ROW_NUMBER', 'RANK',
    'DENSE_RANK', 'LAG', 'LEAD', 'FIRST_VALUE', 'LAST_VALUE', 'SUM',
    'COUNT', 'AVG', 'MAX', 'MIN', 'STDDEV', 'VARIANCE', 'COLLECT_LIST',
    'COLLECT_SET', 'PERCENTILE', 'MEDIAN', 'WM_CONCAT',
]

/** ClickHouse SQL 关键字 */
const clickhouseKeywords = [
    'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN',
    'IS', 'NULL', 'AS', 'ORDER', 'BY', 'GROUP', 'HAVING', 'LIMIT', 'OFFSET',
    'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE',
    'TABLE', 'ALTER', 'DROP', 'INDEX', 'VIEW', 'DATABASE', 'JOIN',
    'INNER', 'LEFT', 'RIGHT', 'FULL', 'OUTER', 'ON', 'UNION', 'ALL',
    'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'IF', 'EXISTS',
    'WITH', 'FINAL', 'PREWHERE', 'ARRAY', 'FORMAT', 'SETTINGS',
    'PARTITION', 'PARTITIONED', 'ENGINE', 'MergeTree', 'ReplacingMergeTree',
    'SummingMergeTree', 'AggregatingMergeTree', 'CollapsingMergeTree',
    'VersionedCollapsingMergeTree', 'Distributed', 'Replicated',
    'Nullable', 'Codec', 'TTL', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT',
    'UINT8', 'UINT16', 'UINT32', 'UINT64', 'FLOAT', 'DOUBLE', 'DECIMAL',
    'DATE', 'DATETIME', 'DATETIME64', 'BOOLEAN', 'STRING', 'FIXEDSTRING',
    'UUID', 'Enum8', 'Enum16', 'Array', 'Tuple', 'Map', 'Nested',
    'LowCardinality', 'AggregateFunction', 'SimpleAggregateFunction',
]

/** ClickHouse 内置函数 */
const clickhouseFunctions = [
    'toUInt8', 'toUInt16', 'toUInt32', 'toUInt64', 'toInt8', 'toInt16',
    'toInt32', 'toInt64', 'toFloat32', 'toFloat64', 'toDate', 'toTime',
    'toDateTime', 'toDateTime64', 'toString', 'toUUID', 'toTypeName',
    'CAST', 'COALESCE', 'IFNULL', 'NULLIF', 'GREATEST', 'LEAST', 'CONCAT',
    'substring', 'splitByChar', 'splitByString', 'length', 'empty',
    'notEmpty', 'lower', 'upper', 'trim', 'replaceAll', 'replaceRegexpAll',
    'extractRegexp', 'match', 'like', 'notLike', 'ilike', 'notILike',
    'position', 'left', 'right', 'format', 'printf', 'now', 'today',
    'yesterday', 'dateDiff', 'addDays', 'addMonths', 'addYears', 'sum',
    'count', 'avg', 'max', 'min', 'any', 'anyLast', 'groupBitOr',
    'groupBitAnd', 'groupUniqArray', 'groupArray', 'groupArrayInsertAt',
    'topK', 'quantile', 'quantiles', 'varSamp', 'varPop', 'stddevSamp',
    'stddevPop', 'corr', 'covarSamp', 'covarPop', 'sequenceCount',
    'sequenceMatch', 'windowFunnel', 'retention', 'uniq', 'uniqExact',
    'uniqCombined', 'uniqHLL12', 'uniqUpTo', 'hllCardinality',
]

/** 注册自定义 SQL 方言 (只注册一次) */
export function registerSqlDialects() {
    if (registered) return
    registered = true

    const buildTokenizer = (keywords: string[], functions: string[]) => ({
        tokenizer: {
            root: [
                [new RegExp('\\b(' + keywords.join('|') + ')\\b', 'i'), 'keyword'],
                [new RegExp('\\b(' + functions.join('|') + ')\\b', 'i'), 'predefined'],
                [/\d+\.\d+/, 'number.float'],
                [/\d+/, 'number'],
                [/'[^']*'/, 'string'],
                [/"/, 'string', '@stringDouble'],
                [/--.*$/, 'comment'],
                [/\/\*[\s\S]*?\*\//, 'comment'],
                [/[a-zA-Z_]\w*/, 'identifier'],
                [/[<>=!]+/, 'operator'],
                [/[+\-*/%]/, 'operator'],
            ],
            stringDouble: [
                [/[^"]+/, 'string'],
                [/"/, 'string', '@pop'],
            ],
        },
    })

    // === MaxCompute SQL 方言 ===
    monaco.languages.register({ id: 'maxcompute' })
    monaco.languages.setMonarchTokensProvider('maxcompute', buildTokenizer(
        maxcomputeKeywords, maxcomputeFunctions
    ) as any)

    // === ClickHouse SQL 方言 ===
    monaco.languages.register({ id: 'clickhouse' })
    monaco.languages.setMonarchTokensProvider('clickhouse', buildTokenizer(
        clickhouseKeywords, clickhouseFunctions
    ) as any)

    // 配置语言自动闭合括号
    for (const lang of ['maxcompute', 'clickhouse']) {
        monaco.languages.setLanguageConfiguration(lang, {
            comments: {
                lineComment: '--',
                blockComment: ['/*', '*/'],
            },
            brackets: [
                ['{', '}'],
                ['[', ']'],
                ['(', ')'],
            ],
            autoClosingPairs: [
                { open: '{', close: '}' },
                { open: '[', close: ']' },
                { open: '(', close: ')' },
                { open: "'", close: "'", notIn: ['string', 'comment'] },
                { open: '"', close: '"', notIn: ['string', 'comment'] },
            ],
        })
    }
}
