package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 JDBC 元数据的原生 ER 关系图。
 */
public final class ErDiagramDialog extends JDialog {

    private final DesktopRuntime runtime;
    private final String connectionId;
    private final String schema;
    private final JLabel statusLabel = new JLabel("正在读取元数据…");
    private final GraphPanel graphPanel = new GraphPanel();
    private final AtomicBoolean loading = new AtomicBoolean();

    public ErDiagramDialog(JFrame owner, DesktopRuntime runtime,
            String connectionId, String schema) {
        super(owner, "ER 图 - " + (schema == null || schema.isBlank() ? "当前目录" : schema),
                false);
        this.runtime = runtime;
        this.connectionId = connectionId;
        this.schema = schema == null || schema.isBlank() ? null : schema.trim();
        buildUi();
        setMinimumSize(new Dimension(800, 600));
        setSize(1100, 760);
        setLocationRelativeTo(owner);
        load();
    }

    private void buildUi() {
        JButton export = UiKit.button("导出 PNG",
                LyraIcons.of(LyraIcons.Kind.EXPORT),
                UiKit.ButtonStyle.SECONDARY);
        export.addActionListener(event -> export());
        JButton refresh = UiKit.button("刷新",
                LyraIcons.of(LyraIcons.Kind.REFRESH),
                UiKit.ButtonStyle.SECONDARY);
        refresh.addActionListener(event -> load());
        JButton close = UiKit.button("关闭",
                LyraIcons.of(LyraIcons.Kind.CLOSE),
                UiKit.ButtonStyle.GHOST);
        close.addActionListener(event -> dispose());
        JPanel header = UiKit.glass(new BorderLayout(), 0);
        header.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 12));
        statusLabel.setFont(NativeTheme.FONT_CAPTION);
        header.add(statusLabel, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(refresh);
        buttons.add(export);
        buttons.add(close);
        header.add(buttons, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        JScrollPane scroll = UiKit.scroll(graphPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        getContentPane().setBackground(NativeTheme.BACKGROUND);
    }

    private void load() {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("正在读取 JDBC 表、字段与外键元数据…");
        new SwingWorker<SchemaGraph, Void>() {
            @Override
            protected SchemaGraph doInBackground() throws Exception {
                return runtime.connectionManager().withLockedJdbcConnection(
                        connectionId, jdbc -> inspect(jdbc, schema));
            }

            @Override
            protected void done() {
                try {
                    SchemaGraph graph = get();
                    graphPanel.setGraph(graph);
                    statusLabel.setForeground(NativeTheme.SUCCESS);
                    statusLabel.setText("已加载 " + graph.tables.size()
                            + " 张表、" + graph.relations.size() + " 条外键关系"
                            + (graph.truncated ? "（表数量已截断）" : ""));
                } catch (Exception exception) {
                    statusLabel.setForeground(NativeTheme.ERROR);
                    statusLabel.setText("ER 图加载失败");
                    JOptionPane.showMessageDialog(ErDiagramDialog.this,
                            rootCause(exception).getMessage(),
                            "ER 图错误", JOptionPane.ERROR_MESSAGE);
                } finally {
                    loading.set(false);
                }
            }
        }.execute();
    }

    static SchemaGraph inspect(Connection connection, String schema) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, TableNode> tables = new LinkedHashMap<>();
        boolean truncated = false;
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), schema, "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                if (tables.size() >= 60) {
                    truncated = true;
                    break;
                }
                String table = rs.getString("TABLE_NAME");
                String tableSchema = rs.getString("TABLE_SCHEM");
                Set<String> primaryKeys = new HashSet<>();
                try (ResultSet pk = metadata.getPrimaryKeys(
                        connection.getCatalog(), tableSchema, table)) {
                    while (pk.next()) {
                        String column = pk.getString("COLUMN_NAME");
                        if (column != null) {
                            primaryKeys.add(column.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                List<ColumnNode> columns = new ArrayList<>();
                try (ResultSet col = metadata.getColumns(
                        connection.getCatalog(), tableSchema, table, "%")) {
                    while (col.next() && columns.size() < 14) {
                        String name = col.getString("COLUMN_NAME");
                        columns.add(new ColumnNode(name, col.getString("TYPE_NAME"),
                                name != null && primaryKeys.contains(
                                        name.toLowerCase(Locale.ROOT))));
                    }
                }
                tables.put(key(tableSchema, table),
                        new TableNode(tableSchema, table, columns));
            }
        }

        List<Relation> relations = new ArrayList<>();
        for (TableNode table : tables.values()) {
            try (ResultSet keys = metadata.getImportedKeys(
                    connection.getCatalog(), table.schema, table.name)) {
                while (keys.next()) {
                    String from = key(keys.getString("FKTABLE_SCHEM"),
                            keys.getString("FKTABLE_NAME"));
                    String to = key(keys.getString("PKTABLE_SCHEM"),
                            keys.getString("PKTABLE_NAME"));
                    if (tables.containsKey(from) && tables.containsKey(to)) {
                        relations.add(new Relation(from, to,
                                keys.getString("FKCOLUMN_NAME"),
                                keys.getString("PKCOLUMN_NAME")));
                    }
                }
            }
        }
        return new SchemaGraph(List.copyOf(tables.values()),
                List.copyOf(relations), truncated);
    }

    private void export() {
        if (graphPanel.graph.tables.isEmpty()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("lyradb-er-diagram.png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            Dimension size = graphPanel.getPreferredSize();
            BufferedImage image = new BufferedImage(size.width, size.height,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphPanel.setSize(size);
                graphPanel.paint(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", target.toFile());
            statusLabel.setText("已导出：" + target.toAbsolutePath());
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "导出失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String key(String schema, String table) {
        return (schema == null ? "" : schema) + "." + table;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static final class GraphPanel extends JPanel {
        private static final int BOX_WIDTH = 276;
        private static final int HEADER_HEIGHT = 42;
        private static final int ROW_HEIGHT = 22;
        private static final int GAP_X = 100;
        private static final int GAP_Y = 70;
        private static final int MAX_BOX_HEIGHT = HEADER_HEIGHT + 14 * ROW_HEIGHT + 14;
        private SchemaGraph graph = new SchemaGraph(List.of(), List.of(), false);
        private final Map<String, java.awt.Rectangle> bounds = new LinkedHashMap<>();

        GraphPanel() {
            setBackground(NativeTheme.BACKGROUND);
        }

        void setGraph(SchemaGraph graph) {
            this.graph = graph;
            layoutGraph();
            revalidate();
            repaint();
        }

        private void layoutGraph() {
            bounds.clear();
            int columns = Math.max(1, Math.min(4,
                    (int) Math.ceil(Math.sqrt(Math.max(1, graph.tables.size())))));
            int maxX = 0;
            int maxY = 0;
            for (int i = 0; i < graph.tables.size(); i++) {
                TableNode table = graph.tables.get(i);
                int x = 40 + (i % columns) * (BOX_WIDTH + GAP_X);
                int y = 40 + (i / columns) * (MAX_BOX_HEIGHT + GAP_Y);
                java.awt.Rectangle rectangle =
                        new java.awt.Rectangle(x, y, BOX_WIDTH, boxHeight(table));
                bounds.put(key(table.schema, table.name), rectangle);
                maxX = Math.max(maxX, rectangle.x + rectangle.width + 40);
                maxY = Math.max(maxY, rectangle.y + rectangle.height + 40);
            }
            setPreferredSize(new Dimension(Math.max(900, maxX), Math.max(620, maxY)));
        }

        private static int boxHeight(TableNode table) {
            return HEADER_HEIGHT + Math.max(1, table.columns.size()) * ROW_HEIGHT + 12;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                paintStarfield(g);
                drawRelations(g);
                for (TableNode table : graph.tables) {
                    drawTable(g, table, bounds.get(key(table.schema, table.name)));
                }
            } finally {
                g.dispose();
            }
        }

        private void paintStarfield(Graphics2D g) {
            int width = Math.max(getWidth(), getPreferredSize().width);
            int height = Math.max(getHeight(), getPreferredSize().height);
            g.setColor(NativeTheme.BACKGROUND);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(NativeTheme.ACCENT_LIGHT.getRed(),
                    NativeTheme.ACCENT_LIGHT.getGreen(),
                    NativeTheme.ACCENT_LIGHT.getBlue(), 22));
            g.setStroke(new BasicStroke(0.7F));
            for (int x = 48; x < width; x += 156) {
                for (int y = 42; y < height; y += 128) {
                    int offset = ((x / 156) % 2) * 24;
                    g.fillOval(x, y + offset, 2, 2);
                    if (x + 156 < width) {
                        g.drawLine(x + 2, y + offset + 1,
                                x + 92, y + 36 - offset / 2);
                    }
                }
            }
        }

        private void drawRelations(Graphics2D g) {
            Color edge = new Color(NativeTheme.ACCENT_LIGHT.getRed(),
                    NativeTheme.ACCENT_LIGHT.getGreen(),
                    NativeTheme.ACCENT_LIGHT.getBlue(), 150);
            g.setColor(edge);
            g.setStroke(new BasicStroke(1.35F, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.setFont(getFont().deriveFont(Font.PLAIN, 10F));
            for (Relation relation : graph.relations) {
                java.awt.Rectangle from = bounds.get(relation.from);
                java.awt.Rectangle to = bounds.get(relation.to);
                if (from == null || to == null) {
                    continue;
                }
                if (from.equals(to)) {
                    int x = from.x + from.width;
                    int y = from.y + from.height / 2;
                    Path2D loop = new Path2D.Double();
                    loop.moveTo(x, y - 10);
                    loop.lineTo(x + 30, y - 10);
                    loop.lineTo(x + 30, y + 22);
                    loop.lineTo(x, y + 22);
                    g.draw(loop);
                    drawArrowHead(g, x, y + 22, -1, edge);
                    drawRelationLabel(g, relation, x + 7, y + 6);
                    continue;
                }
                boolean targetOnRight = to.getCenterX() >= from.getCenterX();
                int direction = targetOnRight ? 1 : -1;
                int x1 = targetOnRight ? from.x + from.width : from.x;
                int y1 = from.y + from.height / 2;
                int x2 = targetOnRight ? to.x : to.x + to.width;
                int y2 = to.y + to.height / 2;
                int middleX = (x1 + x2) / 2;
                Path2D path = new Path2D.Double();
                path.moveTo(x1, y1);
                path.lineTo(middleX, y1);
                path.lineTo(middleX, y2);
                path.lineTo(x2, y2);
                g.draw(path);
                drawArrowHead(g, x2, y2, direction, edge);
                drawRelationLabel(g, relation, middleX + 6, (y1 + y2) / 2);
            }
        }

        private void drawArrowHead(Graphics2D g, int x, int y,
                int direction, Color edge) {
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(x, y);
            arrow.lineTo(x - direction * 7, y - 4);
            arrow.lineTo(x - direction * 7, y + 4);
            arrow.closePath();
            g.setColor(edge);
            g.fill(arrow);
        }

        private void drawRelationLabel(
                Graphics2D g, Relation relation, int x, int y) {
            String label = relation.fromColumn + " → " + relation.toColumn;
            int width = g.getFontMetrics().stringWidth(label) + 10;
            g.setColor(NativeTheme.SURFACE);
            g.fillRoundRect(x - 3, y - 11, width, 16, 7, 7);
            g.setColor(NativeTheme.MUTED);
            g.drawString(label, x + 2, y);
        }

        private void drawTable(Graphics2D g, TableNode table,
                java.awt.Rectangle rectangle) {
            if (rectangle == null) {
                return;
            }
            g.setColor(new Color(NativeTheme.SHADOW.getRed(),
                    NativeTheme.SHADOW.getGreen(),
                    NativeTheme.SHADOW.getBlue(), 72));
            g.fillRoundRect(rectangle.x + 3, rectangle.y + 5,
                    rectangle.width, rectangle.height, 14, 14);
            g.setColor(NativeTheme.SURFACE);
            g.fillRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    rectangle.height, 14, 14);
            g.setColor(NativeTheme.SURFACE_ALT);
            g.fillRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    HEADER_HEIGHT, 14, 14);
            g.fillRect(rectangle.x, rectangle.y + HEADER_HEIGHT - 12,
                    rectangle.width, 12);
            g.setColor(NativeTheme.ACCENT);
            g.fillRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    3, 14, 14);

            LyraIcons.of(LyraIcons.Kind.TABLE, 18, NativeTheme.ACCENT_LIGHT)
                    .paintIcon(this, g, rectangle.x + 12, rectangle.y + 12);
            g.setFont(getFont().deriveFont(Font.BOLD, 13F));
            g.setColor(NativeTheme.FOREGROUND);
            g.drawString(clipText(g, table.name, BOX_WIDTH - 54),
                    rectangle.x + 39, rectangle.y + 19);
            g.setFont(getFont().deriveFont(Font.PLAIN, 10F));
            g.setColor(NativeTheme.MUTED);
            String namespace = table.schema == null || table.schema.isBlank()
                    ? "TABLE" : table.schema;
            g.drawString(clipText(g, namespace, BOX_WIDTH - 54),
                    rectangle.x + 39, rectangle.y + 34);

            int y = rectangle.y + HEADER_HEIGHT;
            if (table.columns.isEmpty()) {
                g.setFont(getFont().deriveFont(Font.PLAIN, 11F));
                g.setColor(NativeTheme.MUTED);
                g.drawString("无可见字段", rectangle.x + 12, y + 16);
            } else {
                for (int index = 0; index < table.columns.size(); index++) {
                    ColumnNode column = table.columns.get(index);
                    if (index % 2 == 1) {
                        g.setColor(NativeTheme.TABLE_ALT);
                        g.fillRect(rectangle.x + 1, y,
                                rectangle.width - 2, ROW_HEIGHT);
                    }
                    if (column.primaryKey) {
                        g.setColor(new Color(NativeTheme.WARNING.getRed(),
                                NativeTheme.WARNING.getGreen(),
                                NativeTheme.WARNING.getBlue(), 42));
                        g.fillRoundRect(rectangle.x + 10, y + 4, 25, 14, 7, 7);
                        g.setFont(getFont().deriveFont(Font.BOLD, 9F));
                        g.setColor(NativeTheme.WARNING);
                        g.drawString("PK", rectangle.x + 16, y + 15);
                    } else {
                        LyraIcons.of(LyraIcons.Kind.COLUMN, 12, NativeTheme.MUTED)
                                .paintIcon(this, g, rectangle.x + 16, y + 5);
                    }
                    g.setFont(getFont().deriveFont(Font.PLAIN, 11F));
                    g.setColor(NativeTheme.FOREGROUND);
                    g.drawString(clipText(g, column.name, 130),
                            rectangle.x + 43, y + 15);
                    String type = column.typeName == null ? "" : column.typeName;
                    g.setFont(getFont().deriveFont(Font.PLAIN, 10F));
                    g.setColor(NativeTheme.MUTED);
                    String clippedType = clipText(g, type, 72);
                    int typeWidth = g.getFontMetrics().stringWidth(clippedType);
                    g.drawString(clippedType,
                            rectangle.x + rectangle.width - typeWidth - 11, y + 15);
                    y += ROW_HEIGHT;
                }
            }
            g.setColor(NativeTheme.BORDER);
            g.setStroke(new BasicStroke(1F));
            g.drawRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    rectangle.height, 14, 14);
        }

        private static String clipText(Graphics2D g, String value, int width) {
            String text = value == null ? "" : value;
            if (g.getFontMetrics().stringWidth(text) <= width) {
                return text;
            }
            String suffix = "…";
            while (!text.isEmpty()
                    && g.getFontMetrics().stringWidth(text + suffix) > width) {
                text = text.substring(0, text.length() - 1);
            }
            return text + suffix;
        }
    }

    record TableNode(String schema, String name, List<ColumnNode> columns) {
    }

    record ColumnNode(String name, String typeName, boolean primaryKey) {
    }

    record Relation(String from, String to, String fromColumn, String toColumn) {
    }

    record SchemaGraph(List<TableNode> tables, List<Relation> relations,
                       boolean truncated) {
    }
}
