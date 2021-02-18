package me.legrange.mikrotik.impl

import cn.foperate.ros.munity.AsyncSocketFactory
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.MultiEmitter
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.core.net.NetSocket
import me.legrange.mikrotik.ApiConnection
import me.legrange.mikrotik.ApiConnectionException
import me.legrange.mikrotik.MikrotikApiException
import me.legrange.mikrotik.ResultListener
import org.slf4j.LoggerFactory
import java.io.*
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Consumer


/**
 * The Mikrotik API connection implementation. This is the class used to connect
 * to a remote Mikrotik and send commands to it.
 *
 * @author GideonLeGrange
 */
class AsyncApiConnection private constructor(val vertx: Vertx): AutoCloseable{
    fun isConnected(): Boolean {
        return connected
    }

    fun login(username: String, password: String):Uni<Void> {
        return Uni.createFrom().emitter { emitter ->
            if (username.trim { it <= ' ' }.isEmpty()) {
                emitter.fail(ApiConnectionException("API username cannot be empty"))
            }
            val cmd = Command("/login")
            cmd.addParameter("name", username)
            cmd.addParameter("password", password)
            var hasHash = false
            executeAsMulti(cmd, timeout = timeout)
                .onItem().invoke { res ->
                    log.debug("似乎需要MD5校验")
                    hasHash = true

                    if (res.containsKey("ret")) {
                        val hash = res["ret"] as String
                        var chal = AsyncUtil.hexStrToStr("00") + String(password.toCharArray()) + AsyncUtil.hexStrToStr(hash)
                        chal = AsyncUtil.hashMD5(chal)
                        executeAsMulti("/login name=$username response=00$chal")
                            .subscribe().with({
                                emitter.complete(null)
                            }) { e ->
                                emitter.fail(e)
                            }
                    } }
                .onCompletion().invoke {
                    log.debug("成功登陆，没有MD5校验")
                    if (!hasHash) {
                        emitter.complete(null)
                    }
                }
                .onFailure().invoke { e ->
                    emitter.fail(e)
                }
                .subscribe().with {}
        }


        /*if (list.isNotEmpty()) {
            val res = list[0]
            if (res.containsKey("ret")) {
                val hash = res["ret"]
                var chal = Util.hexStrToStr("00") + String(password.toCharArray()) + Util.hexStrToStr(hash)
                chal = Util.hashMD5(chal)
                execute("/login name=$username response=00$chal")
            }
        }*/
    }

    @Throws(MikrotikApiException::class)
    fun executeAsMulti(cmd: String): Multi<Map<String, String>> {
        return executeAsMulti(Parser.parse(cmd), timeout = timeout)
    }

    @Throws(MikrotikApiException::class)
    fun execute(cmd: String, lis: ResultListener): String {
        return execute(Parser.parse(cmd), lis)
    }

    @Throws(MikrotikApiException::class)
    fun cancel(tag: String) {
        executeAsMulti("/cancel tag=$tag")
    }

    @Throws(MikrotikApiException::class)
    fun setTimeout(timeout: Int) {
        if (timeout > 0) {
            this.timeout = timeout
        } else {
            throw MikrotikApiException("Invalid timeout value '$timeout'; must be postive")
        }
    }

    @Throws(ApiConnectionException::class)
    override fun close() {
        if (!connected) {
            throw ApiConnectionException("Not/no longer connected to remote Mikrotik")
        }

        try {
            sock.close().subscribe().with { connected = false }
        } catch (ex: IOException) {
            throw ApiConnectionException("Error closing socket: ${ex.message}", ex)
        }
    }

    @Throws(MikrotikApiException::class)
    private fun executeAsMulti(cmd: Command, timeout: Int): Multi<Map<String, String>> {
        val listener = SyncListener(vertx)
        execute(cmd, listener)
        return listener.getResults(timeout)
    }

    @Throws(MikrotikApiException::class)
    private fun execute(cmd: Command, lis: ResultListener): String {
        val tag = nextTag()
        cmd.tag = tag
        listeners[tag] = lis
        try {
            val baos = ByteArrayOutputStream()
            AsyncUtil.write(cmd, baos)
            val buffer = Buffer.buffer(baos.toByteArray())
            sock.write(buffer).subscribe().with {
                log.debug("命令已发送")
            }
        } catch (ex: UnsupportedEncodingException) {
            throw ApiDataException(ex.message, ex)
        } catch (ex: IOException) {
            throw ApiConnectionException(ex.message, ex)
        }
        return tag
    }

    /**
     * Start the API. Connects to the Mikrotik
     */
    @Throws(ApiConnectionException::class)
    private fun open(host: String, port: Int, fact: AsyncSocketFactory):Uni<Void> {
        return Uni.createFrom().emitter { emitter ->
            fact.createSocket(host, port).subscribe().with ({
                log.info("已连接到服务器")
                connected = true
                sock = it
                it.closeHandler {
                    connected = false
                }
                processor = Processor()
                reader = Reader()
                it.handler(reader)
                it.exceptionHandler { e ->
                    log.error(e.toString(), e.message)
                    reader.put(ApiConnectionException(e.message, e))
                    connected = false
                    it.closeAndForget()
                }
                emitter.complete(null)
            }) {
                log.error(it.toString(), it.message)
                connected = false
                if (it is UnknownHostException) {
                    emitter.fail(ApiConnectionException("Unknown host $host", it))
                } else {
                    emitter.fail(ApiConnectionException(
                        "Error connecting to $host:$port : ${it.message}",
                        it
                    ))
                }
            }
        }
    }

    private fun nextTag(): String {
        _tag++
        return Integer.toHexString(_tag)
    }

    private lateinit var sock: NetSocket
    private var connected = false
    private lateinit var reader: Reader
    private lateinit var processor: Processor
    private val listeners: MutableMap<String, ResultListener> = ConcurrentHashMap()

    private var _tag = 0
    private var timeout = ApiConnection.DEFAULT_COMMAND_TIMEOUT

    /**
     * thread to read data from the socket and process it into Strings
     */
    private inner class Reader : Consumer<Buffer> {

        var output = ByteArrayOutputStream()
        var remains = 0

        @Throws(ApiDataException::class)
        fun take(): String? {
            val value = try {
                queue.take()
            } catch (ex: InterruptedException) {
                throw ApiConnectionException("Interrupted while reading data from queue.", ex)
            }
            if (value is ApiConnectionException) {
                throw value
            } else if (value is ApiDataException) {
                throw value
            }
            return value as String?
        }

        val isEmpty: Boolean
            get() = queue.isEmpty()

        fun put(data: Any) {
            try {
                queue.put(data)
            } catch (ex: InterruptedException) {
            }
        }

        private val queue: LinkedBlockingQueue<Any> = LinkedBlockingQueue(40)

        override fun accept(buffer: Buffer) {
            log.debug("收到数据：" + buffer.length())
            var remainBuffer = buffer

            if (remains==0) {
                remains = readLen(remainBuffer)
                log.debug("message length=$remains")
            }
            while (remains>0){
                val bufferLength = remainBuffer.length()
                if (bufferLength<remains) {
                    output.write(remainBuffer.bytes)
                    remains -= bufferLength
                    return
                }

                // else
                output.write(remainBuffer.bytes, 0, remains)
                if (remains==1) {
                    try {
                        val s = AsyncUtil.decode(ByteArrayInputStream(output.toByteArray()))
                        put(s)
                    } catch (ex: ApiDataException) {
                        put(ex)
                    } catch (ex: ApiConnectionException) {
                        if (connected) {
                            put(ex)
                        }
                    }
                    processor.run()
                    output = ByteArrayOutputStream()
                }

                if (bufferLength>remains) {
                    //output.write(buffer.bytes, remains.toInt(), buffer.length()-remains.toInt())
                    remainBuffer = remainBuffer.slice(remains, bufferLength)
                    remains = readLen(remainBuffer)
                    log.debug("message length=$remains")
                } else {
                    log.debug("数据读取完毕")
                    remains = 0
                }
            }
        }

        /**
         * read length bytes from stream and return length of coming word, including length bytes
         */
        private fun readLen(buffer: Buffer): Int {
            var c = buffer.getUnsignedByte(0).toInt()
            when {
                c and 0xC0 == 0x80 -> {
                    c = buffer.getUnsignedShort(0) and 0x8000.inv()
                    return 2+c
                }
                c and 0xE0 == 0xC0 -> {
                    c = buffer.getUnsignedMedium(0) and 0xC00000.inv()
                    return 3+c
                }
                c and 0xF0 == 0xE0 -> {
                    c = buffer.getInt(0) or 0x0FFFFFFF
                    return 4+c
                }
                c == 0xF0 -> {
                    return buffer.getInt(1) + 5
                }
                else -> return 1+c
            }
        }
    }

    /**
     * Thread to take the received strings and process it into Result objects
     */
    private inner class Processor : Runnable {
        override fun run() {
            if (connected) {
                val res: Response = try {
                    unpack()
                } catch (ex: ApiCommandException) {
                    val tag = ex.tag
                    if (tag != null) {
                        Error(tag, ex.message, ex.category)
                    } else {
                        return
                    }
                } catch (ex: MikrotikApiException) {
                    return
                }
                val listener = listeners[res.tag]
                if (listener != null) {
                    when (res) {
                        is Result -> listener.receive(res)
                        is Done -> {
                            if (listener is SyncListener) {
                                listener.completed(res)
                            } else {
                                listener.completed()
                            }
                            listeners.remove(res.getTag())
                        }
                        is Error -> listener.error(ApiCommandException(res))
                    }
                }
            }
        }

        @Throws(ApiConnectionException::class, ApiDataException::class)
        private fun nextLine() {
            if (lines.isEmpty()) {
                val block = reader.take()!!
                val parts = block.split("\n".toRegex()).toTypedArray()
                lines.addAll(parts)
            }
            line = lines.removeAt(0)
        }

        private fun hasNextLine(): Boolean {
            return lines.isNotEmpty() || !reader.isEmpty
        }

        @Throws(ApiConnectionException::class, ApiDataException::class)
        private fun peekLine(): String {
            if (lines.isEmpty()) {
                val block = reader.take()!!
                val parts = block.split("\n".toRegex()).toTypedArray()
                lines.addAll(parts)
            }
            return lines[0]
        }

        @Throws(MikrotikApiException::class)
        private fun unpack(): Response {
            if (line == null) {
                nextLine()
            }
            return when (line) {
                "!re" -> unpackRe()
                "!done" -> unpackDone()
                "!trap" -> unpackError()
                "!halt" -> unpackError()
                "" -> throw ApiDataException("Unexpected line '$line'")
                else -> throw ApiDataException("Unexpected line '$line'")
            }
        }

        @Throws(ApiDataException::class, ApiConnectionException::class)
        private fun unpackRe(): Result {
            nextLine()
            var l = 0
            val res = Result()
            while (!line!!.startsWith("!")) {
                l++
                if (line!!.startsWith("=")) {
                    val parts = line!!.split("=".toRegex(), 3).toTypedArray()
                    if (parts.size == 3) {
                        if (!parts[2].endsWith("\r")) {
                            res[parts[1]] = unpackResult(parts[2])
                        } else {
                            val sb = StringBuilder()
                            sb.append(parts[2])
                            while (lines.isNotEmpty()) {
                                nextLine()
                                sb.append(line)
                            }
                            res[parts[1]] = sb.toString()
                        }
                    } else {
                        throw ApiDataException(String.format("Malformed line '%s'", line))
                    }
                } else if (line!!.startsWith(".tag=")) {
                    val parts = line!!.split("=".toRegex(), 2).toTypedArray()
                    if (parts.size == 2) {
                        res.tag = parts[1]
                    }
                } else {
                    throw ApiDataException("Unexpected line '$line'")
                }
                if (hasNextLine()) {
                    nextLine()
                } else {
                    line = null
                    break
                }
            }
            return res
        }

        @Throws(ApiConnectionException::class, ApiDataException::class)
        private fun unpackResult(first: String): String {
            val buf = StringBuilder(first)
            line = null
            while (hasNextLine()) {
                val peek = peekLine()
                if (!(peek.startsWith("!") || peek.startsWith("=") || peek.startsWith(".tag="))) {
                    nextLine()
                    buf.append("\n")
                    buf.append(line)
                } else {
                    break
                }
            }
            return buf.toString()
        }

        @Throws(MikrotikApiException::class)
        private fun unpackDone(): Done {
            val done = Done(null)
            if (hasNextLine()) {
                nextLine()
                while (!line!!.startsWith("!")) {
                    if (line!!.startsWith(".tag=")) {
                        val parts = line!!.split("=".toRegex(), 2).toTypedArray()
                        if (parts.size == 2) {
                            done.tag = parts[1]
                        }
                    } else if (line!!.startsWith("=ret")) {
                        val parts = line!!.split("=".toRegex(), 3).toTypedArray()
                        if (parts.size == 3) {
                            done.hash = parts[2]
                        } else {
                            throw ApiDataException(String.format("Malformed line '%s'", line))
                        }
                    }
                    if (hasNextLine()) {
                        nextLine()
                    } else {
                        line = null
                        break
                    }
                }
            }
            return done
        }

        @Throws(MikrotikApiException::class)
        private fun unpackError(): Error {
            nextLine()
            val err = Error()
            if (hasNextLine()) {
                while (!line!!.startsWith("!")) {
                    if (line!!.startsWith(".tag=")) {
                        val parts = line!!.split("=".toRegex(), 2).toTypedArray()
                        if (parts.size == 2) {
                            err.tag = parts[1]
                        }
                    } else if (line!!.startsWith("=message=")) {
                        err.message = line!!.split("=".toRegex(), 3).toTypedArray()[2]
                    } else if (line!!.startsWith("=category=")) {
                        err.category = line!!.split("=".toRegex(), 3).toTypedArray()[2].toInt()
                    }
                    if (hasNextLine()) {
                        nextLine()
                    } else {
                        line = null
                        break
                    }
                }
            }
            return err
        }

        private val lines: MutableList<String> = LinkedList()
        private var line: String? = null
    }

    private class SyncListener(val vertx: Vertx) : ResultListener {
        override fun error(ex: MikrotikApiException) {
            stopTimer()
            emitter.fail(ex)
        }

        override fun completed() {
            stopTimer()
            emitter.complete()
        }

        fun completed(done: Done) {
            stopTimer()
            if (done.hash != null) {
                val res = Result()
                res["ret"] = done.hash
                emitter.emit(res)
            }
            emitter.complete()
        }

        override fun receive(result: Map<String, String>) {
            emitter.emit(result)
        }

        @Throws(MikrotikApiException::class)
        fun getResults(timeout: Int): Multi<Map<String, String>> {
            timerId = vertx.setTimer(timeout*1000L) {
                emitter.fail(ApiConnectionException("Command timed out after $timeout ms"))
            }
            return results

            /*try {
                synchronized(this) {
                    // don't wait if we already have a result.
                    var waitTime = timeout
                    while (!complete && waitTime > 0) {
                        val start = System.currentTimeMillis()
                        //wait(waitTime.toLong())
                        waitTime = waitTime - (System.currentTimeMillis() - start).toInt()
                        if (waitTime <= 0 && !complete) {
                            err = ApiConnectionException(String.format("Command timed out after %d ms", timeout))
                        }
                    }
                }
            } catch (ex: InterruptedException) {
                throw ApiConnectionException(ex.message, ex)
            }
            if (err != null) {
                throw MikrotikApiException(err!!.message, err)
            }
            return results*/
        }

        private var timerId = 0L
        private fun stopTimer() {
            if (timerId!=0L) {
                vertx.cancelTimer(timerId)
            }
        }

        private lateinit var emitter: MultiEmitter<in Map<String, String>>
        private val results = Multi.createFrom().emitter<Map<String, String>> {
            emitter = it
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncApiConnection::class.java)
        /**
         * Create a new API connection to the give device on the supplied port
         *
         * @param fact The socket factory used to construct the connection socket.
         * @param host The host to which to connect.
         * @param port The TCP port to use.
         * @return The ApiConnection
         * @throws me.legrange.mikrotik.ApiConnectionException Thrown if there is a
         * problem connecting
         */
        @Throws(ApiConnectionException::class)
        fun connect(fact: AsyncSocketFactory, host: String, port: Int = ApiConnection.DEFAULT_PORT): Uni<AsyncApiConnection> {
            val con = AsyncApiConnection(fact.vertx)
            return Uni.createFrom().emitter { em ->
                con.open(host, port, fact)
                    .subscribe().with ({
                        em.complete(con)
                    }) {
                        log.error(it.message)
                        em.fail(it)
                    }
            }
        }
    }


}