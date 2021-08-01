package cn.foperate.ros.netty

import io.netty.channel.*
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.InternetProtocolFamily
import io.netty.handler.codec.dns.*
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.Promise
import io.vertx.core.Future
import io.vertx.core.VertxException
import io.vertx.core.dns.DnsClientOptions
import io.vertx.core.dns.DnsResponseCode
import io.vertx.core.impl.ContextInternal
import io.vertx.core.impl.VertxInternal
import io.vertx.core.net.impl.PartialPooledByteBufAllocator
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

/**
 * @author [Norman Maurer](mailto:nmaurer@redhat.com)
 */
class DnsProxyImpl(private val vertx: VertxInternal, private val options: DnsClientOptions): DnsProxy {
    private val inProgressMap = mutableMapOf<Int, Query>()
    private lateinit var dnsServer: InetSocketAddress
    private lateinit var actualCtx: ContextInternal
    private lateinit var channel: DatagramChannel

    override fun connect(localPort: Int): DnsProxy {
        require(options.host.isNotBlank()){ "必须有服务器的域名" }
        vertx.context
        dnsServer = InetSocketAddress(options.host, options.port)
        require(!dnsServer.isUnresolved) { "域名服务器非法" }
        val transport = vertx.transport()
        actualCtx = vertx.orCreateContext
        val channel =
            transport.datagramChannel(if (dnsServer.address is Inet4Address) InternetProtocolFamily.IPv4 else InternetProtocolFamily.IPv6)
        //channel.config().setOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, true) // 用bind代替
        val bufAllocator = channel.config().getRecvByteBufAllocator<MaxMessagesRecvByteBufAllocator>()
        bufAllocator.maxMessagesPerRead(1)
        channel.config().allocator = PartialPooledByteBufAllocator.INSTANCE
        channel.config().isAutoClose = false
        actualCtx.nettyEventLoop().register(channel)
        if (options.logActivity) {
            channel.pipeline().addLast("logging", LoggingHandler())
        }
        channel.pipeline().addLast(DatagramDnsQueryEncoder())
        channel.pipeline().addLast(DatagramDnsResponseDecoder())
        channel.pipeline().addLast(object : SimpleChannelInboundHandler<DatagramDnsResponse>() {
            @Throws(Exception::class)
            override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramDnsResponse) {
                val query = inProgressMap[msg.id()]
                query?.handle(msg)
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                log.error(cause.message, cause)
                ctx.fireExceptionCaught(cause) // FIXME 没出现过，还不清楚是否需要继续抛出
            }
        })
        this.channel = channel
        channel.bind(InetSocketAddress("0.0.0.0", localPort)) // FIXME 最终也许要判断是否还需要这种处理
        return this
    }

    override fun close(): Future<Void> { // 没有用到过，实现总没问题
        val promise = actualCtx.promise<Void>()
        channel.close().addListener(promise)
        return promise.future()
    }

    private inner class Query(dnsQuestion: DnsQuestion) {
        var promise: Promise<List<DnsRawRecord>> = actualCtx.nettyEventLoop().newPromise()
        var timerID: Long = 0
        var msg: DatagramDnsQuery = DatagramDnsQuery(
            null,
            dnsServer,
            ThreadLocalRandom.current().nextInt()
        ) .setRecursionDesired(options.isRecursionDesired)

        init {
            log.debug(dnsQuestion.toString())
            msg.addRecord(DnsSection.QUESTION, dnsQuestion)
            promise = actualCtx.nettyEventLoop().newPromise()
        }

        private fun fail(cause: Throwable) {
            inProgressMap.remove(msg.id())
            if (timerID >= 0) {
                vertx.cancelTimer(timerID)
            }
            promise.setFailure(cause)
        }

        fun handle(resp: DatagramDnsResponse) {
            inProgressMap.remove(resp.id())
            if (timerID >= 0) {
                vertx.cancelTimer(timerID)
            }
            val code: DnsResponseCode = DnsResponseCode.valueOf(resp.code().intValue())
            if (code==DnsResponseCode.NOERROR) {
                val answers = mutableListOf<DnsRawRecord>()
                for (idx in 0 until resp.count(DnsSection.ANSWER)) {
                    val answer = resp.recordAt<DnsRawRecord>(DnsSection.ANSWER, idx)
                    // FIXME 目前一律按照10分钟的寿命来处理请求，是否合理？
                    val raw = DefaultDnsRawRecord(answer.name(), answer.type(), 600L, answer.content().retain())
                    answers.add(raw)
                }
                /*for (idx in 0 until resp.count(DnsSection.AUTHORITY)) {
                    val answer = resp.recordAt<DnsRawRecord>(DnsSection.AUTHORITY, idx)
                    //val raw = DefaultDnsRawRecord(answer.name(), answer.type(), answer.timeToLive(), answer.)
                    answers.add(answer.copy())
                }
                for (idx in 0 until resp.count(DnsSection.ADDITIONAL)) {
                    val answer = resp.recordAt<DnsRawRecord>(DnsSection.ADDITIONAL, idx)
                    //val raw = DefaultDnsRawRecord(answer.name(), answer.type(), answer.timeToLive(), answer.)
                    answers.add(answer.copy())
                }*/
                promise.setSuccess(answers)
            } else {
                promise.setFailure(RuntimeException("服务器错误: $code"))
            }
        }

        fun run() {
            val msgId = msg.id()
            inProgressMap[msgId] = this
            timerID = vertx.setTimer(options.queryTimeout) {
                timerID = -1
                actualCtx.runOnContext { fail(VertxException("DNS query timeout for $msgId")) }
            }
            channel.writeAndFlush(msg).addListener(ChannelFutureListener { future: ChannelFuture ->
                if (!future.isSuccess) {
                    actualCtx.emit(future.cause()) { cause -> // 如果出现错误
                        fail(cause)
                    }
                }
            })
        }
    }

    override fun proxy(question: DnsQuestion): Future<List<DnsRawRecord>> {
        val ctx = vertx.orCreateContext
        val promise = ctx.promise<List<DnsRawRecord>>()
        val el = actualCtx.nettyEventLoop()
        val query = Query(question)
        query.promise.addListener(promise)
        if (el.inEventLoop()) {
            query.run()
        } else {
            el.execute { query.run() }
        }
        return promise.future()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DnsProxyImpl::class.java)
    }
}
