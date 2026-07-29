package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.dto.ConnectionDTO;
import io.github.lexaquila.lyradb.model.entity.ConnectionConfig;
import io.github.lexaquila.lyradb.repository.ConnectionConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ConnectionServiceSecurityTest {

    @Test
    void connectionExportNeverDecryptsSecrets() {
        ConnectionConfigRepository repository = mock(ConnectionConfigRepository.class);
        CredentialService credentials = mock(CredentialService.class);
        ConnectionConfig config = new ConnectionConfig();
        config.setId("c1");
        config.setName("测试连接");
        config.setDbType("MYSQL");
        config.setConnectionParamsJson("{\"host\":\"db\",\"password\":\"ciphertext\"}");
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(config));
        when(credentials.maskSensitiveFields(anyMap())).thenReturn(
                Map.of("host", "db", "password", CredentialService.MASKED_VALUE));

        ConnectionService service = new ConnectionService(repository,
                mock(DriverFactory.class), mock(DriverRegistry.class), credentials,
                new ObjectMapper(), new AppProperties(), mock(SshTunnelService.class));

        List<ConnectionDTO> exported = service.exportConnections();

        assertEquals(CredentialService.MASKED_VALUE,
                exported.get(0).getParams().get("password"));
        verify(credentials, never()).decryptSensitiveFields(anyMap());
    }
}
