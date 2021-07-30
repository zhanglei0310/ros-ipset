package cn.foperate.ros.verticle

import cn.foperate.ros.netty.*
import cn.foperate.ros.pac.DomainUtil
import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.buffer.Unpooled
import io.netty.handler.codec.dns.*
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.dns.DnsClient
import io.vertx.core.dns.impl.decoder.RecordDecoder
import io.vertx.core.eventbus.EventBus
import io.vertx.core.impl.VertxInternal
import io.vertx.kotlin.core.dns.dnsClientOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/*****
 * 进行DNS过滤、解析和转发，并请求将结果保存到ROS中。
 * 改用Kotlin协程来实现，期望语义上更加简洁清晰。
 * @author Aston Mei
 * @since 2021-07-25
 */
class NettyDnsVerticle : CoroutineVerticle() {
    private lateinit var backupClient: DnsProxy
    private lateinit var proxyClient: DnsProxy
    private lateinit var dnsProxy: DnsClient
    private lateinit var dnsServer: DnsServer
    private val aCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(1000)
        .evictionListener { _: String?, value: Future<List<DnsRawRecord>>?, _ ->
            value?.onSuccess { list ->
                list.forEach(DnsRawRecord::release)
            }
        }
        .build<String, Future<List<DnsRawRecord>>>()

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String
    private lateinit var blockAddress: InetAddress

    private lateinit var eb: EventBus

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
            backupClient = vertx.createDnsProxy(dnsClientOptionsOf(
                    host = fallback,
                    port = 53,
                    recursionDesired = true // 需要这个选项，否则某些域名的解析会失败
                ))

            proxyClient = vertx.createDnsProxy(dnsClientOptionsOf(
                    host = remote,
                    port = remotePort,
                    recursionDesired = true
                ))

            dnsProxy = vertx.createDnsClient(remotePort, remote)

            dnsServer = DnsServerImpl.create(vertx as VertxInternal, dnsServerOptionsOf(
                port = localPort,
                host = "0.0.0.0"
            )).handler {
                val question = it.recordAt<DnsQuestion>(DnsSection.QUESTION)
                val response = DatagramDnsResponse(it.recipient(), it.sender(), it.id())
                handleDnsQuery(question, response)
            }
            dnsServer.listen(localPort, "0.0.0.0").await()
            log.debug("UDP服务已经启动")
        } catch (e: Exception) {
            log.error(e.message, e)
        }
    }

    override suspend fun stop() {
        dnsServer.close()
    }

    fun handleDnsQuery(dnsQuestion: DnsQuestion, response: DatagramDnsResponse) {
        try {
            val questionName = dnsQuestion.name()
            val questionType = dnsQuestion.type().name()
            response.addRecord(DnsSection.QUESTION, dnsQuestion)
            log.debug("查询的域名：$dnsQuestion")
            when {
                DomainUtil.match(questionName) -> { // A类查询，在查询名单，且不在逃逸名单里
                    log.debug("gfwlist hint")
                    log.debug(dnsQuestion.toString())
                    val queryKey = "$questionName($questionType)"

                    val future: Future<List<DnsRawRecord>> = aCache.getIfPresent(queryKey) ?: run {
                        val promise = Promise.promise<List<DnsRawRecord>>()
                        proxyClient.proxy(dnsQuestion).onSuccess {
                            promise.tryComplete(it)
                        }.onFailure {
                            promise.tryFail(it)
                        }
                        promise.future()
                    }
                    future.onSuccess {
                        log.debug("Success with ${it.size}")
                        if (it.isNotEmpty()) {
                            if (dnsQuestion.type()== DnsRecordType.A) {
                                aCache.put(queryKey, future)
                            }
                            val aRecordIps = mutableListOf<String>()
                            it.stream().limit(14).forEach { answer ->
                                response.addRecord(DnsSection.ANSWER, answer.retain())
                                if (answer.type()==DnsRecordType.A) {
                                    val address = RecordDecoder.decode<String>(answer)
                                    aRecordIps.add(address)
                                }
                            }
                            dnsServer.send(response)
                            if (aRecordIps.isNotEmpty()) {
                                eb.request<Long>(
                                    RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                                        "domain" to questionName,
                                        "address" to aRecordIps
                                    )
                                ).onSuccess {
                                    //log.debug("call success")
                                }.onFailure { err ->
                                    log.error(err.message)
                                }
                            }
                        }
                    }.onFailure { e ->
                        log.error("$questionName 解析失败 ${e.message}", e)
                        // 但是请求失败后，会从备用服务器解析结果
                        if (dnsQuestion.type()== DnsRecordType.A) {
                            dnsProxy.resolveA(questionName).onSuccess {
                                log.debug(it.toString())
                                for (answer in it) {
                                    val ip = answer.split(".").map { sec ->
                                        sec.toUByte().toByte()
                                    }.toByteArray()
                                    response.addRecord(DnsSection.ANSWER,
                                        DefaultDnsRawRecord(questionName, DnsRecordType.A, 600, Unpooled.wrappedBuffer(ip))
                                    )
                                }
                                dnsServer.send(response)
                            }.onFailure {
                                log.error(it.message)
                            }
                        }
                    }
                }
                DomainUtil.matchBlock(questionName) -> {
                    log.debug("adBlock matched")
                    //val reply = blockMessage(message)
                    //serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                    val buf = Unpooled.wrappedBuffer(blockAddress.address)
                    val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 600, buf)
                    response.addRecord(DnsSection.ANSWER, queryAnswer)
                    dnsServer.send(response)
                }
                else -> {
                    backupClient.proxy(dnsQuestion).onSuccess {
                        log.debug(it.toString())
                        for (answer in it) {
                            response.addRecord(DnsSection.ANSWER, answer)
                        }
                        dnsServer.send(response)
                    }.onFailure {  }
                }
            }
        } catch (e: Exception) {
            log.error("异常了：$e", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NettyDnsVerticle::class.java)
    }
}