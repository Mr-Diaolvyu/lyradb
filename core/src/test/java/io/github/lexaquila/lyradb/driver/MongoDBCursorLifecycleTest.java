package io.github.lexaquila.lyradb.driver;

import com.mongodb.client.FindIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoIterable;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoDBCursorLifecycleTest {

    @Test
    void shouldCloseDatabaseNamesCursor() throws Exception {
        Fixture fixture = fixture();
        MongoIterable<String> iterable = mock(MongoIterable.class);
        MongoCursor<String> cursor = mock(MongoCursor.class);
        when(fixture.client.listDatabaseNames()).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        fixture.driver.getTreeNodes(fixture.connection, null);

        verify(cursor).close();
    }

    @Test
    void shouldCloseCollectionNamesCursor() throws Exception {
        Fixture fixture = fixture();
        MongoIterable<String> iterable = mock(MongoIterable.class);
        MongoCursor<String> cursor = mock(MongoCursor.class);
        when(fixture.database.listCollectionNames()).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        fixture.driver.getTreeNodes(fixture.connection, "app");

        verify(cursor).close();
    }

    @Test
    void shouldCloseQueryCursor() throws Exception {
        Fixture fixture = fixture();
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(fixture.collection.find()).thenReturn(iterable);
        when(iterable.limit(25)).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        fixture.driver.executeQuery(
                fixture.connection, "app.customer", 25);

        verify(cursor).close();
    }

    @Test
    void shouldCloseSchemaSamplingCursor() throws Exception {
        Fixture fixture = fixture();
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(fixture.collection.find()).thenReturn(iterable);
        when(iterable.limit(100)).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        fixture.driver.getTableColumns(
                fixture.connection, "app", "app/customer");

        verify(cursor).close();
    }

    @Test
    void shouldCloseIndexCursor() throws Exception {
        Fixture fixture = fixture();
        ListIndexesIterable<Document> iterable =
                mock(ListIndexesIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(fixture.collection.listIndexes()).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        fixture.driver.getTreeNodes(
                fixture.connection, "app/customer");

        verify(cursor).close();
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() throws Exception {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("app")).thenReturn(database);
        when(database.getCollection("customer")).thenReturn(collection);

        MongoDBDriver driver = createDriver();
        Class<?> wrapperClass = Class.forName(
                MongoDBDriver.class.getName() + "$AutoCloseableMongoClient");
        Constructor<?> constructor = wrapperClass.getDeclaredConstructor(
                Object.class, Class.class);
        constructor.setAccessible(true);
        Object connection = constructor.newInstance(
                client, MongoClient.class);
        return new Fixture(
                driver, connection, client, database, collection);
    }

    private static MongoDBDriver createDriver() {
        DriverInfo info = new DriverInfo();
        info.setDbType("MONGODB");
        info.setCapabilities(new DriverCapability());
        return new MongoDBDriver(
                info, MongoDBCursorLifecycleTest.class.getClassLoader());
    }

    private record Fixture(
            MongoDBDriver driver,
            Object connection,
            MongoClient client,
            MongoDatabase database,
            MongoCollection<Document> collection) {
    }
}
