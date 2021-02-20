package cn.foperate.ros.api

import cn.foperate.ros.munity.AsyncSocketFactory
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Promise
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.core.net.NetSocket
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.UnknownHostException

open class RxApiConnection(val vertx: Vertx, val host: String): AutoCloseable {

    enum class ConnectionState {
        Connecting,
        Connected,
        Disconnected
    }
    private var state = ConnectionState.Connecting
    fun isOnline() = state!=ConnectionState.Disconnected
    private val pending = Promise.promise<Boolean>()

    private lateinit var sock: NetSocket
    private var _tag = 0
    var timeout = DEFAULT_COMMAND_TIMEOUT

    private val defaultListener = object:ResponseListener {
        override fun receive(result: Response) {
            log.debug(result.toString())
        }

        override fun error(ex: MikrotikApiException) {
            log.error(ex.message, ex)
            if (ex is ApiConnectionException) {
                close()
            }
        }

        override fun completed(result: Response) {
            log.debug(result.toString())
        }
    }
    // Responser在建立对象时就随之建立，准备处理任何后续的命令返回
    private val responser: Responser = Responser(defaultListener)

    private fun nextTag(): String {
        _tag++
        return Integer.toHexString(_tag)
    }

    fun cancel(tag: String) {
        executeAsUni(Command("/cancel tag=$tag")).subscribe().with {  }
        responser.forget(tag)
    }

    open fun executeAsUni(cmd: Command, timeout: Int = this.timeout):Uni<Map<String, String>> {
        return Uni.createFrom().emitter { em ->
            val tag = nextTag()
            cmd.tag = tag

            val timerId = vertx.setTimer(timeout*1000L) {
                cancel(tag)
                em.fail(ApiTimeoutException("Command timed out after $timeout ms: $it"))
            }
            log.debug("启动了定时器$timerId")

            val listener = object:ResponseListener {
                var sent = false
                override fun receive(result: Response) {
                    vertx.cancelTimer(timerId)
                    em.complete(result.data)
                    responser.forget(tag)
                    sent = true
                }

                override fun error(ex: MikrotikApiException) {
                    vertx.cancelTimer(timerId)
                    em.fail(ex)
                    responser.forget(tag)
                }

                override fun completed(result: Response) {
                    vertx.cancelTimer(timerId)
                    if (result.data.isNotEmpty()) {
                        em.complete(result.data)
                    } else if (!sent) {
                        em.complete(mapOf())
                    }
                    responser.forget(tag)
                }
            }
            execute(cmd, listener)
        }
    }

    open fun executeAsMulti(cmd: Command, timeout: Int = this.timeout): Multi<Map<String, String>> {
        return Multi.createFrom().emitter { em ->
            val tag = nextTag()
            cmd.tag = tag

            val timerId = vertx.setTimer(timeout*1000L) {
                cancel(tag)
                em.fail(ApiTimeoutException("Command timed out after $timeout ms: $it"))
            }
            log.debug("启动了定时器$timerId")

            val listener = object:ResponseListener {
                override fun receive(result: Response) {
                    vertx.cancelTimer(timerId)
                    em.emit(result.data)
                }

                override fun error(ex: MikrotikApiException) {
                    vertx.cancelTimer(timerId)
                    em.fail(ex)
                    responser.forget(tag)
                }

                override fun completed(result: Response) {
                    vertx.cancelTimer(timerId)
                    if (result.data.isNotEmpty()) {
                        em.emit(result.data)
                    }
                    em.complete()
                    responser.forget(tag)
                }
            }
            execute(cmd, listener)
        }
    }

    /****
     * 实际的命令处理请求，包括Multi请求和Uni请求。
     * 此时，命令应该已经包括了对应的tag信息。
     */
    @Throws(MikrotikApiException::class)
    private fun execute(cmd: Command, lis: ResponseListener): String {
        responser.waiting(cmd.tag, lis)
        try {
            val buffer = Buffer.buffer()
            cmd.toSentence().forEach {
                // log.debug(it)
                val bytes = it.toByteArray()
                assert(bytes.size<0x4000) // 根据分析，命令中单个word有16384的长度已经足够了
                if (bytes.size<128) {
                    buffer.appendByte(bytes.size.toByte())
                } else if (bytes.size<0x4000) {
                    val len = bytes.size or 0x8000
                    buffer.appendShort(len.toShort())
                }
                buffer.appendBytes(bytes)
            }
            buffer.appendByte(0x00)

            sock.write(buffer).subscribe().with {
                log.debug("命令已发送")
            }
        } catch (ex: UnsupportedEncodingException) {
            throw ApiDataException(ex.message, ex)
        } catch (ex: IOException) {
            throw ApiConnectionException(ex.message, ex)
        }
        return cmd.tag
    }

    fun login(username: String, password: String): Uni<Map<String, String>> {
        if (username.trim { it <= ' ' }.isEmpty()) {
            return Uni.createFrom().failure(ApiConnectionException("API username cannot be empty"))
        }
        val cmd = Command(
            "/login", params = mapOf(
                "name" to username,
                "password" to password
            )
        )
        return executeAsUni(cmd, timeout = timeout)
            .onItem().transformToUni { res ->
                if (res.containsKey("ret")) {
                    log.debug("似乎需要MD5校验: $res")

                    val hash = res["ret"] as String
                    val buffer = Buffer.buffer()
                        .appendByte(0x00)
                        .appendString(password)
                        .appendBytes(Hex.decodeHex(hash))
                    val digest = DigestUtils.md5Hex(buffer.bytes)
                    return@transformToUni executeAsUni(
                        Command(
                            "/login", params = mapOf(
                                "name" to username,
                                "response" to "00$digest"
                            )
                        )
                    )
                } else {
                    log.debug("成功登陆，没有MD5校验")
                    return@transformToUni Uni.createFrom().item(res)
                }
            }
    }

    /**
     * Start the API. Connects to the Mikrotik
     */
    @Throws(ApiConnectionException::class)
    fun open(host: String, port: Int, fact: AsyncSocketFactory, timeout: Int=30):Uni<Void> {
        return Uni.createFrom().emitter { emitter ->
            fact.createSocket(host, port).subscribe().with ({
                log.debug("已连接到服务器")
                this.state = ConnectionState.Connected
                this.sock = it
                this.timeout = timeout

                Multi.createFrom().emitter<List<String>> { em ->
                    val parser = ProtocolParser(emitter = em)
                    it.handler(parser)
                    it.closeHandler {
                        parser.handleEnd()
                        close()
                    }
                    it.exceptionHandler { e ->
                        // 这里是一个网络错误，会被下面一条指令转换为ApiConnection错误并抛出
                        parser.handleException(e)
                        // 该错误将被responser处理，并导致RxApiConnection关闭
                        // 无论如何，首先把TCP连接关掉
                        it.closeAndForget()
                    }
                    emitter.complete(null)
                }.subscribe().with(responser, responser::handleError)
            }) {
                log.error(it.toString(), it.message)
                close()
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


    override fun close() {
        log.debug("收到Close请求")
        if (isOnline()) { // 避免重复被关闭
            // Exception().printStackTrace()
            state = ConnectionState.Disconnected
            sock.closeAndForget()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RxApiConnection::class.java)

        /**
         * default TCP port used by Mikrotik API
         */
        const val DEFAULT_PORT = 8728

        /**
         * default TCP TLS port used by Mikrotik API
         * for the moment, TLS connection is not supported
         */
        const val DEFAULT_TLS_PORT = 8729

        /**
         * default connection timeout to use when opening the connection
         */
        const val DEFAULT_CONNECTION_TIMEOUT = 60000

        /**
         * default command timeout used for synchronous commands
         */
        const val DEFAULT_COMMAND_TIMEOUT = 60000

        private var activeConnection: RxApiConnection? = null

        /**
         * Return the connection if available , or create a new API connection to the give device on the supplied port
         *
         * @param fact The socket factory used to construct the connection socket.
         * @param options Optons for this ApiConnection
         * @return The ApiConnection Future
         * @throws ApiConnectionException Thrown if there is any, guess mostly server ip error
         * problem connecting
         */
        @Throws(ApiConnectionException::class)
        fun connection(fact: AsyncSocketFactory, options: ApiConnectionOptions): Uni<RxApiConnection> {
            if (activeConnection!=null && activeConnection!!.isOnline()) {
                require(options.host== activeConnection!!.host) {
                    "Wrong usage, try another API"
                }
                val connection = activeConnection as RxApiConnection
                return Uni.createFrom().emitter { em ->
                    connection.pending.future()
                        .subscribe().with { re ->
                            if (re) {
                                em.complete(connection)
                            } else {
                                em.fail(ApiConnectionException("服务器连接失败"))
                            }
                        }
                }
            }

            activeConnection = RxApiConnection(fact.vertx, options.host)
            return Uni.createFrom().emitter { em ->
                val conn = activeConnection as RxApiConnection
                conn.open(options.host, options.port, fact)
                    .onItem().transformToUni { _ ->
                        conn.login(options.username, options.password)
                    }.subscribe().with ({
                        log.info("Login successed")
                        em.complete(conn)
                        conn.pending.complete(true)
                    }) {
                        log.error(it.message)
                        em.fail(it)
                        conn.pending.complete(false)
                    }
            }
        }

    }
}
