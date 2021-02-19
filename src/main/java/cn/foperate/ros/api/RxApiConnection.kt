package cn.foperate.ros.api

import cn.foperate.ros.munity.AsyncSocketFactory
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.core.net.NetSocket
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.UnknownHostException


open class RxApiConnection(val vertx: Vertx): AutoCloseable {

    var connected = false
    private lateinit var sock: NetSocket
    private var _tag = 0
    private lateinit var responser: Responser
    var timeout = DEFAULT_COMMAND_TIMEOUT

    private fun nextTag(): String {
        _tag++
        return Integer.toHexString(_tag)
    }

    fun cancel(tag: String) {
        executeAsMulti(Command("/cancel tag=$tag")).subscribe().with {  }
    }

    open fun executeAsMulti(cmd: Command, timeout: Int = this.timeout): Multi<Map<String, String>> {
        return Multi.createFrom().emitter { em ->
            val tag = nextTag()
            cmd.tag = tag

            val timerId = vertx.setTimer(timeout*1000L) {
                cancel(tag)
                em.fail(ApiTimeoutException("Command timed out after $timeout ms: $it"))
            }
            log.info("启动了定时器$timerId")

            val listener = object:ResponseListener {
                override fun receive(result: Response) {
                    vertx.cancelTimer(timerId)
                    em.emit(result.data)
                }

                override fun error(ex: MikrotikApiException) {
                    vertx.cancelTimer(timerId)
                    em.fail(ex)
                }

                override fun completed(result: Response) {
                    vertx.cancelTimer(timerId)
                    if (result.data.isNotEmpty()) {
                        em.emit(result.data)
                    }
                    em.complete()
                }
            }
            execute(cmd, listener)
        }
    }

    @Throws(MikrotikApiException::class)
    private fun execute(cmd: Command, lis: ResponseListener): String {
        //listeners[tag] = lis
        responser.waiting(cmd.tag, lis)
        try {
            val buffer = Buffer.buffer()
            cmd.toSentence().forEach {
                log.debug(it)
                val bytes = it.toByteArray()
                assert(bytes.size<128)
                buffer.appendByte(bytes.size.toByte())
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

    fun login(username: String, password: String): Uni<Void> {
        return Uni.createFrom().emitter { emitter ->
            if (username.trim { it <= ' ' }.isEmpty()) {
                emitter.fail(ApiConnectionException("API username cannot be empty"))
            }
            val cmd = Command("/login", params = mapOf(
                "name" to username,
                "password" to password
            ))
            var hasHash = false
            executeAsMulti(cmd, timeout = timeout)
                .onItem().invoke { res ->
                    log.debug("似乎需要MD5校验")
                    hasHash = true

                    if (res.containsKey("ret")) {
                        /*val hash = res["ret"] as String
                        var chal = Util.hexStrToStr("00") + String(password.toCharArray()) + Util.hexStrToStr(hash)
                        chal = Util.hashMD5(chal)
                        executeAsMulti("/login name=$username response=00$chal")
                            .subscribe().with({
                                emitter.complete(null)
                            }) { e ->
                                emitter.fail(e)
                            } */
                        log.error(res["ret"])
                    }
                }
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

    /**
     * Start the API. Connects to the Mikrotik
     */
    @Throws(ApiConnectionException::class)
    fun open(host: String, port: Int, fact: AsyncSocketFactory, timeout: Int=30):Uni<Void> {
        return Uni.createFrom().emitter { emitter ->
            fact.createSocket(host, port).subscribe().with ({
                log.info("已连接到服务器")
                this.connected = true
                this.sock = it
                this.timeout = timeout
                it.closeHandler {
                    connected = false
                }
                //this.processor = Processor()
                //this.reader = Reader()
                responser = Responser(defaultListener = object:ResponseListener {
                    override fun receive(result: Response) {
                        log.debug(result.toString())
                    }

                    override fun error(ex: MikrotikApiException) {
                        log.error(ex.message, ex)
                        if (ex !is ApiCommandException) {
                            connected = false
                            it.closeAndForget()
                        }
                    }

                    override fun completed(result: Response) {
                        log.debug(result.toString())
                    }

                })
                Multi.createFrom().emitter<List<String>> { em ->
                    val parser = ProtocolParser(emitter = em)
                    it.handler(parser)
                    it.closeHandler(parser::handleEnd)
                    it.exceptionHandler { e ->
                        parser.handleException(e)
                        connected = false
                        it.closeAndForget()
                    }
                    emitter.complete(null)
                }.subscribe().with(responser)
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

    override fun close() {
        if (!connected) {
            throw ApiConnectionException("Not/no longer connected to remote Mikrotik")
        }
        connected = false

        try {
            sock.close().subscribe().with {  }
        } catch (ex: IOException) {
            throw ApiConnectionException("Error closing socket: ${ex.message}", ex)
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

        /**
         * Create a new API connection to the give device on the supplied port
         *
         * @param fact The socket factory used to construct the connection socket.
         * @param host The host to which to connect.
         * @param port The TCP port to use.
         * @return The ApiConnection
         * @throws ApiConnectionException Thrown if there is a
         * problem connecting
         */
        @Throws(ApiConnectionException::class)
        fun connect(fact: AsyncSocketFactory, host: String, port: Int = DEFAULT_PORT): Uni<RxApiConnection> {
            val con = RxApiConnection(fact.vertx)
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
