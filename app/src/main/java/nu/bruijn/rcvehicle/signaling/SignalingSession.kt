package nu.bruijn.rcvehicle.signaling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

private val json = Json { ignoreUnknownKeys = true }

/**
 * One newline-delimited-JSON connection carrying SignalingMessages, in either
 * direction. Wraps a connected Socket from either SignalingServer.accept() or
 * SignalingClient.connect().
 */
class SignalingSession(private val socket: Socket) {
    private val writer = PrintWriter(socket.getOutputStream(), true)
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

    suspend fun send(message: SignalingMessage) = withContext(Dispatchers.IO) {
        writer.println(json.encodeToString(SignalingMessage.serializer(), message))
    }

    /** Emits one SignalingMessage per line until the socket closes or errors. */
    fun messages(): Flow<SignalingMessage> = callbackFlow {
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (line.isBlank()) continue
                val msg = runCatching {
                    json.decodeFromString(SignalingMessage.serializer(), line)
                }.getOrNull() ?: continue
                trySend(msg)
            }
        } finally {
            close(null)
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    fun close() {
        runCatching { socket.close() }
    }
}
