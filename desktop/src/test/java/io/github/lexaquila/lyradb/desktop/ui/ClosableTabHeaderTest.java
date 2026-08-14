package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClosableTabHeaderTest {

    @Test
    void shouldExposeAccessibleCloseAction() {
        JTabbedPane tabs = new JTabbedPane();
        JPanel content = new JPanel();
        tabs.addTab("订单表", content);
        AtomicInteger closes = new AtomicInteger();
        ClosableTabHeader header = new ClosableTabHeader(
                tabs, content, "订单表", null, closes::incrementAndGet);

        header.closeButton().doClick();

        assertThat(closes).hasValue(1);
        assertThat(header.closeButton().getToolTipText())
                .contains("Ctrl+W");
        assertThat(header.closeButton().getAccessibleContext()
                .getAccessibleName()).contains("订单表");
    }

    @Test
    void shouldSelectContentWhenCustomHeaderIsClicked() {
        JTabbedPane tabs = new JTabbedPane();
        JPanel first = new JPanel();
        JPanel second = new JPanel();
        tabs.addTab("表一", first);
        tabs.addTab("表二", second);
        ClosableTabHeader header = new ClosableTabHeader(
                tabs, first, "表一", null, () -> { });
        tabs.setTabComponentAt(0, header);
        tabs.setSelectedComponent(second);

        header.titleLabel().dispatchEvent(new MouseEvent(
                header.titleLabel(), MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 4, 4, 1, false,
                MouseEvent.BUTTON1));

        assertThat(tabs.getSelectedComponent()).isSameAs(first);
        assertThat(header.titleLabel().getFont().isBold()).isTrue();
    }

    @Test
    void shouldElideLongTitleAndKeepFullTooltip() {
        String title = "_oneclickodps_lcdp_to_jfdw_maxcompute_"
                + "jfdw_maxcompute_20250814181307_done_";
        JTabbedPane tabs = new JTabbedPane();
        JPanel content = new JPanel();
        tabs.addTab(title, content);

        ClosableTabHeader header = new ClosableTabHeader(
                tabs, content, title, null, () -> { });

        assertThat(header.titleLabel().getText())
                .endsWith("…")
                .hasSizeLessThan(title.length());
        assertThat(header.titleLabel().getToolTipText()).isEqualTo(title);
        assertThat(header.getToolTipText()).isEqualTo(title);
    }
}
