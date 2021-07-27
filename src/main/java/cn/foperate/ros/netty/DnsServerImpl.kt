package cn.foperate.ros.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.MaxMessagesRecvByteBufAllocator
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.InternetProtocolFamily
import io.netty.handler.codec.dns.DatagramDnsQuery
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder
import io.netty.handler.codec.dns.DatagramDnsResponse
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.GenericFutureListener
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.impl.ContextInternal
import io.vertx.core.impl.VertxInternal
import io.vertx.core.net.SocketAddress
import io.vertx.core.net.impl.ConnectionBase
import io.vertx.core.net.impl.PartialPooledByteBufAllocator
import io.vertx.core.net.impl.VertxHandler
import io.vertx.core.spi.metrics.DatagramSocketMetrics
import io.vertx.core.spi.metrics.Metrics
import io.vertx.core.spi.metrics.MetricsProvider
import io.vertx.core.spi.metrics.NetworkMetrics
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.*

/**
 * @author [Norman Maurer](mailto:nmaurer@redhat.com)
 */
class DnsServerImpl private constructor(vertx: VertxInternal, options: DatagramSocketOptions) :
    MetricsProvider, DnsServer {
    private val context: ContextInternal
    private val metrics: DatagramSocketMetrics?
    private val channel: DatagramChannel
    private var packetHandler: Handler<DatagramDnsQuery>? = null
    private var endHandler: Handler<Void>? = null
    private var exceptionHandler: Handler<Throwable>? = null
    private var demand: Long

    init {
        val transport = vertx.transport()
        val channel =
            transport.datagramChannel(if (options.isIpV6) InternetProtocolFamily.IPv6 else InternetProtocolFamily.IPv4)
        transport.configure(channel, DatagramSocketOptions(options))

        val context = vertx.orCreateContext
        channel.config().setOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, true)
        val bufAllocator = channel.config().getRecvByteBufAllocator<MaxMessagesRecvByteBufAllocator>()
        bufAllocator.maxMessagesPerRead(1)

        // FIXME  也许没有用
        channel.config().allocator = PartialPooledByteBufAllocator.INSTANCE

        context.nettyEventLoop().register(channel)
        if (options.logActivity) {
            channel.pipeline().addLast("logging", LoggingHandler())
        }
        val metrics = vertx.metricsSPI()
        this.metrics = metrics?.createDatagramSocketMetrics(options)
        this.channel = channel
        this.context = context
        demand = Long.MAX_VALUE
    }

    private fun initial() {
        channel.pipeline().addLast(DatagramDnsQueryDecoder())
        channel.pipeline().addLast(DatagramDnsResponseEncoder())
        channel.pipeline().addLast("handler", VertxHandler.create { ctx -> Connection(context, ctx)} )
    }

    override fun listen(port: Int, address: String, handler: Handler<AsyncResult<DnsServer>>): DnsServer {
        Objects.requireNonNull(handler, "no null handler accepted")
        listen(SocketAddress.inetSocketAddress(port, address)).onComplete(handler)
        return this
    }

    override fun listen(port: Int, address: String): Future<DnsServer> {
        return listen(SocketAddress.inetSocketAddress(port, address))
    }

    @Synchronized
    override fun handler(handler: Handler<DatagramDnsQuery>): DnsServer {
        packetHandler = handler
        return this
    }

    override fun endHandler(handler: Handler<Void>): DnsServer {
        endHandler = handler
        return this
    }

    override fun exceptionHandler(handler: Handler<Throwable>): DnsServer {
        exceptionHandler = handler
        return this
    }

    private fun listen(local: SocketAddress): Future<DnsServer> {
        val resolver = context.owner().addressResolver()
        val promise = context.promise<Void>()
        val f1 = resolver.resolveHostname(context.nettyEventLoop(), local.host())
        f1.addListener(GenericFutureListener { res1: io.netty.util.concurrent.Future<InetSocketAddress> ->
            if (res1.isSuccess) {
                val f2 = channel.bind(InetSocketAddress(res1.now.address, local.port()))
                if (metrics != null) {
                    f2.addListener(GenericFutureListener { res2: io.netty.util.concurrent.Future<Void> ->
                        if (res2.isSuccess) {
                            metrics.listening(local.host(), localAddress())
                        }
                    })
                }
                f2.addListener(promise)
            } else {
                promise.fail(res1.cause())
            }
        })
        return promise.future().map(this)
    }

    /*@Synchronized
    override fun pause(): DnsServer {
        if (demand > 0L) {
            demand = 0L
            channel.config().isAutoRead = false
        }
        return this
    }

    @Synchronized
    override fun resume(): DnsServer {
        if (demand == 0L) {
            demand = Long.MAX_VALUE
            channel.config().isAutoRead = true
        }
        return this
    }

    @Synchronized
    override fun fetch(amount: Long): DnsServer {
        require(amount >= 0L) { "Illegal fetch $amount" }
        if (amount > 0L) {
            if (demand == 0L) {
                channel.config().isAutoRead = true
            }
            demand += amount
            if (demand < 0L) {
                demand = Long.MAX_VALUE
            }
        }
        return this
    }

    override fun sender(port: Int, host: String): WriteStream<DatagramDnsResponse> {
        Arguments.requireInRange(port, 0, 65535, "port p must be in range 0 <= p <= 65535")
        Objects.requireNonNull(host, "no null host accepted")
        return PacketWriteStreamImpl(this, port, host)
    }*/

    override fun localAddress(): SocketAddress {
        return context.owner().transport().convert(channel.localAddress())
    }

    override fun close(handler: Handler<AsyncResult<Void>>) {
        val future = close()
        if (handler != null) {
            future.onComplete(handler)
        }
    }

    @Synchronized
    override fun close(): Future<Void> {
        // make sure everything is flushed out on close
        if (!channel.isOpen) {
            return context.succeededFuture()
        }
        channel.flush()
        val future = channel.close()
        val promise = context.promise<Void>()
        future.addListener(promise)
        return promise.future()
    }

    override fun isMetricsEnabled(): Boolean {
        return metrics != null
    }

    override fun getMetrics(): Metrics? {
        return metrics
    }

    @Throws(Throwable::class)
    protected fun finalize() {
        // Make sure this gets cleaned up if there are no more references to it
        // so as not to leave connections and resources dangling until the system is shutdown
        // which could make the JVM run out of file handles.
        close()
        //super.finalize()
    }
    
    //override fun writeAndFlush(response: DatagramDnsResponse): ChannelFuture = channel.writeAndFlush(response)

    override fun send(response: DatagramDnsResponse): Future<Void> {
        val promise = context.promise<Void>()
        val f2 = channel.writeAndFlush(response)
        if (metrics != null) {
            f2.addListener { fut: io.netty.util.concurrent.Future<in Void?> ->
                if (fut.isSuccess) {
                    metrics.bytesWritten(
                        null,
                        SocketAddress.inetSocketAddress(response.recipient().port, response.recipient().hostString),
                        1024L // 虚拟的数据
                    )
                }
            }
        }
        f2.addListener(promise)
        return promise.future()
    }

    override fun send(
        response: DatagramDnsResponse,
        handler: Handler<AsyncResult<Void>>?
    ): DnsServer {
        val fut = send(response)
        if (handler != null) {
            fut.onComplete(handler)
        }
        return this
    }

    internal inner class Connection(context: ContextInternal, val channel: ChannelHandlerContext)
        : ConnectionBase(context, channel) {

        override fun metrics(): NetworkMetrics<*>? {
            return metrics
        }

        override fun handleInterestedOpsChanged() {}
        override fun handleException(t: Throwable) {
            super.handleException(t)
            // FIXME
            log.error(t.message, t)
        }

        override fun handleClosed() {
            super.handleClosed()
            var metrics: DatagramSocketMetrics?
            synchronized(this@DnsServerImpl) {
                metrics = this@DnsServerImpl.metrics
            }
            metrics?.close()
            // FIXME
        }

        public override fun handleMessage(msg: Any) {
            if (msg is DatagramDnsQuery) {
                /*val question = msg.recordAt<DnsQuestion>(DnsSection.QUESTION)
                val response = DatagramDnsResponse(msg.recipient(), msg.sender(), msg.id())
                handler.accept(question, response)*/
                packetHandler?.handle(msg)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DnsServerImpl::class.java)
        fun create(vertx: VertxInternal, options: DatagramSocketOptions): DnsServerImpl {
            val socket = DnsServerImpl(vertx, options)
            // Make sure object is fully initiliased to avoid race with async registration
            socket.initial()
            return socket
        }
    }
}
