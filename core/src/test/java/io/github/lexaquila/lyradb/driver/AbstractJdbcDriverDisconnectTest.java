package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractJdbcDriverDisconnectTest {

    private final GenericJdbcDriver driver = createDriver();

    @Test
    void shouldRollbackActiveTransactionBeforeClosing() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(connection.getAutoCommit()).thenReturn(false);

        driver.disconnect(connection);

        InOrder order = inOrder(connection);
        order.verify(connection).isClosed();
        order.verify(connection).getAutoCommit();
        order.verify(connection).rollback();
        order.verify(connection).close();
    }

    @Test
    void shouldNotRollbackAutoCommitConnection() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(connection.getAutoCommit()).thenReturn(true);

        driver.disconnect(connection);

        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void shouldStillCloseWhenRollbackFails() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(connection.getAutoCommit()).thenReturn(false);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();

        driver.disconnect(connection);

        verify(connection).close();
    }

    private static GenericJdbcDriver createDriver() {
        DriverInfo info = new DriverInfo();
        info.setDbType("TEST");
        info.setCapabilities(new DriverCapability());
        return new GenericJdbcDriver(
                info, AbstractJdbcDriverDisconnectTest.class.getClassLoader());
    }
}
