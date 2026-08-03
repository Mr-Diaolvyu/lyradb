package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;

/**
 * LyraDB 的统一线性图标集。
 *
 * <p>图标在运行时以矢量方式绘制，避免系统 Emoji 和低分辨率位图造成的
 * 平台差异，并能跟随按钮前景色与禁用状态。</p>
 */
public final class LyraIcons {

    public enum Kind {
        ADD_DATABASE,
        CONNECT,
        DISCONNECT,
        SQL,
        FORMAT,
        WORKSPACE,
        FIT,
        ZOOM_IN,
        ZOOM_OUT,
        REFRESH,
        AI,
        SETTINGS,
        THEME,
        ER,
        DATABASE,
        SCHEMA,
        TABLE,
        VIEW,
        ROUTINE,
        TRIGGER,
        COLLECTION,
        KEY,
        COLUMN,
        SHIELD,
        COPY,
        EXPORT,
        PLAY,
        CLOSE,
        SEARCH,
        MORE,
        INDEX,
        PARTITION,
        BRAND
    }

    private LyraIcons() {
    }

    public static Icon of(Kind kind) {
        return new VectorIcon(kind, 16, null);
    }

    public static Icon of(Kind kind, Color color) {
        return new VectorIcon(kind, 16, color);
    }

    public static Icon of(Kind kind, int size, Color color) {
        return new VectorIcon(kind, size, color);
    }

    public static Icon databaseEngine(
            String dbType, int size, boolean connected) {
        return new EngineIcon(dbType, size, connected);
    }

    public static Icon treeNode(
            String type, Map<String, Object> properties, int size) {
        String normalized = type == null ? ""
                : type.trim().toUpperCase(Locale.ROOT);
        boolean primaryKey = properties != null
                && Boolean.TRUE.equals(properties.get("primaryKey"));
        Kind kind = primaryKey ? Kind.KEY : switch (normalized) {
            case "DATABASE" -> Kind.DATABASE;
            case "SCHEMA" -> Kind.SCHEMA;
            case "TABLE" -> Kind.TABLE;
            case "VIEW" -> Kind.VIEW;
            case "PROCEDURE", "FUNCTION", "ROUTINE" -> Kind.ROUTINE;
            case "TRIGGER" -> Kind.TRIGGER;
            case "COLLECTION" -> Kind.COLLECTION;
            case "KEY", "KEY_GROUP" -> Kind.KEY;
            case "INDEX", "INDEX_GROUP" -> Kind.INDEX;
            case "PARTITION" -> Kind.PARTITION;
            case "COLUMN" -> Kind.COLUMN;
            default -> Kind.DATABASE;
        };
        Color color = switch (normalized) {
            case "TABLE", "COLUMN" -> NativeTheme.ACCENT_LIGHT;
            case "VIEW" -> NativeTheme.SUCCESS;
            case "KEY", "KEY_GROUP", "INDEX", "INDEX_GROUP" -> NativeTheme.WARNING;
            case "TRIGGER" -> NativeTheme.ERROR;
            default -> NativeTheme.MUTED;
        };
        return of(kind, size, color);
    }

    public static Image applicationImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            new BrandIcon(64).paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static final class VectorIcon implements Icon {
        private final Kind kind;
        private final int size;
        private final Color color;

        private VectorIcon(Kind kind, int size, Color color) {
            this.kind = kind;
            this.size = size;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(size / 16D, size / 16D);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color resolved = color != null ? color
                        : component != null && component.isEnabled()
                        ? component.getForeground() : NativeTheme.MUTED;
                g.setColor(resolved);
                g.setStroke(new BasicStroke(1.55F, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                draw(g);
            } finally {
                g.dispose();
            }
        }

        private void draw(Graphics2D g) {
            switch (kind) {
                case ADD_DATABASE -> {
                    database(g, 1.5, 2, 9.5, 11);
                    g.draw(new Line2D.Double(12.5, 8.5, 12.5, 14.5));
                    g.draw(new Line2D.Double(9.5, 11.5, 15.5, 11.5));
                }
                case CONNECT -> {
                    g.draw(new Arc2D.Double(2, 3, 7, 7, 45, 220, Arc2D.OPEN));
                    g.draw(new Arc2D.Double(7, 6, 7, 7, 225, 220, Arc2D.OPEN));
                    g.draw(new Line2D.Double(6, 10, 10, 6));
                }
                case DISCONNECT -> {
                    g.draw(new Arc2D.Double(1.5, 3, 7, 7, 45, 160, Arc2D.OPEN));
                    g.draw(new Arc2D.Double(7.5, 6, 7, 7, 225, 160, Arc2D.OPEN));
                    g.draw(new Line2D.Double(3, 13, 13, 3));
                }
                case SQL -> {
                    Path2D path = new Path2D.Double();
                    path.moveTo(2, 4);
                    path.lineTo(6, 8);
                    path.lineTo(2, 12);
                    g.draw(path);
                    g.draw(new Line2D.Double(8, 12, 14, 12));
                }
                case FORMAT -> {
                    g.draw(new Line2D.Double(2, 3, 12.5, 3));
                    g.draw(new Line2D.Double(5, 7, 14, 7));
                    g.draw(new Line2D.Double(5, 11, 12, 11));
                    g.draw(new Line2D.Double(2, 14, 9.5, 14));
                    Path2D indent = new Path2D.Double();
                    indent.moveTo(1.5, 6);
                    indent.lineTo(4, 8.5);
                    indent.lineTo(1.5, 11);
                    g.draw(indent);
                }
                case WORKSPACE -> {
                    g.draw(new Rectangle2D.Double(1.5, 2, 13, 12));
                    g.draw(new Line2D.Double(1.5, 5.5, 14.5, 5.5));
                    g.draw(new Line2D.Double(6, 5.5, 6, 14));
                    g.fill(new Ellipse2D.Double(3, 3.1, 1.2, 1.2));
                }
                case FIT -> {
                    g.draw(new Line2D.Double(2, 6, 2, 2));
                    g.draw(new Line2D.Double(2, 2, 6, 2));
                    g.draw(new Line2D.Double(10, 2, 14, 2));
                    g.draw(new Line2D.Double(14, 2, 14, 6));
                    g.draw(new Line2D.Double(14, 10, 14, 14));
                    g.draw(new Line2D.Double(14, 14, 10, 14));
                    g.draw(new Line2D.Double(6, 14, 2, 14));
                    g.draw(new Line2D.Double(2, 14, 2, 10));
                }
                case ZOOM_IN, ZOOM_OUT -> {
                    g.draw(new Ellipse2D.Double(2, 2, 8.5, 8.5));
                    g.draw(new Line2D.Double(9.2, 9.2, 14.2, 14.2));
                    g.draw(new Line2D.Double(4.3, 6.2, 8.2, 6.2));
                    if (kind == Kind.ZOOM_IN) {
                        g.draw(new Line2D.Double(6.25, 4.25, 6.25, 8.15));
                    }
                }
                case REFRESH -> {
                    g.draw(new Arc2D.Double(2, 2, 12, 12, 35, 285, Arc2D.OPEN));
                    g.draw(new Line2D.Double(11.5, 1.5, 14, 4));
                    g.draw(new Line2D.Double(14, 4, 10.5, 4.3));
                }
                case AI -> {
                    Path2D star = new Path2D.Double();
                    star.moveTo(8, 1.5);
                    star.lineTo(9.3, 6.7);
                    star.lineTo(14.5, 8);
                    star.lineTo(9.3, 9.3);
                    star.lineTo(8, 14.5);
                    star.lineTo(6.7, 9.3);
                    star.lineTo(1.5, 8);
                    star.lineTo(6.7, 6.7);
                    star.closePath();
                    g.draw(star);
                }
                case THEME -> {
                    g.draw(new Ellipse2D.Double(2, 2, 12, 12));
                    g.fill(new Arc2D.Double(4, 4, 8, 8, 90, 180, Arc2D.PIE));
                }
                case SETTINGS -> {
                    g.draw(new Ellipse2D.Double(5.5, 5.5, 5, 5));
                    g.draw(new Ellipse2D.Double(2.2, 2.2, 11.6, 11.6));
                    for (int i = 0; i < 8; i++) {
                        double angle = Math.PI * i / 4D;
                        double x1 = 8 + Math.cos(angle) * 5.7;
                        double y1 = 8 + Math.sin(angle) * 5.7;
                        double x2 = 8 + Math.cos(angle) * 7;
                        double y2 = 8 + Math.sin(angle) * 7;
                        g.draw(new Line2D.Double(x1, y1, x2, y2));
                    }
                }
                case ER -> {
                    box(g, 1, 2, 5, 4);
                    box(g, 10, 2, 5, 4);
                    box(g, 5.5, 10, 5, 4);
                    g.draw(new Line2D.Double(6, 4, 10, 4));
                    g.draw(new Line2D.Double(3.5, 6, 7, 10));
                    g.draw(new Line2D.Double(12.5, 6, 9, 10));
                }
                case DATABASE -> database(g, 2, 2, 12, 12);
                case SCHEMA -> {
                    g.draw(new Rectangle2D.Double(2, 3, 12, 9));
                    g.draw(new Line2D.Double(5, 3, 5, 12));
                    g.draw(new Line2D.Double(5, 6, 14, 6));
                }
                case TABLE -> {
                    g.draw(new Rectangle2D.Double(1.5, 2, 13, 12));
                    g.draw(new Line2D.Double(1.5, 6, 14.5, 6));
                    g.draw(new Line2D.Double(6, 2, 6, 14));
                    g.draw(new Line2D.Double(10, 2, 10, 14));
                }
                case VIEW -> {
                    Path2D eye = new Path2D.Double();
                    eye.moveTo(1.5, 8);
                    eye.curveTo(4, 3.5, 12, 3.5, 14.5, 8);
                    eye.curveTo(12, 12.5, 4, 12.5, 1.5, 8);
                    g.draw(eye);
                    g.draw(new Ellipse2D.Double(6, 6, 4, 4));
                }
                case ROUTINE -> {
                    g.draw(new Line2D.Double(5, 2, 2, 5));
                    g.draw(new Line2D.Double(2, 5, 5, 8));
                    g.draw(new Line2D.Double(11, 8, 14, 11));
                    g.draw(new Line2D.Double(14, 11, 11, 14));
                    g.draw(new Line2D.Double(9.5, 2, 6.5, 14));
                }
                case TRIGGER -> {
                    Path2D bolt = new Path2D.Double();
                    bolt.moveTo(9, 1);
                    bolt.lineTo(3.5, 9);
                    bolt.lineTo(8, 9);
                    bolt.lineTo(7, 15);
                    bolt.lineTo(13, 6.5);
                    bolt.lineTo(8.5, 6.5);
                    bolt.closePath();
                    g.draw(bolt);
                }
                case COLLECTION -> {
                    box(g, 3, 2, 10, 9);
                    box(g, 1, 5, 10, 9);
                }
                case KEY -> {
                    g.draw(new Ellipse2D.Double(1.5, 2.5, 6, 6));
                    g.draw(new Line2D.Double(6.5, 7.5, 14, 15));
                    g.draw(new Line2D.Double(11, 12, 13, 10));
                }
                case COLUMN -> {
                    g.draw(new Rectangle2D.Double(3, 2, 10, 12));
                    g.draw(new Line2D.Double(6, 2, 6, 14));
                    g.draw(new Line2D.Double(10, 2, 10, 14));
                }
                case SHIELD -> {
                    Path2D shield = new Path2D.Double();
                    shield.moveTo(8, 1.5);
                    shield.lineTo(13.5, 3.5);
                    shield.lineTo(12.5, 10.5);
                    shield.curveTo(11.8, 13, 9.6, 14.3, 8, 15);
                    shield.curveTo(6.4, 14.3, 4.2, 13, 3.5, 10.5);
                    shield.lineTo(2.5, 3.5);
                    shield.closePath();
                    g.draw(shield);
                    g.draw(new Line2D.Double(5.5, 8, 7.3, 10));
                    g.draw(new Line2D.Double(7.3, 10, 11, 6));
                }
                case COPY -> {
                    g.draw(new Rectangle2D.Double(5, 5, 9, 9));
                    g.draw(new Rectangle2D.Double(2, 2, 9, 9));
                }
                case EXPORT -> {
                    g.draw(new Rectangle2D.Double(2, 7, 12, 7));
                    g.draw(new Line2D.Double(8, 1.5, 8, 10));
                    g.draw(new Line2D.Double(4.5, 5, 8, 1.5));
                    g.draw(new Line2D.Double(11.5, 5, 8, 1.5));
                }
                case PLAY -> {
                    Path2D play = new Path2D.Double();
                    play.moveTo(4, 2);
                    play.lineTo(14, 8);
                    play.lineTo(4, 14);
                    play.closePath();
                    g.draw(play);
                }
                case CLOSE -> {
                    g.draw(new Line2D.Double(3, 3, 13, 13));
                    g.draw(new Line2D.Double(13, 3, 3, 13));
                }
                case SEARCH -> {
                    g.draw(new Ellipse2D.Double(2, 2, 8.5, 8.5));
                    g.draw(new Line2D.Double(9.2, 9.2, 14.2, 14.2));
                }
                case MORE -> {
                    g.fill(new Ellipse2D.Double(2, 6.7, 2.2, 2.2));
                    g.fill(new Ellipse2D.Double(6.9, 6.7, 2.2, 2.2));
                    g.fill(new Ellipse2D.Double(11.8, 6.7, 2.2, 2.2));
                }
                case INDEX -> {
                    g.draw(new Line2D.Double(2.5, 4, 13.5, 4));
                    g.draw(new Line2D.Double(2.5, 8, 10.5, 8));
                    g.draw(new Line2D.Double(2.5, 12, 7.5, 12));
                    g.fill(new Ellipse2D.Double(12.2, 2.7, 2.6, 2.6));
                }
                case PARTITION -> {
                    g.draw(new Rectangle2D.Double(2, 2, 12, 12));
                    g.draw(new Line2D.Double(8, 2, 8, 14));
                    g.draw(new Line2D.Double(2, 8, 8, 8));
                }
                case BRAND -> {
                    Path2D lyra = new Path2D.Double();
                    lyra.moveTo(2.5, 4);
                    lyra.lineTo(7.5, 2.2);
                    lyra.lineTo(13.5, 5.5);
                    lyra.lineTo(11.5, 12.8);
                    lyra.lineTo(5.2, 13.8);
                    lyra.closePath();
                    g.draw(lyra);
                    g.fill(new Ellipse2D.Double(1.3, 2.8, 2.4, 2.4));
                    g.fill(new Ellipse2D.Double(6.3, 1, 2.4, 2.4));
                    g.fill(new Ellipse2D.Double(12.2, 4.3, 2.4, 2.4));
                    database(g, 5.3, 6, 5.4, 6.2);
                }
            }
        }

        private static void database(Graphics2D g, double x, double y,
                double width, double height) {
            g.draw(new Ellipse2D.Double(x, y, width, 4));
            g.draw(new Arc2D.Double(x, y + height - 4, width, 4,
                    180, 180, Arc2D.OPEN));
            g.draw(new Arc2D.Double(x, y + height / 2D - 2, width, 4,
                    180, 180, Arc2D.OPEN));
            g.draw(new Line2D.Double(x, y + 2, x, y + height - 2));
            g.draw(new Line2D.Double(x + width, y + 2,
                    x + width, y + height - 2));
        }

        private static void box(Graphics2D g, double x, double y,
                double width, double height) {
            g.draw(new Rectangle2D.Double(x, y, width, height));
        }
    }

    private static final class EngineIcon implements Icon {
        private final String dbType;
        private final int size;
        private final boolean connected;

        private EngineIcon(String dbType, int size, boolean connected) {
            this.dbType = dbType == null ? "" : dbType.toUpperCase(Locale.ROOT);
            this.size = size;
            this.connected = connected;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(size / 20D, size / 20D);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(engineColor(dbType));
                g.fillRoundRect(1, 1, 18, 18, 6, 6);
                g.setColor(new Color(255, 255, 255, 230));
                g.setStroke(new BasicStroke(1.45F, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                drawEngineGlyph(g, dbType);
                g.setColor(connected ? NativeTheme.SUCCESS : NativeTheme.MUTED);
                g.fillOval(14, 14, 5, 5);
                g.setColor(NativeTheme.SURFACE);
                g.setStroke(new BasicStroke(1.1F));
                g.drawOval(14, 14, 5, 5);
            } finally {
                g.dispose();
            }
        }

        private static void drawEngineGlyph(Graphics2D g, String type) {
            switch (type) {
                case "MAXCOMPUTE" -> {
                    g.draw(new Line2D.Double(5, 10, 10, 5));
                    g.draw(new Line2D.Double(10, 5, 15, 10));
                    g.draw(new Line2D.Double(15, 10, 10, 14));
                    g.draw(new Line2D.Double(10, 14, 5, 10));
                    g.fill(new Ellipse2D.Double(8.5, 8.5, 3, 3));
                }
                case "CLICKHOUSE" -> {
                    g.fill(new Rectangle2D.Double(5, 5, 1.7, 10));
                    g.fill(new Rectangle2D.Double(8.1, 7, 1.7, 8));
                    g.fill(new Rectangle2D.Double(11.2, 4, 1.7, 11));
                    g.fill(new Rectangle2D.Double(14.3, 8.5, 1.7, 6.5));
                }
                case "MONGODB" -> {
                    Path2D leaf = new Path2D.Double();
                    leaf.moveTo(10, 4);
                    leaf.curveTo(5.5, 7, 6, 12.5, 10, 16);
                    leaf.curveTo(14, 12.5, 14.5, 7, 10, 4);
                    leaf.closePath();
                    g.draw(leaf);
                    g.draw(new Line2D.Double(10, 7, 10, 16));
                }
                case "REDIS" -> {
                    g.draw(new Rectangle2D.Double(5, 5, 10, 3));
                    g.draw(new Rectangle2D.Double(5, 10, 10, 3));
                    g.draw(new Line2D.Double(7, 15, 13, 15));
                }
                case "ORACLE" -> g.draw(new Ellipse2D.Double(4.5, 6.2, 11, 7.5));
                case "MSSQL" -> {
                    g.draw(new Arc2D.Double(4, 4, 12, 6, 5, 170, Arc2D.OPEN));
                    g.draw(new Arc2D.Double(4, 7, 12, 6, 185, 170, Arc2D.OPEN));
                    g.draw(new Arc2D.Double(4, 10, 12, 6, 5, 170, Arc2D.OPEN));
                }
                case "POSTGRESQL" -> {
                    g.draw(new Ellipse2D.Double(5, 4, 10, 10));
                    g.draw(new Line2D.Double(10, 9, 10, 16));
                    g.draw(new Arc2D.Double(8, 10, 6, 5, 180, 170, Arc2D.OPEN));
                }
                default -> {
                    g.draw(new Ellipse2D.Double(5, 4.5, 10, 4));
                    g.draw(new Arc2D.Double(5, 10.5, 10, 4,
                            180, 180, Arc2D.OPEN));
                    g.draw(new Line2D.Double(5, 6.5, 5, 12.5));
                    g.draw(new Line2D.Double(15, 6.5, 15, 12.5));
                }
            }
        }

        private static Color engineColor(String type) {
            return switch (type) {
                case "MYSQL" -> new Color(54, 126, 160);
                case "POSTGRESQL" -> new Color(51, 103, 145);
                case "ORACLE" -> new Color(193, 67, 52);
                case "MSSQL" -> new Color(184, 53, 56);
                case "SQLITE" -> new Color(43, 105, 128);
                case "CLICKHOUSE" -> new Color(170, 132, 28);
                case "MAXCOMPUTE" -> new Color(218, 98, 51);
                case "MONGODB" -> new Color(58, 143, 75);
                case "REDIS" -> new Color(190, 62, 54);
                default -> NativeTheme.ACCENT_STRONG;
            };
        }
    }

    private static final class BrandIcon implements Icon {
        private final int size;

        private BrandIcon(int size) {
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(size / 64D, size / 64D);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(16, 20, 32));
                g.fillRoundRect(3, 3, 58, 58, 17, 17);
                g.setColor(new Color(151, 135, 255));
                g.setStroke(new BasicStroke(2.4F, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.drawLine(15, 18, 31, 12);
                g.drawLine(31, 12, 49, 24);
                g.drawLine(49, 24, 42, 45);
                g.drawLine(42, 45, 20, 48);
                g.drawLine(20, 48, 15, 18);
                int[][] stars = {{15, 18}, {31, 12}, {49, 24}, {42, 45}, {20, 48}};
                for (int[] star : stars) {
                    g.fillOval(star[0] - 2, star[1] - 2, 4, 4);
                }
                g.setColor(new Color(240, 238, 255));
                g.drawOval(23, 25, 18, 6);
                g.drawArc(23, 31, 18, 6, 180, 180);
                g.drawLine(23, 28, 23, 34);
                g.drawLine(41, 28, 41, 34);
            } finally {
                g.dispose();
            }
        }
    }
}
