import com.apollographql.apollo3.annotations.ApolloInternal
import com.apollographql.apollo3.api.http.HttpMethod
import com.apollographql.apollo3.api.http.HttpRequest
import com.apollographql.apollo3.network.http.DefaultHttpEngine
import com.apollographql.apollo3.network.http.HttpEngine
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okio.Buffer
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.measureTime

class BenchmarksTest {
    private val server: ApplicationEngine

    private val port = 8081
    private lateinit var client: HttpEngine

    init {
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/") {
                    call.respondText("Hello, world!")
                }
            }
        }.start()
    }
    @OptIn(ApolloInternal::class)
    private fun benchmark(test: suspend (Int) -> Unit) = runBlocking {
        val durations = mutableListOf<Duration>()
        repeat(MEASUREMENT_COUNT) {
            durations.add(
                measureTime {
                    repeat(EXECUTION_PER_MEASUREMENT) { test(it) }
                }
            )
        }
    }

    private suspend fun simpleQuery(iteration: Int) {
        if (iteration == 0) {
            client = DefaultHttpEngine()
        }

        val body = "hello $iteration"
//        server.enqueue(
//            MockResponse(
//                body = flowOf(Buffer().writeUtf8(body).readByteString()),
//                headers = mapOf("Content-Length" to body.length.toString()),
//            )
//        )

        println("$iteration - server.url=http://127.0.0.1:$port/")
        HttpRequest.Builder(HttpMethod.Get, "http://127.0.0.1:$port/")
            .build()
            .let {
                client.execute(it)
            }
            .body!!.readUtf8().let {
                println("Got - $it")
            }
    }

    @Test
    fun benchmarkSimpleQuery() = benchmark { simpleQuery(it) }

    companion object {
        private const val EXECUTION_PER_MEASUREMENT = 500
        private const val MEASUREMENT_COUNT = 10
    }
}
