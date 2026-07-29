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
        REFRESH,
        AI,
        SETTINGS,
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
        CLOSE
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

    public static Image applicationImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(NativeTheme.ACCENT);
            graphics.fillRoundRect(4, 4, 56, 56, 16, 16);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke(3.5F));
            graphics.drawOval(16, 15, 32, 12);
            graphics.drawArc(16, 24, 32, 12, 180, 180);
            graphics.drawArc(16, 34, 32, 12, 180, 180);
            graphics.drawLine(16, 21, 16, 40);
            graphics.drawLine(48, 21, 48, 40);
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
}
