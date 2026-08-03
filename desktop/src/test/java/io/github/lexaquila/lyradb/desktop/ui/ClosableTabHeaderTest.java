package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
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
}
