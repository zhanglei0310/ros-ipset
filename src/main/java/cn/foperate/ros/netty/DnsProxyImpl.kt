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
import java.lang.RuntimeException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

/**
 * @author [Norman Maurer](mailto:nmaurer@redhat.com)
 */
class DnsProxyImpl(vertx: VertxInternal, options: DnsClientOptions): DnsProxy {
    private val vertx: VertxInternal
    private val inProgressMap = mutableMapOf<Int, Query>()
    private val dnsServer: InetSocketAddress
    private val actualCtx: ContextInternal
    private val channel: DatagramChannel
    private val options: DnsClientOptions

    init {
        requireNotNull(options.host){ "no null host accepted" }
        this.options = DnsClientOptions(options)
        //val creatingContext = vertx.context
        dnsServer = InetSocketAddress(options.host, options.port)
        require(!dnsServer.isUnresolved) { "Cannot resolve the host to a valid ip address" }
        this.vertx = vertx
        val transport = vertx.transport()
        actualCtx = vertx.orCreateContext
        channel =
            transport.datagramChannel(if (dnsServer.address is Inet4Address) InternetProtocolFamily.IPv4 else InternetProtocolFamily.IPv6)
        val bufAllocator = channel.config().getRecvByteBufAllocator<MaxMessagesRecvByteBufAllocator>()
        bufAllocator.maxMessagesPerRead(1)
        channel.config().setOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, true) // 用connect代替
        channel.config().allocator = PartialPooledByteBufAllocator.INSTANCE
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
            }
        })
        channel.connect(dnsServer)
    }

    private inner class Query(dnsQuestion: DnsQuestion) {
        var promise: Promise<List<DnsRawRecord>> = actualCtx.nettyEventLoop().newPromise()
        var timerID: Long = 0
        var msg: DatagramDnsQuery = DatagramDnsQuery(
            null,
            dnsServer,
            ThreadLocalRandom.current().nextInt()
        ) //.setRecursionDesired(options.isRecursionDesired)

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
                    //val raw = DefaultDnsRawRecord(answer.name(), answer.type(), answer.timeToLive(), answer.)
                    answers.add(answer.copy())
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
                //handler.handle(answers)
                promise.setSuccess(answers)
            } else {
                promise.setFailure(RuntimeException("服务器错误"))
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
                    actualCtx.emit(future.cause()) { cause: Throwable ->
                        fail(
                            cause
                        )
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
