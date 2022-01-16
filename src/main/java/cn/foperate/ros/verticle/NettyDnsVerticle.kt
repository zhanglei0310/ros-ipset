package cn.foperate.ros.verticle

import cn.foperate.ros.netty.*
import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.service.DnsOverHttpsService
import cn.foperate.ros.service.RestService
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.dns.*
import io.netty.util.NetUtil
import io.vertx.core.Context
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.EventBus
import io.vertx.core.impl.VertxInternal
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import org.slf4j.LoggerFactory
import java.net.InetAddress

/*****
 * 进行DNS过滤、解析和转发，并请求将结果保存到ROS中。
 * 改用Kotlin协程来实现，期望语义上更加简洁清晰。
 * @author Aston Mei
 * @since 2021-07-25
 */
class NettyDnsVerticle : CoroutineVerticle() {
    private lateinit var backupClient: DnsProxy
    private lateinit var proxyClient: DnsProxy
    private lateinit var dnsServer: DnsServer

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String
    private lateinit var blockAddress: InetAddress

    private lateinit var eb: EventBus

    override fun init(vertx: Vertx, context: Context) {
        super.init(vertx, context)

        localPort = config.getInteger("localPort", localPort)
        remote = config.getString("remote")
        remotePort = config.getInteger("remotePort", remotePort)
        fallback = config.getString("direct")
        val block = config.getString("blockAddress", "224.0.0.1")
        // 实际不会发生阻塞
        blockAddress = InetAddress.getByName(block)
    }

    private fun addressToBuffer(address: String): ByteBuf {
        val result = NetUtil.createByteArrayFromIpAddressString(address)/*ByteArray(4)
        address.split(".")
            .forEachIndexed { index, s ->
                result[index] = s.toUByte().toByte()
            }*/
        return Unpooled.wrappedBuffer(result)
    }

    private fun nameToBuffer(name: String): ByteBuf {
        val buffer = Buffer.buffer()
        name.split(".")
            .forEach {
                buffer.appendUnsignedByte(it.length.toShort())
                buffer.appendString(it)
            }
        return buffer.byteBuf
    }

    override suspend fun start() {
        try {
            eb = vertx.eventBus()
            backupClient = vertx.createDnsProxy(dnsProxyOptionsOf(
                    host = fallback,
                    port = 53,
                    recursionDesired = true, // 需要这个选项，否则某些域名的解析会失败
                    localPort = 1053)) // 一种固定端口的尝试，也许会放弃

            proxyClient = vertx.createDnsProxy(dnsProxyOptionsOf(
                    host = remote,
                    port = remotePort,
                    recursionDesired = true,
                    localPort = 1153,
                    timeToLive = 3600L
            ))

            dnsServer = DnsServer.create(vertx as VertxInternal, dnsServerOptionsOf(
                port = localPort,
                host = "0.0.0.0"
            )).handler {
                val question = it.recordAt<DnsQuestion>(DnsSection.QUESTION)
                val response = DatagramDnsResponse(it.recipient(), it.sender(), it.id())
                handleDnsQuery(question, response)
            }
            dnsServer.listen(localPort, "0.0.0.0").await()
            log.debug("UDP服务已经启动")

            RestService.addStaticAddress("1.1.1.1", "DNS")
                .onItem().transformToUni { _ -> RestService.addStaticAddress("9.9.9.9", "DNS") }
                .subscribe().with({
                    log.debug("DNS服务已加到列表中")
                }) { err ->
                    log.error(err.message)
                }

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
                DomainUtil.matchNetflix(dnsQuestion) -> {
                    log.debug("收到一个Netflix请求")
                    log.debug(dnsQuestion.toString())

                    DnsOverHttpsService.queryQuad(questionName, questionType)
                        .subscribe().with { answers ->
                            if (answers.isEmpty) {
                                val dest = response.recipient().address.toString()
                                log.error("$questionName 解析失败: $dest")
                            } else {
                                val aRecordIps = jsonArrayOf()
                                answers.forEach { answer -> // 在Alpine上会遇到奇怪的现象会大分片失败，存疑。
                                    answer as JsonObject

                                    when (val type = answer.getInteger("type")){
                                        1 -> {
                                            val ip = answer.getString("data")
                                            val buf = addressToBuffer(ip)
                                            val queryAnswer = DefaultDnsRawRecord(answer.getString("name"), DnsRecordType.A, 600, buf)
                                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                                            aRecordIps.add(ip)
                                        }
                                        5 -> {
                                            val str = answer.getString("data")
                                            val buf = nameToBuffer(str)
                                            val queryAnswer = DefaultDnsRawRecord(answer.getString("name"), DnsRecordType.CNAME, answer.getLong("TTL"), buf)
                                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                                        }
                                        28 -> {
                                            val ip = answer.getString("data")
                                            val buf = addressToBuffer(ip)
                                            val queryAnswer = DefaultDnsRawRecord(answer.getString("name"), DnsRecordType.AAAA, 600, buf)
                                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                                        }
                                        else -> {
                                            log.error("收到了异常的解析结果： $type")
                                        }
                                    }
                                }
                                dnsServer.send(response)
                                if (!aRecordIps.isEmpty) {
                                    eb.request<Long>(
                                        RestVerticle.EVENT_ADDRESS, jsonObjectOf(
                                            "domain" to questionName,
                                            "address" to aRecordIps
                                        )
                                    ).onSuccess {
                                        log.debug("call success")
                                    }.onFailure { err ->
                                        log.error(err.message)
                                    }
                                }
                            }
                        }
                }
                DomainUtil.matchBlock(questionName) -> {
                    val dest = response.recipient().address.toString()
                    log.debug("$questionName 被阻止: $dest")
                    //val reply = blockMessage(message)
                    //serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                    val buf = Unpooled.wrappedBuffer(blockAddress.address)
                    val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 600, buf)
                    response.addRecord(DnsSection.ANSWER, queryAnswer)
                    dnsServer.send(response)
                }
                DomainUtil.match(dnsQuestion) -> { // A类查询，在查询名单，且不在逃逸名单里
                    log.debug("gfwlist hint")
                    log.debug(dnsQuestion.toString())

                    DnsOverHttpsService.queryCloudflare(questionName)
                        .subscribe().with { answers -> // 由于实现的特殊性，不会有异常分支
                            if (answers.isEmpty) {
                                val dest = response.recipient().address.toString()
                                log.error("$questionName 解析失败: $dest")
                                // 但是请求失败后，会从备用服务器解析结果
                                backupClient.proxy(dnsQuestion).onSuccess {
                                    log.debug(it.toString())
                                    for (answer in it) {
                                        response.addRecord(DnsSection.ANSWER, answer)
                                    }
                                    dnsServer.send(response)
                                }.onFailure {  }
                            } else {
                                val aRecordIps = jsonArrayOf()
                                answers.forEach { answer -> // 在Alpine上会遇到奇怪的现象会大分片失败，存疑。
                                    answer as JsonObject

                                    when (val type = answer.getInteger("type")){
                                        1 -> {
                                            val ip = answer.getString("data")
                                            val buf = addressToBuffer(ip)
                                            val queryAnswer = DefaultDnsRawRecord(answer.getString("name"), DnsRecordType.A, 600, buf)
                                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                                            aRecordIps.add(ip)
                                        }
                                        5 -> {
                                            val str = answer.getString("data")
                                            val buf = nameToBuffer(str)
                                            val queryAnswer = DefaultDnsRawRecord(answer.getString("name"), DnsRecordType.CNAME, answer.getLong("TTL"), buf)
                                            response.addRecord(DnsSection.ANSWER, queryAnswer)
                                        }
                                        else -> {
                                            log.error("收到了异常的解析结果： $type")
                                        }
                                    }
                                }
                                dnsServer.send(response)
                                if (!aRecordIps.isEmpty) {
                                    eb.request<Long>(
                                        RestVerticle.EVENT_ADDRESS, jsonObjectOf(
                                            "domain" to questionName,
                                            "address" to aRecordIps
                                        )
                                    ).onSuccess {
                                        log.debug("call success")
                                    }.onFailure { err ->
                                        log.error(err.message)
                                    }
                                }
                            }
                        }

                    /*val future: Future<List<DnsRawRecord>> = run {
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
                            val aRecordIps = mutableListOf<String>()
                            it.forEach { answer -> // 在Alpine上会遇到奇怪的现象会大分片失败，存疑。
                                response.addRecord(DnsSection.ANSWER, answer.retain())
                                if (answer.type()==DnsRecordType.A) {
                                    val address = RecordDecoder.decode<String>(answer)
                                    aRecordIps.add(address)
                                } else {
                                    log.debug(answer.type().name())
                                    val address = answer.content()
                                    address.forEachByte { b ->
                                        log.debug(b.toString())
                                        true
                                    }
                                }
                            }
                            dnsServer.send(response)
                            if (aRecordIps.isNotEmpty()) {
                                eb.request<Long>(
                                    RestVerticle.EVENT_ADDRESS, jsonObjectOf(
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
                        log.error("$questionName 解析失败 ${e.message}")
                        // 但是请求失败后，会从备用服务器解析结果
                        if (dnsQuestion.type()== DnsRecordType.A) {
                            val dnsProxy = vertx.createDnsClient(remotePort, remote)
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
                    }*/
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