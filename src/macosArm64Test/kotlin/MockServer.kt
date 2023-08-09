@file:OptIn(ExperimentalForeignApi::class)

import com.apollographql.apollo3.annotations.ApolloInternal
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import platform.Foundation.NSMutableArray
import platform.posix.AF_INET
import platform.posix.INADDR_ANY
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * @param acceptDelayMillis: an artificial delay introduced before each `accept()`
 * call. Can be used to simulate slow connections.
 */
@OptIn(ExperimentalStdlibApi::class)
class MockServer(
    private val acceptDelayMillis: Long = 0,
) {

  init {
    check(isExperimentalMM()) {
      "Apollo: The legacy memory manager is no longer supported, please use the new memory manager instead. " +
          "See https://github.com/JetBrains/kotlin/blob/master/kotlin-native/NEW_MM.md for more information."
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private val pthreadT: pthread_tVar
  private val port: Int
  private var socket: Socket? = null
  private val handler = QueueMockServerHandler()

  init {
    val socketFd = socket(AF_INET, SOCK_STREAM, 0)

    check(socketFd != -1) {
      "Cannot open socket (errno = $errno)"
    }

    port = memScoped {
      val sockaddrIn = alloc<sockaddr_in>().apply {
        sin_family = AF_INET.convert()
        sin_port = 0.convert() //htons(port.convert())
        sin_addr.s_addr = INADDR_ANY // AutoFill local address
      }

      check(bind(socketFd, sockaddrIn.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) {
        "Cannot bind socket (errno = $errno)"
      }
      val addrLen = alloc<UIntVar>()
      addrLen.value = sizeOf<sockaddr_in>().convert()
      getsockname(socketFd, sockaddrIn.ptr.reinterpret(), addrLen.ptr)

      val networkPort = sockaddrIn.sin_port.toInt()
      // Convert to MacOS endianess, this is most likely wrong on other systems but I can't find a ntohs
      networkPort.and(0xff).shl(8).or(networkPort.and(0xff00).shr(8))
    }

    listen(socketFd, 1)

    pthreadT = nativeHeap.alloc()

    socket = Socket(socketFd, acceptDelayMillis, handler)

    val stableRef = StableRef.create(socket!!)

    pthread_create(pthreadT.ptr, null, staticCFunction { arg ->
      val ref = arg!!.asStableRef<Socket>()

      try {
        ref.get().also {
          ref.dispose()
        }.run()
      } catch (e: Throwable) {
        println("MockServer socket thread crashed: $e")
        e.printStackTrace()
      }

      null
    }, stableRef.asCPointer())
  }

  suspend fun url(): String {
    return "http://localhost:$port/"
  }

   fun enqueue(mockResponse: MockResponse) {
    check(socket != null) {
      "Cannot enqueue a response to a stopped MockServer"
    }

     handler.enqueue(mockResponse)
  }

  /**
   * [MockServer] can only stop in between complete request/responses pairs
   * If stop() is called while we're reading a request, this might wait forever
   * Revisit once okio has native Timeout
   */
  suspend fun stop() {
    if (socket == null) {
      return
    }
    socket!!.stop()
    pthread_join(pthreadT.value, null)

    pthreadT.value = null
    nativeHeap.free(pthreadT.rawPtr)

    socket = null
  }

  fun takeRequest(): MockRequest {
    check(socket != null) {
      "Cannot take a request from a stopped MockServer"
    }
    return socket!!.takeRequest()
  }
}
class MockRequest(
  val method: String,
  val path: String,
  val version: String,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteString = ByteString.EMPTY,
)

class MockResponse(
  val statusCode: Int = 200,
  val body: Flow<ByteString> = emptyFlow(),
  val headers: Map<String, String> = mapOf("Content-Length" to "0"),
  val delayMillis: Long = 0,
)


suspend fun writeResponse(sink: BufferedSink, mockResponse: MockResponse, version: String) {
  sink.writeUtf8("$version ${mockResponse.statusCode}\r\n")
  // We don't support 'Connection: Keep-Alive', so indicate it to the client
  val headers = mockResponse.headers + mapOf("Connection" to "close")
  headers.forEach {
    sink.writeUtf8("${it.key}: ${it.value}\r\n")
  }
  sink.writeUtf8("\r\n")
  sink.flush()

  mockResponse.body.collect {
    sink.write(it)
    sink.flush()
  }
}

fun parseHeader(line: String): Pair<String, String> {
  val index = line.indexOfFirst { it == ':' }
  check(index >= 0) {
    "Invalid header: $line"
  }

  return line.substring(0, index).trim() to line.substring(index + 1, line.length).trim()
}
internal fun readRequest(source: BufferedSource): MockRequest? {
  var line = source.readUtf8Line()
  if (line == null) {
    // the connection was closed
    return null
  }

  val (method, path, version) = parseRequestLine(line)

  val headers = mutableMapOf<String, String>()
  /**
   * Read headers
   */
  while (true) {
    line = source.readUtf8Line()
    //println("Header Line: $line")
    if (line.isNullOrBlank()) {
      break
    }

    val (key, value) = parseHeader(line)
    headers.put(key, value)
  }

  val contentLength = headers["Content-Length"]?.toLongOrNull() ?: 0
  val transferEncoding = headers["Transfer-Encoding"]?.lowercase()
  check(transferEncoding == null || transferEncoding == "identity" || transferEncoding == "chunked") {
    "Transfer-Encoding $transferEncoding is not supported"
  }

  val buffer = Buffer()
  if (contentLength > 0) {
    source.read(buffer, contentLength)
  }

  return MockRequest(
    method = method,
    path = path,
    version = version,
    headers = headers,
    body = buffer.readByteString()
  )
}

/**
 * Read a source encoded in the "Transfer-Encoding: chunked" encoding.
 * This format is a sequence of:
 * - chunk-size (in hexadecimal) + CRLF
 * - chunk-data + CRLF
 */
@ApolloInternal
fun BufferedSource.readChunked(buffer: Buffer) {
  while (true) {
    val line = readUtf8Line()
    if (line.isNullOrBlank()) break

    val chunkSize = line.toLong(16)
    if (chunkSize == 0L) break

    read(buffer, chunkSize)
    readUtf8Line() // CRLF
  }
}

fun parseRequestLine(line: String): Triple<String, String, String> {
  val regex = Regex("([A-Z-a-z]*) ([^ ]*) (.*)")
  val match = regex.matchEntire(line)
  check(match != null) {
    "Cannot match request line: $line"
  }

  val method = match.groupValues[1].uppercase()
  check(method in listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")) {
    "Unkown method $method"
  }

  return Triple(method, match.groupValues[2], match.groupValues[3])
}

class QueueMockServerHandler {
  private val queue = NSMutableArray()

  fun enqueue(response: MockResponse) {
    queue.addObject(response)
  }

  fun handle(request: MockRequest): MockResponse {
    check(queue.count.toInt() > 0) {
      "No more responses in queue"
    }
    val response = queue.objectAtIndex(0u) as MockResponse
    queue.removeObjectAtIndex(0u)
    return response
  }
}
