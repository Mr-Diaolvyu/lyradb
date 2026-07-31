package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * 使用透明叠层、高光和细描边模拟磨砂玻璃；不依赖平台私有模糊 API。
 */
final class GlassPanel extends JPanel {

    private final int arc;

    GlassPanel(LayoutManager layout, int arc) {
        super(layout);
        this.arc = Math.max(0, arc);
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            if (width <= 1 || height <= 1) {
                return;
            }

            Shape shadow = new RoundRectangle2D.Float(
                    1, 2, width - 2F, height - 3F, arc, arc);
            g.setColor(NativeTheme.SHADOW);
            g.fill(shadow);

            Shape glass = new RoundRectangle2D.Float(
                    0.5F, 0.5F, width - 1F, height - 2F, arc, arc);
            g.setPaint(new LinearGradientPaint(
                    0, 0, 0, Math.max(1, height),
                    new float[] {0F, 0.38F, 1F},
                    new Color[] {
                            NativeTheme.GLASS_SURFACE,
                            NativeTheme.GLASS_SURFACE,
                            NativeTheme.SURFACE
                    }));
            g.fill(glass);
            g.setColor(NativeTheme.GLASS_HIGHLIGHT);
            g.drawLine(Math.max(arc / 2, 4), 1,
                    Math.max(arc / 2, width - arc / 2), 1);
            g.setColor(NativeTheme.GLASS_BORDER);
            g.setStroke(new BasicStroke(1F));
            g.draw(glass);
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }
}

/**
 * 星空画布：用少量恒星、星轨与环境光建立产品识别，不干扰数据内容。
 */
final class AuroraPanel extends JPanel {

    AuroraPanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(NativeTheme.BACKGROUND);
            g.fillRect(0, 0, getWidth(), getHeight());
            paintAmbient(g);
            paintConstellation(g);
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }

    private void paintAmbient(Graphics2D g) {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        g.setPaint(new LinearGradientPaint(
                0, 0, width, height,
                new float[] {0F, 0.52F, 1F},
                new Color[] {
                        NativeTheme.AURORA_PRIMARY,
                        new Color(NativeTheme.BACKGROUND.getRed(),
                                NativeTheme.BACKGROUND.getGreen(),
                                NativeTheme.BACKGROUND.getBlue(), 0),
                        NativeTheme.AURORA_SECONDARY
                }));
        g.fillRect(0, 0, width, height);
    }

    private void paintConstellation(Graphics2D g) {
        int width = getWidth();
        int height = getHeight();
        if (width < 120 || height < 120) {
            return;
        }
        double[][] stars = {
                {0.08, 0.12, 1.2}, {0.16, 0.22, 1.7}, {0.26, 0.10, 1.0},
                {0.38, 0.18, 1.4}, {0.51, 0.08, 1.1}, {0.63, 0.25, 1.6},
                {0.74, 0.13, 1.0}, {0.88, 0.20, 1.5}, {0.94, 0.08, 1.0},
                {0.20, 0.74, 1.0}, {0.44, 0.82, 1.3}, {0.78, 0.76, 1.1}
        };
        g.setStroke(new BasicStroke(0.8F));
        g.setColor(new Color(NativeTheme.ACCENT_LIGHT.getRed(),
                NativeTheme.ACCENT_LIGHT.getGreen(),
                NativeTheme.ACCENT_LIGHT.getBlue(), 24));
        int[] chain = {0, 1, 3, 5, 7, 8};
        for (int i = 1; i < chain.length; i++) {
            double[] from = stars[chain[i - 1]];
            double[] to = stars[chain[i]];
            g.drawLine((int) (from[0] * width), (int) (from[1] * height),
                    (int) (to[0] * width), (int) (to[1] * height));
        }
        g.setColor(new Color(NativeTheme.FOREGROUND.getRed(),
                NativeTheme.FOREGROUND.getGreen(),
                NativeTheme.FOREGROUND.getBlue(), 105));
        for (double[] star : stars) {
            int radius = Math.max(1, (int) Math.round(star[2]));
            int x = (int) (star[0] * width);
            int y = (int) (star[1] * height);
            g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }
}
