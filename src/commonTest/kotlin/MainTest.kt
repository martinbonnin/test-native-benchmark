import benchmarks.GetRandomQuery
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.annotations.ApolloInternal
import com.apollographql.apollo3.api.http.HttpMethod
import com.apollographql.apollo3.api.http.HttpRequest
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.network.http.DefaultHttpEngine
import com.apollographql.apollo3.network.http.HttpEngine
import com.apollographql.apollo3.testing.enqueueData
import com.apollographql.apollo3.testing.internal.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.measureTime

class BenchmarksTest {
    private val server = MockServer()
    private lateinit var client: HttpEngine

    @OptIn(ApolloInternal::class)
    private fun benchmark(test: suspend (Int) -> Unit) = runTest {
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

        server.enqueueData(
            GetRandomQuery.Data {
                random = 42
            }
        )

        println("$iteration - server.url=${server.url()}")
        HttpRequest.Builder(HttpMethod.Get, server.url())
            .build()
            .let {
                client.execute(it)
            }
    }

    @Test
    fun benchmarkSimpleQuery() = benchmark { simpleQuery(it) }

    companion object {
        private const val EXECUTION_PER_MEASUREMENT = 500
        private const val MEASUREMENT_COUNT = 10
    }
}
