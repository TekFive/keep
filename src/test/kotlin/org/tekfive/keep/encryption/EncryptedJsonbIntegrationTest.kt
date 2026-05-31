package org.tekfive.keep.encryption

import com.google.crypto.tink.aead.AeadConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.data.Data
import java.nio.file.Path
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.toJsonObject
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Simple pass-through payload type for testing encrypted storage. */
data class EncryptedPayload(val entries: Map<String, Any?>) : ToJsonObject {
    override fun toJsonObject(): JsonObject = entries.toJsonObject()

    companion object : FromJsonObject<EncryptedPayload> {
        override fun fromJson(json: JsonObject): EncryptedPayload = EncryptedPayload(json.toMap())
    }
}

/** Simple Data class for testing encrypted storage. */
class EncryptedTestRecord(
    val label: String,
    val payload: EncryptedPayload = EncryptedPayload(emptyMap()),
) : Data()

object EncryptedTestTable : DataTable<EncryptedTestRecord>("encrypted_test") {
    val label = varchar("label", 255)
    val payload = encryptedJsonb("payload", EncryptedPayload)
}

@Testcontainers
class EncryptedJsonbIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @BeforeAll
        @JvmStatic
        fun setup(@TempDir tmp: Path) {
            AeadConfig.register()
            DatabaseEncryptionProvider.resetForTesting()
            val keysetPath = tmp.resolve("keyset.json")
            KeysetIO.write(KeysetTemplate.generateNewKeysetHandle(), keysetPath)
            DatabaseEncryptionProvider.configure(
                KeysetLoader.Config(
                    mode = EncryptionKeysetMode.PLAINTEXT,
                    file = keysetPath,
                )
            )
            DatabaseEncryptionProvider.ensureInitialized()

            AckRegistry.clear()
            AckRegistry.addSource(MapSource(mapOf(
                "JDBC_URL" to postgres.jdbcUrl,
                "JDBC_USER" to postgres.username,
                "JDBC_PASSWORD" to postgres.password,
            )))
            DbConnection.startup()

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE SEQUENCE IF NOT EXISTS globalid")
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS encrypted_test (
                            id BIGINT DEFAULT nextval('globalid') PRIMARY KEY,
                            label VARCHAR(255) NOT NULL,
                            payload BYTEA NOT NULL
                        )
                    """.trimIndent())
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            DbConnection.shutdown()
            AckRegistry.clear()
            DatabaseEncryptionProvider.resetForTesting()
        }
    }

    @BeforeEach
    fun cleanUp() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE encrypted_test RESTART IDENTITY CASCADE") }
        }
    }

    @Test
    fun `should store and retrieve encrypted JSON object`() {
        val payload = EncryptedPayload(mapOf("patient" to "Jane Doe", "mrn" to "12345"))
        val record = EncryptedTestRecord(label = "report-1", payload = payload)

        val saved = db { EncryptedTestTable.create(record) }
        val loaded = db { EncryptedTestTable.findById(saved.id) }

        assertNotNull(loaded)
        assertEquals("report-1", loaded.label)
        assertEquals("Jane Doe", loaded.payload.entries["patient"])
        assertEquals("12345", loaded.payload.entries["mrn"])
    }

    @Test
    fun `should store ciphertext as BYTEA not readable JSON`() {
        val payload = EncryptedPayload(mapOf("secret" to "classified"))
        val record = EncryptedTestRecord(label = "secret-1", payload = payload)

        db { EncryptedTestTable.create(record) }

        // Read raw bytes directly from the database
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT payload FROM encrypted_test WHERE label = ?").use { stmt ->
                stmt.setString(1, "secret-1")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val rawBytes = rs.getBytes("payload")
                    assertNotNull(rawBytes)
                    // The raw value should NOT contain the plaintext
                    val rawString = String(rawBytes, Charsets.UTF_8)
                    assertTrue(!rawString.contains("classified"))
                    assertTrue(!rawString.contains("secret"))
                }
            }
        }
    }

    @Test
    fun `should round-trip empty JSON object`() {
        val record = EncryptedTestRecord(label = "empty", payload = EncryptedPayload(emptyMap()))

        val saved = db { EncryptedTestTable.create(record) }
        val loaded = db { EncryptedTestTable.findById(saved.id) }

        assertNotNull(loaded)
        assertTrue(loaded.payload.entries.isEmpty())
    }
}
