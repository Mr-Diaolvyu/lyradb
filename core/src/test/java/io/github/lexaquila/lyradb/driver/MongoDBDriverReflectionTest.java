package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoDBDriverReflectionTest {

    @Test
    void shouldEncodeCredentialsAndOptionsInConnectionString() {
        String uri = MongoDBDriver.buildConnectionString(
                "::1", 27017,
                "alice@example.com", "p a:ss/?#@",
                "admin db", true);

        assertThat(uri).isEqualTo(
                "mongodb://alice%40example.com:p%20a%3Ass%2F%3F%23%40"
                        + "@[::1]:27017/?authSource=admin%20db&ssl=true");
    }

    @Test
    void shouldRejectHostThatCanInjectUriOptions() {
        assertThatThrownBy(() -> MongoDBDriver.buildConnectionString(
                "localhost/?tls=false", 27017, null, null, "admin", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法");
    }

    @Test
    void shouldCreateClientWithMongoDriverApiWithoutConnectingToServer()
            throws Exception {
        MongoDBDriver driver = createDriver();
        Object connection = driver.connect(Map.of(
                "host", "127.0.0.1",
                "port", 27017,
                "authSource", "admin",
                "ssl", false));
        try {
            assertThat(connection).isNotNull();
        } finally {
            driver.disconnect(connection);
        }
    }

    @Test
    void shouldResolveBsonBasedMongoApiSignatures() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        Class<?> bson = Class.forName("org.bson.conversions.Bson", true, loader);
        Class<?> database = Class.forName(
                "com.mongodb.client.MongoDatabase", true, loader);
        Class<?> collection = Class.forName(
                "com.mongodb.client.MongoCollection", true, loader);

        assertThatCode(() -> database.getMethod("runCommand", bson))
                .doesNotThrowAnyException();
        assertThatCode(() -> collection.getMethod("updateOne", bson, bson))
                .doesNotThrowAnyException();
        assertThatCode(() -> collection.getMethod("deleteOne", bson))
                .doesNotThrowAnyException();
    }

    private static MongoDBDriver createDriver() {
        DriverInfo info = new DriverInfo();
        info.setDbType("MONGODB");
        info.setCapabilities(new DriverCapability());
        return new MongoDBDriver(
                info, MongoDBDriverReflectionTest.class.getClassLoader());
    }
}
