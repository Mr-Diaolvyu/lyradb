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
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        JButton export = new JButton("导出 PNG");
        export.addActionListener(event -> export());
        JButton refresh = new JButton("刷新");
        refresh.addActionListener(event -> load());
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        header.add(statusLabel, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(refresh);
        buttons.add(export);
        buttons.add(close);
        header.add(buttons, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(graphPanel), BorderLayout.CENTER);
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
                List<String> columns = new ArrayList<>();
                try (ResultSet col = metadata.getColumns(
                        connection.getCatalog(), tableSchema, table, "%")) {
                    while (col.next() && columns.size() < 14) {
                        columns.add(col.getString("COLUMN_NAME")
                                + " : " + col.getString("TYPE_NAME"));
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
        private static final int BOX_WIDTH = 240;
        private static final int HEADER_HEIGHT = 30;
        private static final int ROW_HEIGHT = 19;
        private static final int GAP_X = 70;
        private static final int GAP_Y = 45;
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
                int x = 30 + (i % columns) * (BOX_WIDTH + GAP_X);
                int y = 30 + (i / columns) * (boxHeight(table) + GAP_Y);
                java.awt.Rectangle rectangle =
                        new java.awt.Rectangle(x, y, BOX_WIDTH, boxHeight(table));
                bounds.put(key(table.schema, table.name), rectangle);
                maxX = Math.max(maxX, rectangle.x + rectangle.width + 30);
                maxY = Math.max(maxY, rectangle.y + rectangle.height + 30);
            }
            setPreferredSize(new Dimension(Math.max(760, maxX), Math.max(520, maxY)));
        }

        private static int boxHeight(TableNode table) {
            return HEADER_HEIGHT + Math.max(1, table.columns.size()) * ROW_HEIGHT + 10;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                drawRelations(g);
                for (TableNode table : graph.tables) {
                    drawTable(g, table, bounds.get(key(table.schema, table.name)));
                }
            } finally {
                g.dispose();
            }
        }

        private void drawRelations(Graphics2D g) {
            g.setColor(NativeTheme.WARNING);
            g.setStroke(new BasicStroke(1.4F));
            for (Relation relation : graph.relations) {
                java.awt.Rectangle from = bounds.get(relation.from);
                java.awt.Rectangle to = bounds.get(relation.to);
                if (from == null || to == null) {
                    continue;
                }
                int x1 = from.x + from.width / 2;
                int y1 = from.y + from.height / 2;
                int x2 = to.x + to.width / 2;
                int y2 = to.y + to.height / 2;
                g.drawLine(x1, y1, x2, y2);
                g.fillOval(x2 - 4, y2 - 4, 8, 8);
            }
        }

        private void drawTable(Graphics2D g, TableNode table,
                java.awt.Rectangle rectangle) {
            if (rectangle == null) {
                return;
            }
            g.setColor(NativeTheme.SURFACE);
            g.fillRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    rectangle.height, 10, 10);
            g.setColor(NativeTheme.ACCENT);
            g.fillRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    HEADER_HEIGHT, 10, 10);
            g.fillRect(rectangle.x, rectangle.y + HEADER_HEIGHT - 10,
                    rectangle.width, 10);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 13F));
            String title = table.schema == null || table.schema.isBlank()
                    ? table.name : table.schema + "." + table.name;
            g.drawString(title, rectangle.x + 10, rectangle.y + 20);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12F));
            g.setColor(NativeTheme.FOREGROUND);
            int y = rectangle.y + HEADER_HEIGHT + 17;
            if (table.columns.isEmpty()) {
                g.setColor(NativeTheme.MUTED);
                g.drawString("（无可见字段）", rectangle.x + 10, y);
            } else {
                for (String column : table.columns) {
                    g.drawString(column, rectangle.x + 10, y);
                    y += ROW_HEIGHT;
                }
            }
            g.setColor(NativeTheme.SURFACE_ALT.brighter());
            g.drawRoundRect(rectangle.x, rectangle.y, rectangle.width,
                    rectangle.height, 10, 10);
        }
    }

    record TableNode(String schema, String name, List<String> columns) {
    }

    record Relation(String from, String to, String fromColumn, String toColumn) {
    }

    record SchemaGraph(List<TableNode> tables, List<Relation> relations,
                       boolean truncated) {
    }
}
