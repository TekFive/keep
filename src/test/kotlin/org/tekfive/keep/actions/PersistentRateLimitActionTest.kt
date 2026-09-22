package org.tekfive.keep.actions

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tekfive.keep.counter.CounterTable
import org.tekfive.keep.counter.CountersTable
import org.tekfive.keep.data.TestDatabase
import org.tekfive.kviash.DefaultKviashConfiguration
import org.tekfive.kviash.exchange.Exchange
import org.tekfive.kviash.exchange.ExchangeAction
import org.tekfive.kviash.exchange.ExchangePipeline
import org.tekfive.kviash.http.HttpHeader
import org.tekfive.kviash.http.HttpRequest
import org.tekfive.kviash.http.HttpRequestPath
import org.tekfive.kviash.http.HttpRequestSource
import org.tekfive.kviash.http.HttpResponse
import org.tekfive.kviash.http.HttpResponseSource
import org.tekfive.kviash.http.HttpSession
import org.tekfive.kviash.http.ResponseCookie
import org.tekfive.kviash.http.toPathSegments
import org.tekfive.kviash.routing.RoutePath
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PersistentRateLimitActionTest {
    private val clock = TestClock()

    @BeforeTest
    fun setup() {
        TestDatabase.connect()
        transaction { SchemaUtils.create(CountersTable) }
    }

    @AfterTest
    fun teardown() {
        transaction { SchemaUtils.drop(CountersTable) }
    }

    @Test
    fun `limit persists across actions and rejects only requests over the limit`() {
        val firstAction = PersistentRateLimitAction("login", 2, 1501, clock = clock)
        val secondAction = PersistentRateLimitAction("login", 2, 1501, clock = clock)
        val first = request(firstAction)
        assertEquals(200, first.status)
        assertEquals(listOf("2"), first.getHeaderValues("X-RateLimit-Limit"))
        assertEquals(listOf("1"), first.getHeaderValues("X-RateLimit-Remaining"))
        assertEquals(emptyList(), first.getHeaderValues("Retry-After"))
        val second = request(secondAction)
        assertEquals(200, second.status)
        assertEquals(listOf("0"), second.getHeaderValues("X-RateLimit-Remaining"))
        val rejected = request(firstAction)
        assertEquals(429, rejected.status)
        assertEquals(listOf("0"), rejected.getHeaderValues("X-RateLimit-Remaining"))
        assertEquals(listOf("2"), rejected.getHeaderValues("Retry-After"))
        assertEquals(3L, CountersTable.get("rate:login", "127.0.0.1")?.count)

        clock.now = 1500
        assertEquals(listOf("1"), request(secondAction).getHeaderValues("Retry-After"))
        clock.now = 1501
        val reset = request(secondAction)
        assertEquals(200, reset.status)
        assertEquals(listOf("1"), reset.getHeaderValues("X-RateLimit-Remaining"))
    }

    @Test
    fun `scopes and clients have separate request budgets`() {
        val login = PersistentRateLimitAction("login", 1, 1000, clock = clock)
        val refresh = PersistentRateLimitAction("refresh", 1, 1000, clock = clock)
        assertEquals(200, request(login).status)
        assertEquals(429, request(login).status)
        assertEquals(200, request(login, ip = "127.0.0.2").status)
        assertEquals(200, request(refresh).status)
    }

    @Test
    fun `custom key and table can share a tenant budget across client IPs`() {
        val table = CounterTable("tenant_rate_counters")
        transaction { SchemaUtils.create(table) }
        try {
            val action = PersistentRateLimitAction("exports", 1, 1000, counters = table, clock = clock) {
                "tenant-123"
            }
            assertEquals(200, request(action).status)
            assertEquals(429, request(action, ip = "127.0.0.2").status)
            assertEquals(2L, table.get("rate:exports", "tenant-123")?.count)
            assertNull(CountersTable.get("rate:exports", "tenant-123"))
        } finally {
            transaction { SchemaUtils.drop(table) }
        }
    }

    @Test
    fun `rejected requests stop the route action`() {
        val action = PersistentRateLimitAction("route", 1, 1000, clock = clock)
        var handled = 0
        assertEquals(200, request(action, handler = { handled++ }).status)
        assertEquals(429, request(action, handler = { handled++ }).status)
        assertEquals(1, handled)
    }

    @Test
    fun `invalid rate limit configuration fails immediately`() {
        assertFailsWith<IllegalArgumentException> { PersistentRateLimitAction("", 1, 1000) }
        assertFailsWith<IllegalArgumentException> { PersistentRateLimitAction("a".repeat(124), 1, 1000) }
        assertFailsWith<IllegalArgumentException> { PersistentRateLimitAction("a", 0, 1000) }
        assertFailsWith<IllegalArgumentException> { PersistentRateLimitAction("a", 1, 0) }
    }

    private fun request(
        action: PersistentRateLimitAction,
        ip: String = "127.0.0.1",
        handler: () -> Unit = {},
    ): TestResponse {
        val configuration = DefaultKviashConfiguration
        val path = "/test".toPathSegments(true)
        val pipeline = ExchangePipeline(
            configuration, RoutePath(path),
            interceptors = emptyList(), preActions = listOf(action),
            action = ExchangeAction { handler(); null },
            postActions = emptyList(), routeAttributes = emptyMap(),
        )
        val response = TestResponse()
        val exchange = Exchange(
            HttpRequest(TestRequest(ip), configuration), HttpRequestPath(path),
            HttpResponse(response, configuration), pipeline,
        )
        pipeline(exchange)
        return response
    }
}

private class TestClock(var now: Long = 0) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(now)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
}

private class TestRequest(override val clientIp: String) : HttpRequestSource {
    override val method = "GET"
    override val path = "/test"
    override val queryString: String? = null
    override val urlProtocol = "http"
    override val httpProtocol = "HTTP/1.1"
    override val port = 80
    override val headers = listOf("Host" to listOf("localhost"))
    override val parameters = emptyList<Pair<String, List<String>>>()
    override val inputStream: InputStream? = null
    private val attributes = mutableMapOf<String, Any?>()
    override fun getAttribute(name: String): Any? = attributes[name]
    override fun setAttribute(name: String, value: Any?) { attributes[name] = value }
    override fun getSession(createIfNotExists: Boolean): HttpSession? = null
}

private class TestResponse : HttpResponseSource {
    private var statusCode = 200
    override val status get() = statusCode
    override val headers = mutableListOf<HttpHeader>()
    override var committed = false
        private set
    override val outputStream = ByteArrayOutputStream()
    override val outputWriter = OutputStreamWriter(outputStream)
    override fun addCookie(cookie: ResponseCookie) = Unit
    override fun addHeader(header: HttpHeader) { headers.add(header) }
    override fun setStatus(status: Int) { statusCode = status }
    override fun setHeader(header: HttpHeader) {
        headers.removeAll { it.name.equals(header.name, true) }
        headers.add(header)
    }
    override fun getHeaderValues(name: String): List<String> =
        headers.filter { it.name.equals(name, true) }.flatMap { it.values }
    override fun commit() { outputWriter.flush(); committed = true }
    override fun createdBufferedResponse(outputBuffer: OutputStream): HttpResponseSource =
        error("Buffering is not used by these tests")
}
