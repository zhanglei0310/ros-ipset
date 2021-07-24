package cn.foperate.ros.verticle

import cn.foperate.ros.netty.*
import cn.foperate.ros.pac.DomainUtil
import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.MaxMessagesRecvByteBufAllocator
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.InternetProtocolFamily
import io.netty.handler.codec.dns.*
import io.netty.handler.logging.LoggingHandler
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.eventbus.EventBus
import io.vertx.core.impl.VertxInternal
import io.vertx.core.net.impl.PartialPooledByteBufAllocator
import io.vertx.kotlin.core.dns.dnsClientOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class NettyDnsVerticle : CoroutineVerticle() {
    private lateinit var backupClient: DnsProxy
    private lateinit var proxyClient: DnsProxy
    private val aCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(1000)
        .build<String, Future<List<DnsRawRecord>>>()

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String
    private lateinit var blockAddress: InetAddress

    private lateinit var eb: EventBus

    //private val dnsServer: InetSocketAddress
    //private val actualCtx: ContextInternal
    //private lateinit var channel: DatagramChannel
    //private lateinit var options: DnsServerOptions

    override suspend fun start() {
        try {
            localPort = config.getInteger("localPort", localPort)
            remote = config.getString("remote")
            remotePort = config.getInteger("remotePort", remotePort)
            fallback = config.getString("fallback")
            val block = config.getString("blockAddress")
            // 实际不会发生阻塞
            blockAddress = InetAddress.getByName(block)

            eb = vertx.eventBus()
            backupClient = DnsProxyImpl(
                vertx as VertxInternal, dnsClientOptionsOf(
                    host = fallback,
                    port = 53,
                    recursionDesired = true
                )
            )

            proxyClient = DnsProxyImpl(
                vertx as VertxInternal, dnsClientOptionsOf(
                    host = remote,
                    port = remotePort,
                    recursionDesired = true
                )
            )

            /*al server = DnsServer(
                vertx as VertxInternal, dnsServerOptionsOf(
                    port = 53,
                    host = "0.0.0.0"
                ), backupClient
            )
            server.listen()*/
            setupServer(
                dnsServerOptionsOf(
                    port = localPort,
                    host = "0.0.0.0"
                )
            )
            log.debug("UDP服务已经启动")
        } catch (e: Exception) {
            log.error(e.message, e)
        }
    }

    fun setupServer(options: DnsServerOptions) {
        val internal = vertx as VertxInternal
        requireNotNull(options.host) {
            "no null host accepted"
        }
        //this.options = options
        //val creatingContext = internal.context
        val dnsServer = InetSocketAddress(options.host, options.port)
        require(!dnsServer.isUnresolved) { "Cannot resolve the host to a valid ip address" }
        val transport = internal.transport()
        val actualCtx = internal.orCreateContext
        val channel =
            transport.datagramChannel(if (dnsServer.address is Inet4Address) InternetProtocolFamily.IPv4 else InternetProtocolFamily.IPv6)
        //channel.config().setOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, true)
        //channel.config().setOption(ChannelOption.SO_BROADCAST, true)
        val bufAllocator = channel.config().getRecvByteBufAllocator<MaxMessagesRecvByteBufAllocator>()
        bufAllocator.maxMessagesPerRead(1)
        channel.config().allocator = PartialPooledByteBufAllocator.INSTANCE
        actualCtx.nettyEventLoop().register(channel)
        if (options.logActivity) {
            channel.pipeline().addLast("logging", LoggingHandler())
        }
        channel.pipeline().addLast(DatagramDnsQueryDecoder())
        channel.pipeline().addLast(DatagramDnsResponseEncoder())
        channel.pipeline().addLast(object : SimpleChannelInboundHandler<DatagramDnsQuery>(
        ) {
            override fun channelRead0(ctx: ChannelHandlerContext, query: DatagramDnsQuery) {
                val response = DatagramDnsResponse(query.recipient(), query.sender(), query.id())
                try {
                    val dnsQuestion = query.recordAt<DefaultDnsQuestion>(DnsSection.QUESTION)
                    val questionName = dnsQuestion.name()
                    response.addRecord(DnsSection.QUESTION, dnsQuestion)
                    log.debug("查询的域名：$dnsQuestion")
                    when {
                        DomainUtil.match(questionName) -> {
                            log.debug("gfwlist hint")
                            //forwardToRemote(request, questionName, questionType)

                            val future = aCache.get(questionName) {
                                val promise = Promise.promise<List<DnsRawRecord>>()
                                proxyClient.proxy(dnsQuestion).onSuccess {
                                    //log.debug(it.toString())
                                    /*val list = it.map { raw ->
                                        DnsAnswer.fromRawRecord(raw)
                                    }*/
                                    promise.tryComplete(it)
                                }.onFailure {
                                    // 失败的请求，从缓存中去掉该key
                                    aCache.invalidate(questionName)
                                    promise.tryFail(it)
                                }
                                promise.future()
                            }
                            future!!.onSuccess {
                                val aRecordIps = mutableListOf<String>()
                                for (answer in it) {
                                    response.addRecord(DnsSection.ANSWER, answer.copy())
                                    if (answer.type()==DnsRecordType.A) {
                                        //response.addRecord(DnsSection.ANSWER, answer.toRawRecord())
                                        val content = answer.content()
                                        val address = content.getUnsignedByte(0).toString() + "." +
                                            content.getUnsignedByte(1).toString() + "." +
                                            content.getUnsignedByte(2).toString() + "." +
                                                content.getUnsignedByte(3).toString()
                                        /*val address = content[0].toUByte().toString() + "." +
                                                content[1].toUByte().toString() + "." +
                                                content[2].toUByte().toString() + "." +
                                                content[3].toUByte().toString()*/
                                        log.debug(address)
                                        aRecordIps.add(address)
                                    }
                                }
                                ctx.writeAndFlush(response)
                                eb.request<Long>(
                                    RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                                        "domain" to questionName,
                                        "address" to aRecordIps
                                    )
                                ).onSuccess {
                                    log.debug("call success")
                                }.onFailure {
                                    log.error(it.message)
                                }
                            }.onFailure {
                                // 但是请求失败后，会从备用服务器解析结果
                                backupClient.proxy(dnsQuestion).onSuccess {
                                    log.debug(it.toString())
                                    for (answer in it) {
                                        response.addRecord(DnsSection.ANSWER, answer)
                                    }
                                    ctx.writeAndFlush(response)
                                }
                            }
                        }
                        DomainUtil.matchBlock(questionName) -> {
                            log.debug("adBlock matched")
                            //val reply = blockMessage(message)
                            //serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                            val buf = Unpooled.wrappedBuffer(blockAddress.address)
                            val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 10, buf)
                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                            ctx.writeAndFlush(response)
                        }
                        else -> {
                            //forwardToFallback(request, questionName, questionType)
                            // TODO  对于没有的域名采用迭代方式
                            // buf = Unpooled.wrappedBuffer(new byte[] { 127, 0, 0, 1});
                            backupClient.proxy(dnsQuestion).onSuccess {
                                log.debug(it.toString())
                                for (answer in it) {
                                    response.addRecord(DnsSection.ANSWER, answer)
                                }
                                ctx.writeAndFlush(response)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("异常了：$e", e)
                }
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                cause.printStackTrace()
            }
        })
        channel.bind(InetSocketAddress(options.host, options.port))
    }

    companion object {
        private val log = LoggerFactory.getLogger(NettyDnsVerticle::class.java)
    }
}