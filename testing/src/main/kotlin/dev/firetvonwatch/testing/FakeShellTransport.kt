package dev.firetvonwatch.testing

import dev.firetvonwatch.domain.model.ConnectionError
import dev.firetvonwatch.domain.protocol.InteractiveShell
import dev.firetvonwatch.domain.protocol.ShellResult
import dev.firetvonwatch.domain.protocol.ShellTransport
import dev.firetvonwatch.domain.protocol.ShellTransportFactory
import dev.firetvonwatch.domain.protocol.TransportException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Scriptable stand-in for an ADB shell. Replies come from [responder]; every command is logged. */
class FakeShellTransport(
    var responder: (String) -> ShellResult = { ShellResult(0, "") },
) : ShellTransport {
    val commands = mutableListOf<String>()
    @Volatile var closed = false
        private set
    /** When true, exec() blocks until close() is called (simulates a dead TCP link). */
    @Volatile var hang = false
    private val closeLatch = CountDownLatch(1)

    override fun exec(command: String): ShellResult {
        if (closed) throw TransportException(ConnectionError.IO, "closed")
        synchronized(commands) { commands += command }
        if (hang) {
            closeLatch.await(10, TimeUnit.SECONDS)
            throw TransportException(ConnectionError.IO, "socket closed")
        }
        return responder(command)
    }

    /** Files "pushed" to the fake TV, by remote path. */
    val pushed = mutableMapOf<String, ByteArray>()
    val interactive = mutableListOf<FakeInteractiveShell>()
    /** Creates the process for openInteractive(); default: a well-behaved injector. */
    var interactiveFactory: (String) -> FakeInteractiveShell = { FakeInteractiveShell.injector() }

    override fun push(bytes: ByteArray, remotePath: String) {
        if (closed) throw TransportException(ConnectionError.IO, "closed")
        pushed[remotePath] = bytes
    }

    override fun openInteractive(command: String): InteractiveShell {
        if (closed) throw TransportException(ConnectionError.IO, "closed")
        return interactiveFactory(command).also { it.command = command; interactive += it }
    }

    override fun close() {
        closed = true
        closeLatch.countDown()
        interactive.forEach { it.close() }
    }

    companion object {
        /** Responder for the probe (a TV with `cmd input`) and the injector presence check (missing). */
        fun fireOs8(command: String): ShellResult = when {
            command.startsWith("getprop") -> ShellResult(0, "AFTKRT\n30\n1\n")
            command.contains("sha256sum") -> ShellResult(0, "missing\n")
            else -> ShellResult(0, "")
        }
    }
}

class FakeShellTransportFactory(
    var nextError: ConnectionError? = null,
    var transportProvider: () -> FakeShellTransport = { FakeShellTransport(FakeShellTransport::fireOs8) },
) : ShellTransportFactory {
    val opened = mutableListOf<FakeShellTransport>()

    override fun open(host: String, port: Int): ShellTransport {
        nextError?.let { throw TransportException(it) }
        return transportProvider().also { opened += it }
    }
}

/**
 * Scriptable long-running process. [onLine] maps each written line to a reply (null = no
 * reply, so readLine blocks until close). The first readLine returns [greeting].
 */
class FakeInteractiveShell(
    private val greeting: String?,
    var onLine: (String) -> String?,
) : InteractiveShell {
    var command: String = ""
    val written = mutableListOf<String>()
    @Volatile var closed = false
        private set
    private val replies = java.util.concurrent.LinkedBlockingQueue<String>()
    private var greeted = false

    override fun writeLine(line: String) {
        if (closed) throw java.io.IOException("closed")
        synchronized(written) { written += line }
        onLine(line)?.let { replies.put(it) }
    }

    override fun readLine(): String? {
        if (!greeted) {
            greeted = true
            if (greeting != null) return greeting
        }
        while (!closed) {
            replies.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
        }
        return null
    }

    override fun close() {
        closed = true
    }

    companion object {
        fun injector() = FakeInteractiveShell("READY 2") { line ->
            when {
                line.startsWith("K ") -> "OK"
                // Label "Netflix", 3-byte "icon".
                line == "I com.netflix.ninja" -> "I TmV0ZmxpeA== AQID"
                line.startsWith("I ") -> "ERR info IllegalArgumentException"
                else -> "ERR bad command"
            }
        }
        fun broken(message: String = "ERR init java.lang.ClassNotFoundException") = FakeInteractiveShell(message) { null }
    }
}
