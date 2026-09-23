package io.github.lexaquila.lyradb.transfer.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException.Code;

/**
 * LyraDB 连接导入 Excel 模板生成与解析器。
 *
 * <p>只接受 OOXML .xlsx，不计算公式。Excel 中的数据库凭据是明文，解析结果会
 * 显式标记为明文凭据风险。</p>
 */
public final class ConnectionExcelTemplateCodec {

    public static final String FILE_NAME = "LyraDB-连接导入模板.xlsx";
    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String DATA_SHEET = "连接导入";
    public static final String GUIDE_SHEET = "填写说明";

    private static final int HEADER_ROW = 1;
    private static final int FIRST_DATA_ROW = 2;
    private static final int MAX_TEMPLATE_ROWS = 1_000;
    private static final List<String> SUPPORTED_TYPES = List.of(
            "MYSQL", "POSTGRESQL", "ORACLE", "MSSQL", "SQLITE",
            "CLICKHOUSE", "MAXCOMPUTE", "MONGODB", "REDIS");
    private static final List<Column> TEMPLATE_COLUMNS = List.of(
            Column.NAME, Column.DB_TYPE, Column.HOST, Column.PORT,
            Column.DATABASE, Column.USERNAME, Column.PASSWORD,
            Column.SERVICE_NAME, Column.FILE_PATH, Column.ENDPOINT,
            Column.PROJECT, Column.ACCESS_KEY_ID, Column.ACCESS_KEY_SECRET);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectionPackageLimits limits;

    public ConnectionExcelTemplateCodec() {
        this(ConnectionPackageLimits.defaults());
    }

    public ConnectionExcelTemplateCodec(ConnectionPackageLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** 创建可直接填写并再次导入 LyraDB 的 Excel 模板。 */
    public byte[] createTemplate() throws ConnectionPackageException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            createDataSheet(workbook, styles);
            createGuideSheet(workbook, styles);
            workbook.setActiveSheet(0);
            workbook.getProperties().getCoreProperties()
                    .setTitle("LyraDB 连接批量导入模板");
            workbook.write(output);
            byte[] content = output.toByteArray();
            ensureFileSize(content.length);
            return content;
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ConnectionPackageException(Code.IO_ERROR,
                    "无法生成 Excel 连接导入模板", exception);
        }
    }

    /** 读取 Excel 模板内容。调用方提供的字节数组不会被修改。 */
    public ConnectionPackageReadResult read(byte[] source)
            throws ConnectionPackageException {
        if (source == null || source.length == 0) {
            throw error(Code.INVALID_INPUT, "Excel 导入文件不能为空");
        }
        ensureFileSize(source.length);
        if (!hasXlsxSignature(source)) {
            throw error(Code.UNSUPPORTED_FORMAT,
                    "导入文件不是有效的 .xlsx Excel 文件");
        }
        try (InputStream input = new ByteArrayInputStream(source);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            if (!workbook.getExternalLinksTable().isEmpty()) {
                throw error(Code.INVALID_FIELD, "Excel 导入文件不得包含外部链接");
            }
            Sheet sheet = workbook.getSheet(DATA_SHEET);
            if (sheet == null) {
                throw error(Code.INVALID_FIELD,
                        "Excel 导入文件缺少“" + DATA_SHEET + "”工作表");
            }
            int sheetIndex = workbook.getSheetIndex(sheet);
            if (workbook.isSheetHidden(sheetIndex)
                    || workbook.isSheetVeryHidden(sheetIndex)) {
                throw error(Code.INVALID_FIELD,
                        "“" + DATA_SHEET + "”工作表不得隐藏");
            }
            return parseSheet(sheet);
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw error(Code.MALFORMED_PACKAGE,
                    "Excel 导入文件损坏、加密或格式不受支持");
        }
    }

    public static boolean hasXlsxSignature(byte[] source) {
        return source != null && source.length >= 4
                && source[0] == 'P' && source[1] == 'K'
                && (source[2] == 3 || source[2] == 5 || source[2] == 7)
                && (source[3] == 4 || source[3] == 6 || source[3] == 8);
    }

    private ConnectionPackageReadResult parseSheet(Sheet sheet)
            throws ConnectionPackageException {
        DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
        Header header = findHeader(sheet, formatter);
        if (sheet.getLastRowNum() - header.rowIndex()
                > limits.maxCollectionElements()) {
            throw error(Code.TOO_MANY_CONNECTIONS, "Excel 导入文件包含过多行");
        }
        List<ConnectionPackageEntry> entries = new ArrayList<>();
        boolean hasCredentials = false;
        for (int rowIndex = header.rowIndex() + 1;
             rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlank(row, header, formatter)) {
                continue;
            }
            if (entries.size() >= limits.maxConnections()) {
                throw error(Code.TOO_MANY_CONNECTIONS,
                        "Excel 导入连接数量超过允许上限");
            }
            ConnectionPackageEntry entry = parseRow(
                    row, rowIndex + 1, header, formatter);
            hasCredentials |= !entry.credentials().isEmpty();
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            throw error(Code.INVALID_INPUT,
                    "Excel 模板中没有可导入的连接，请从第 3 行开始填写");
        }
        CredentialExportPolicy policy = hasCredentials
                ? CredentialExportPolicy.PLAINTEXT
                : CredentialExportPolicy.OMIT;
        return new ConnectionPackageReadResult(
                ConnectionPackageCodec.FORMAT_VERSION, Instant.now(),
                "3.1.2-excel", policy,
                ConnectionPackageRisk.forPolicy(policy), entries);
    }

    private Header findHeader(Sheet sheet, DataFormatter formatter)
            throws ConnectionPackageException {
        int lastCandidate = Math.min(sheet.getLastRowNum(), 9);
        for (int rowIndex = 0; rowIndex <= lastCandidate; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<Column, Integer> columns = new LinkedHashMap<>();
            for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
                Column column = Column.fromHeader(cellText(
                        row.getCell(cellIndex), formatter,
                        rowIndex + 1, cellIndex + 1));
                if (column != null && columns.put(column, cellIndex) != null) {
                    throw error(Code.INVALID_FIELD,
                            "Excel 表头存在重复字段：“" + column.title + "”");
                }
            }
            if (columns.containsKey(Column.NAME)
                    && columns.containsKey(Column.DB_TYPE)) {
                return new Header(rowIndex, Map.copyOf(columns));
            }
        }
        throw error(Code.INVALID_FIELD,
                "Excel 表头必须包含“连接名称 *”和“数据库类型 *”");
    }

    private ConnectionPackageEntry parseRow(Row row, int displayRow,
            Header header, DataFormatter formatter)
            throws ConnectionPackageException {
        String name = requiredValue(row, header, Column.NAME, formatter, displayRow);
        String dbType = requiredValue(row, header, Column.DB_TYPE,
                formatter, displayRow).toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(dbType)) {
            throw rowError(displayRow, "不支持的数据库类型：" + dbType);
        }
        Map<String, Object> parameters = parseJsonMap(value(row, header,
                Column.EXTRA_PARAMETERS, formatter, displayRow),
                displayRow, "其他参数 JSON");
        Map<String, Object> credentials = parseJsonMap(value(row, header,
                Column.EXTRA_CREDENTIALS, formatter, displayRow),
                displayRow, "其他凭据 JSON");
        putText(parameters, "host", row, header, Column.HOST, formatter, displayRow);
        putInteger(parameters, "port", row, header, Column.PORT, formatter, displayRow);
        putText(parameters, "database", row, header, Column.DATABASE, formatter, displayRow);
        putText(parameters, "serviceName", row, header, Column.SERVICE_NAME, formatter, displayRow);
        putText(parameters, "filePath", row, header, Column.FILE_PATH, formatter, displayRow);
        putText(parameters, "protocol", row, header, Column.PROTOCOL, formatter, displayRow);
        putText(parameters, "endpoint", row, header, Column.ENDPOINT, formatter, displayRow);
        putText(parameters, "project", row, header, Column.PROJECT, formatter, displayRow);
        putText(parameters, "username", row, header, Column.USERNAME, formatter, displayRow);
        putText(parameters, "accessKeyId", row, header, Column.ACCESS_KEY_ID, formatter, displayRow);
        putInteger(parameters, "databaseIndex", row, header,
                Column.DATABASE_INDEX, formatter, displayRow);
        putText(parameters, "authSource", row, header, Column.AUTH_SOURCE, formatter, displayRow);
        putBoolean(parameters, "ssl", row, header, Column.SSL, formatter, displayRow);
        putSensitiveText(credentials, "password", row, header,
                Column.PASSWORD, formatter, displayRow);
        putSensitiveText(credentials, "accessKeySecret", row, header,
                Column.ACCESS_KEY_SECRET, formatter, displayRow);
        if (parameters.size() + credentials.size()
                > limits.maxParametersPerConnection()) {
            throw rowError(displayRow, "连接参数数量超过允许上限");
        }
        Set<String> credentialKeys = splitSet(value(row, header,
                Column.CREDENTIAL_KEYS, formatter, displayRow));
        credentialKeys.addAll(credentials.keySet());
        List<String> tags = List.copyOf(splitSet(value(row, header,
                Column.TAGS, formatter, displayRow)));
        try {
            return new ConnectionPackageEntry("", name, dbType,
                    value(row, header, Column.DISPLAY_NAME, formatter, displayRow),
                    parameters, credentials, credentialKeys,
                    value(row, header, Column.GROUP, formatter, displayRow),
                    value(row, header, Column.COLOR, formatter, displayRow),
                    value(row, header, Column.DESCRIPTION, formatter, displayRow),
                    tags,
                    booleanValue(value(row, header, Column.FAVORITE,
                            formatter, displayRow), false, displayRow,
                            Column.FAVORITE.title),
                    integerValue(value(row, header, Column.SORT_ORDER,
                            formatter, displayRow), 0, displayRow,
                            Column.SORT_ORDER.title),
                    booleanValue(value(row, header, Column.AUTO_CONNECT,
                            formatter, displayRow), false, displayRow,
                            Column.AUTO_CONNECT.title));
        } catch (IllegalArgumentException exception) {
            throw rowError(displayRow, exception.getMessage());
        }
    }
    private Map<String, Object> parseJsonMap(String value, int row,
            String label) throws ConnectionPackageException {
        if (value.isBlank()) return new LinkedHashMap<>();
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw rowError(row, label + "必须是 JSON 对象");
            }
            validateJsonNode(node, 0, row, label);
            return new LinkedHashMap<>(mapper.convertValue(node,
                    new TypeReference<Map<String, Object>>() { }));
        } catch (ConnectionPackageException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw rowError(row, label + "格式无效");
        }
    }

    private void validateJsonNode(JsonNode node, int depth, int row,
            String label) throws ConnectionPackageException {
        if (depth > limits.maxNestingDepth()) {
            throw rowError(row, label + "嵌套层级超过允许上限");
        }
        if (node.isTextual()
                && node.textValue().length() > limits.maxStringLength()) {
            throw rowError(row, label + "包含过长文本");
        }
        if (node.isContainerNode()) {
            if (node.size() > limits.maxCollectionElements()) {
                throw rowError(row, label + "包含过多元素");
            }
            for (JsonNode child : node) {
                validateJsonNode(child, depth + 1, row, label);
            }
        }
    }

    private boolean isBlank(Row row, Header header, DataFormatter formatter)
            throws ConnectionPackageException {
        for (int index : header.columns().values()) {
            if (!cellText(row.getCell(index), formatter,
                    row.getRowNum() + 1, index + 1).isBlank()) return false;
        }
        return true;
    }

    private String requiredValue(Row row, Header header, Column column,
            DataFormatter formatter, int displayRow)
            throws ConnectionPackageException {
        String value = value(row, header, column, formatter, displayRow);
        if (value.isBlank()) {
            throw rowError(displayRow, column.title + "不能为空");
        }
        return value;
    }

    private String value(Row row, Header header, Column column,
            DataFormatter formatter, int displayRow)
            throws ConnectionPackageException {
        Integer index = header.columns().get(column);
        return index == null ? "" : cellText(
                row.getCell(index), formatter, displayRow, index + 1);
    }

    private String sensitiveValue(Row row, Header header, Column column,
            DataFormatter formatter, int displayRow)
            throws ConnectionPackageException {
        Integer index = header.columns().get(column);
        return index == null ? "" : cellText(
                row.getCell(index), formatter, displayRow, index + 1, false);
    }

    private String cellText(Cell cell, DataFormatter formatter,
            int row, int column) throws ConnectionPackageException {
        return cellText(cell, formatter, row, column, true);
    }

    private String cellText(Cell cell, DataFormatter formatter,
            int row, int column, boolean trim)
            throws ConnectionPackageException {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            throw error(Code.INVALID_FIELD, "Excel 第 " + row + " 行第 "
                    + column + " 列不得使用公式");
        }
        if (cell.getCellType() == CellType.ERROR) {
            throw error(Code.INVALID_FIELD, "Excel 第 " + row + " 行第 "
                    + column + " 列包含错误值");
        }
        String value = formatter.formatCellValue(cell);
        if (value.length() > limits.maxStringLength()) {
            throw error(Code.INVALID_FIELD, "Excel 第 " + row + " 行第 "
                    + column + " 列文本过长");
        }
        return trim ? value.trim() : value;
    }

    private void putText(Map<String, Object> target, String key,
            Row row, Header header, Column column, DataFormatter formatter,
            int displayRow) throws ConnectionPackageException {
        String value = value(row, header, column, formatter, displayRow);
        if (!value.isBlank()) target.put(key, value);
    }

    private void putSensitiveText(Map<String, Object> target, String key,
            Row row, Header header, Column column, DataFormatter formatter,
            int displayRow) throws ConnectionPackageException {
        String value = sensitiveValue(
                row, header, column, formatter, displayRow);
        if (!value.isBlank()) target.put(key, value);
    }

    private void putInteger(Map<String, Object> target, String key,
            Row row, Header header, Column column, DataFormatter formatter,
            int displayRow) throws ConnectionPackageException {
        String value = value(row, header, column, formatter, displayRow);
        if (!value.isBlank()) {
            target.put(key, integerValue(value, 0, displayRow, column.title));
        }
    }

    private void putBoolean(Map<String, Object> target, String key,
            Row row, Header header, Column column, DataFormatter formatter,
            int displayRow) throws ConnectionPackageException {
        String value = value(row, header, column, formatter, displayRow);
        if (!value.isBlank()) {
            target.put(key, booleanValue(value, false, displayRow, column.title));
        }
    }

    private int integerValue(String value, int defaultValue, int row,
            String label) throws ConnectionPackageException {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return new BigDecimal(value.replace(",", "")).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw rowError(row, label + "必须是整数");
        }
    }

    private boolean booleanValue(String value, boolean defaultValue,
            int row, String label) throws ConnectionPackageException {
        if (value == null || value.isBlank()) return defaultValue;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "是", "true", "1", "yes", "y" -> true;
            case "否", "false", "0", "no", "n" -> false;
            default -> throw rowError(row, label + "只能填写“是”或“否”");
        };
    }

    private static Set<String> splitSet(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return result;
        Arrays.stream(value.split("[,，;；\\n\\r]+"))
                .map(String::trim).filter(item -> !item.isEmpty())
                .forEach(result::add);
        return result;
    }

    private void ensureFileSize(int size) throws ConnectionPackageException {
        if (size > limits.maxFileBytes()) {
            throw error(Code.FILE_TOO_LARGE, "Excel 导入文件超过允许大小");
        }
    }

    private static ConnectionPackageException rowError(int row, String message) {
        return error(Code.INVALID_FIELD, "Excel 第 " + row + " 行：" + message);
    }

    private static ConnectionPackageException error(Code code, String message) {
        return new ConnectionPackageException(code, message);
    }

    private void createDataSheet(XSSFWorkbook workbook, Styles styles) {
        Sheet sheet = workbook.createSheet(DATA_SHEET);
        Row notice = sheet.createRow(0);
        notice.setHeightInPoints(36);
        Cell cell = notice.createCell(0);
        cell.setCellValue("从第 3 行开始填写，每行一个连接；先填带 * 的两列，再按数据库类型填写。密码和 AccessKey Secret 为明文，导入后请删除文件。");
        cell.setCellStyle(styles.notice);
        sheet.addMergedRegion(new CellRangeAddress(
                0, 0, 0, TEMPLATE_COLUMNS.size() - 1));
        createHeader(sheet, HEADER_ROW, styles, true);
        sheet.createFreezePane(0, FIRST_DATA_ROW);
        sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW,
                0, TEMPLATE_COLUMNS.size() - 1));
        addValidations(sheet);
    }

    private void createGuideSheet(XSSFWorkbook workbook, Styles styles)
            throws IOException {
        Sheet sheet = workbook.createSheet(GUIDE_SHEET);
        Row notice = sheet.createRow(0);
        notice.setHeightInPoints(30);
        Cell cell = notice.createCell(0);
        cell.setCellValue("所有类型先填连接名称、数据库类型；其他列只填对应数据库使用的字段。端口留空时使用驱动默认值。");
        cell.setCellStyle(styles.notice);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
        createSimpleHeader(sheet, 1,
                new String[]{"数据库类型", "常用填写字段", "默认端口"}, styles);
        try (InputStream input = ConnectionExcelTemplateCodec.class
                .getResourceAsStream("/drivers.json")) {
            if (input == null) throw new IOException("drivers.json 不存在");
            int rowIndex = 2;
            for (JsonNode driver : mapper.readTree(input)) {
                String type = driver.path("dbType").asText();
                if (!SUPPORTED_TYPES.contains(type)) continue;
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(type);
                row.createCell(1).setCellValue(guideFields(type));
                int port = driver.path("defaultPort").asInt();
                row.createCell(2).setCellValue(port == 0 ? "" : String.valueOf(port));
                applyStyle(row, 3, styles.body);
            }
            Row advanced = sheet.createRow(rowIndex + 1);
            Cell advancedCell = advanced.createCell(0);
            advancedCell.setCellValue("高级选项可在“连接导入”表头右侧新增“其他参数 JSON”等旧模板字段；旧版完整模板仍可导入。");
            advancedCell.setCellStyle(styles.body);
            sheet.addMergedRegion(new CellRangeAddress(rowIndex + 1,
                    rowIndex + 1, 0, 2));
        }
        setWidths(sheet, new int[]{20, 70, 14});
        sheet.createFreezePane(0, 2);
    }

    private static String guideFields(String type) {
        return switch (type) {
            case "MYSQL" -> "主机地址、用户名；按需填数据库名、密码";
            case "POSTGRESQL" -> "主机地址、数据库名、用户名；按需填密码";
            case "ORACLE" -> "主机地址、Oracle 服务名、用户名、密码";
            case "MSSQL" -> "主机地址、数据库名、用户名；按需填密码";
            case "SQLITE" -> "SQLite 文件路径";
            case "CLICKHOUSE" -> "主机地址；按需填数据库名、用户名、密码（默认 HTTPS）";
            case "MAXCOMPUTE" -> "Endpoint、Project、AccessKey ID、AccessKey Secret";
            case "MONGODB" -> "主机地址；按需填数据库名、用户名、密码";
            case "REDIS" -> "主机地址；按需填密码";
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + type);
        };
    }
    private void createHeader(Sheet sheet, int rowIndex, Styles styles,
            boolean comments) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(34);
        Drawing<?> drawing = comments ? sheet.createDrawingPatriarch() : null;
        CreationHelper helper = sheet.getWorkbook().getCreationHelper();
        for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
            Column column = TEMPLATE_COLUMNS.get(index);
            Cell cell = row.createCell(index);
            cell.setCellValue(column.title);
            cell.setCellStyle(column.sensitive
                    ? styles.sensitiveHeader : styles.header);
            sheet.setColumnWidth(index, column.width * 256);
            if (comments) {
                ClientAnchor anchor = helper.createClientAnchor();
                anchor.setCol1(index);
                anchor.setCol2(Math.min(index + 4,
                        TEMPLATE_COLUMNS.size()));
                anchor.setRow1(rowIndex);
                anchor.setRow2(rowIndex + 5);
                Comment comment = drawing.createCellComment(anchor);
                comment.setAuthor("LyraDB");
                comment.setString(helper.createRichTextString(
                        column.description + "\n键名：" + column.key));
                cell.setCellComment(comment);
            }
        }
    }

    private static void createSimpleHeader(Sheet sheet, int rowIndex,
            String[] values,
            Styles styles) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(values[index]);
            cell.setCellStyle(styles.header);
        }
    }

    private void addValidations(Sheet sheet) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        addListValidation(sheet, helper, Column.DB_TYPE,
                SUPPORTED_TYPES.toArray(String[]::new));
    }

    private static void addListValidation(Sheet sheet,
            DataValidationHelper helper, Column column, String[] values) {
        DataValidationConstraint constraint =
                helper.createExplicitListConstraint(values);
        CellRangeAddressList range = new CellRangeAddressList(
                FIRST_DATA_ROW, FIRST_DATA_ROW + MAX_TEMPLATE_ROWS - 1,
                TEMPLATE_COLUMNS.indexOf(column), TEMPLATE_COLUMNS.indexOf(column));
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    private static void applyStyle(Row row, int count, CellStyle style) {
        for (int index = 0; index < count; index++) {
            row.getCell(index).setCellStyle(style);
        }
    }

    private static void setWidths(Sheet sheet, int[] widths) {
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, widths[index] * 256);
        }
    }

    private record Header(int rowIndex, Map<Column, Integer> columns) { }

    private enum Column {
        NAME("name", "连接名称 *", true, false, 22,
                "LyraDB 中显示的连接名称；同一批次不能重复。"),
        DB_TYPE("dbType", "数据库类型 *", true, false, 18,
                "从下拉列表选择支持的数据库类型。"),
        DISPLAY_NAME("displayName", "显示名称", false, false, 22,
                "企业版数据源显示名；留空时使用连接名称。"),
        HOST("host", "主机地址", false, false, 22,
                "数据库服务器主机名或 IP。"),
        PORT("port", "端口", false, false, 12,
                "数据库服务端口，必须是整数。"),
        DATABASE("database", "数据库名", false, false, 20,
                "数据库、认证库或默认库名称。"),
        SERVICE_NAME("serviceName", "Oracle 服务名", false, false, 20,
                "Oracle serviceName。"),
        FILE_PATH("filePath", "SQLite 文件路径", false, false, 32,
                "SQLite 数据库文件的本地路径。"),
        PROTOCOL("protocol", "连接协议", false, false, 14,
                "例如 ClickHouse 的 https 或 http。"),
        ENDPOINT("endpoint", "Endpoint", false, false, 40,
                "例如 MaxCompute API Endpoint。"),
        PROJECT("project", "Project", false, false, 22,
                "例如 MaxCompute Project 名称。"),
        USERNAME("username", "用户名", false, false, 22,
                "数据库登录用户名。"),
        PASSWORD("password", "密码（明文）", false, true, 22,
                "可选。填写后 Excel 文件会包含明文数据库密码。"),
        ACCESS_KEY_ID("accessKeyId", "AccessKey ID", false, false, 24,
                "MaxCompute 等服务使用的 AccessKey ID。"),
        ACCESS_KEY_SECRET("accessKeySecret", "AccessKey Secret（明文）",
                false, true, 28,
                "可选。填写后 Excel 文件会包含明文 AccessKey Secret。"),
        DATABASE_INDEX("databaseIndex", "Redis 数据库索引", false,
                false, 18, "Redis databaseIndex，必须是整数。"),
        AUTH_SOURCE("authSource", "MongoDB 认证源", false, false, 20,
                "MongoDB authSource。"),
        SSL("ssl", "启用 SSL", false, false, 14,
                "填写“是”或“否”。"),
        EXTRA_PARAMETERS("extraParametersJson", "其他参数 JSON", false,
                false, 36, "JSON 对象。与独立列重复时，以独立列的值为准。"),
        EXTRA_CREDENTIALS("extraCredentialsJson", "其他凭据 JSON（明文）",
                false, true, 38,
                "JSON 对象，其中所有值都按明文数据库凭据处理。"),
        CREDENTIAL_KEYS("credentialKeys", "凭据字段名", false, false, 28,
                "逗号分隔。用于标记需要补录但本文件未携带值的凭据字段。"),
        GROUP("group", "分组", false, false, 18, "个人版连接分组。"),
        COLOR("color", "颜色", false, false, 14, "可选颜色标识。"),
        DESCRIPTION("description", "描述", false, false, 32,
                "连接用途或环境说明。"),
        TAGS("tags", "标签", false, false, 24, "使用逗号分隔多个标签。"),
        FAVORITE("favorite", "收藏", false, false, 12,
                "填写“是”或“否”。"),
        SORT_ORDER("sortOrder", "排序号", false, false, 12,
                "整数，数值越小越靠前。"),
        AUTO_CONNECT("autoConnect", "自动连接", false, false, 14,
                "填写“是”或“否”。");

        private static final Map<String, Column> LOOKUP = buildLookup();
        private final String key;
        private final String title;
        private final boolean required;
        private final boolean sensitive;
        private final int width;
        private final String description;

        Column(String key, String title, boolean required,
                boolean sensitive, int width, String description) {
            this.key = key;
            this.title = title;
            this.required = required;
            this.sensitive = sensitive;
            this.width = width;
            this.description = description;
        }

        private static Column fromHeader(String value) {
            return LOOKUP.get(normalizeHeader(value));
        }

        private static Map<String, Column> buildLookup() {
            Map<String, Column> result = new LinkedHashMap<>();
            for (Column column : values()) {
                result.put(normalizeHeader(column.title), column);
                result.put(normalizeHeader(column.key), column);
            }
            return Map.copyOf(result);
        }

        private static String normalizeHeader(String value) {
            if (value == null) return "";
            return value.trim().toLowerCase(Locale.ROOT)
                    .replace(" ", "").replace("*", "").replace("_", "")
                    .replace("（", "(").replace("）", ")");
        }
    }

    private static final class Styles {
        private final CellStyle notice;
        private final CellStyle header;
        private final CellStyle sensitiveHeader;
        private final CellStyle body;
        private final CellStyle sensitiveBody;

        private Styles(Workbook workbook) {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            border(header);

            sensitiveHeader = workbook.createCellStyle();
            sensitiveHeader.cloneStyleFrom(header);
            sensitiveHeader.setFillForegroundColor(IndexedColors.DARK_RED.getIndex());

            body = workbook.createCellStyle();
            body.setVerticalAlignment(VerticalAlignment.TOP);
            body.setWrapText(true);
            body.setDataFormat(workbook.createDataFormat().getFormat("@"));
            border(body);

            sensitiveBody = workbook.createCellStyle();
            sensitiveBody.cloneStyleFrom(body);
            sensitiveBody.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            sensitiveBody.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Font noticeFont = workbook.createFont();
            noticeFont.setBold(true);
            noticeFont.setColor(IndexedColors.DARK_RED.getIndex());
            notice = workbook.createCellStyle();
            notice.setFont(noticeFont);
            notice.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            notice.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            notice.setWrapText(true);
            notice.setVerticalAlignment(VerticalAlignment.CENTER);
        }

        private static void border(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
        }
    }
}
