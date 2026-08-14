package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameWorkspaceTabsTest {

    @Test
    void shouldSelectNextTabWhenClosingCurrentTab() {
        JTabbedPane tabs = tabs();
        tabs.setSelectedIndex(1);

        assertThat(MainFrame.selectionAfterClose(
                tabs, tabs.getComponentAt(1)))
                .isSameAs(tabs.getComponentAt(2));
    }

    @Test
    void shouldSelectPreviousTabWhenClosingLastTab() {
        JTabbedPane tabs = tabs();
        tabs.setSelectedIndex(2);

        assertThat(MainFrame.selectionAfterClose(
                tabs, tabs.getComponentAt(2)))
                .isSameAs(tabs.getComponentAt(1));
    }

    @Test
    void shouldKeepSelectionWhenClosingBackgroundTab() {
        JTabbedPane tabs = tabs();
        tabs.setSelectedIndex(2);

        assertThat(MainFrame.selectionAfterClose(
                tabs, tabs.getComponentAt(1)))
                .isSameAs(tabs.getComponentAt(2));
    }

    private static JTabbedPane tabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("开始", new JPanel());
        tabs.addTab("表一", new JPanel());
        tabs.addTab("表二", new JPanel());
        return tabs;
    }
}
