@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import okio.IOException
import okio.buffer
import platform.Foundation.NSMutableArray
import platform.posix.*
import kotlin.experimental.and
import kotlin.native.concurrent.AtomicInt

class Socket(
    private val socketFd: Int,
    private val acceptDelayMillis: Long,
    private val mockServerHandler: QueueMockServerHandler,

) {
  private val pipeFd = nativeHeap.allocArray<IntVar>(2)
  private val running = AtomicInt(1)
  private val lock = reentrantLock()
  private val recordedRequests = NSMutableArray()

  init {
    check(pipe(pipeFd) == 0) {
      "Cannot create pipe (errno=$errno)"
    }
  }

  private inline fun debug(message: String) {
    if (false) {
      println(message)
    }
  }

  fun run() {
    while (running.value != 0) {
      memScoped {
        val fdSet = allocArray<pollfd>(2)

        fdSet[0].fd = socketFd
        fdSet[0].events = POLLIN.convert()
        fdSet[1].fd = pipeFd[0]
        fdSet[1].events = POLLIN.convert()

        // the timeout is certainly not required but since we're not locking running.value
        // I guess there's a chance for a small race
        poll(fdSet, 2.convert(), 1000.convert())

        if (fdSet[0].revents.and(POLLIN.convert()).toInt() == 0) {
          return@memScoped
        }

        if (acceptDelayMillis > 0) {
          usleep((acceptDelayMillis * 1000).convert())
        }

        // wait for a new incoming connection
        val connectionFd = accept(socketFd, null, null)

        check(connectionFd >= 0) {
          "Cannot accept socket (errno = $errno)"
        }

        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(connectionFd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, 4u)

        handleConnection(connectionFd)
        close(connectionFd)
      }
    }
    close(socketFd)
  }

  private fun handleConnection(connectionFd: Int) {
    val source = FileDescriptorSource(connectionFd).buffer()
    val sink = FileDescriptorSink(connectionFd).buffer()

    memScoped {
      val sockaddrIn = alloc<sockaddr_in>()
      val addrLen = alloc<UIntVar>()

      addrLen.value = sizeOf<sockaddr_in>().convert()
      getpeername(connectionFd, sockaddrIn.ptr.reinterpret(), addrLen.ptr)

      val networkPort = sockaddrIn.sin_port.toInt()
      // Convert to MacOS endianess, this is most likely wrong on other systems but I can't find a ntohs
      val clientPort = networkPort.and(0xff).shl(8).or(networkPort.and(0xff00).shr(8))

      //println("clientPort= $clientPort")
    }

    while (running.value != 0) {
      memScoped {
        val fdSet = allocArray<pollfd>(2)

        fdSet[0].fd = connectionFd
        fdSet[0].events = POLLIN.convert()
        fdSet[1].fd = pipeFd[0]
        fdSet[1].events = POLLIN.convert()

        // the timeout is certainly not required but since we're not locking running.value
        // I guess there's a chance for a small race
        poll(fdSet, 2.convert(), 1000.convert())

        if (fdSet[0].revents.and(POLLIN.convert()).toInt() == 0) {
          return@memScoped
        }

        debug("'$connectionFd': Read request")

        val request = readRequest(source)
        if (request == null) {
          debug("'$connectionFd': Connection closed")
          return
        }

        debug("Got request: ${request.method} ${request.path}")

        val mockResponse = synchronized(lock) {
          recordedRequests.addObject(request)
          try {
            mockServerHandler.handle(request)
          } catch (e: Exception) {
            throw Exception("MockServerHandler.handle() threw an exception: ${e.message}", e)
          }
        }

        debug("Write response: ${mockResponse.statusCode}")

        if (mockResponse.delayMillis > 0) {
          usleep((mockResponse.delayMillis * 1000).convert())
        }

        try {
          runBlocking { writeResponse(sink, mockResponse, request.version) }
        } catch (e: IOException) {
          debug("'$connectionFd': writeResponse error")
          return
        }
        debug("Response Written")
      }
    }
  }

  fun stop() {
    running.value = 0

    memScoped {
      val buf = allocArray<ByteVar>(1)
      // Write a placeholder byte to unblock the reader if needed
      write(pipeFd[1], buf, 1u)
    }
    nativeHeap.free(pipeFd.rawValue)
  }

  fun takeRequest(): MockRequest {
    return synchronized(lock) {
      check(recordedRequests.count.toInt() > 0) {
        "no recorded request"
      }
      recordedRequests.objectAtIndex(0u).also {
        recordedRequests.removeObjectAtIndex(0u)
      } as MockRequest
    }
  }
}
