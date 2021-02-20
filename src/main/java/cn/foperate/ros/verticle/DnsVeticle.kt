package cn.foperate.ros.verticle

import cn.foperate.ros.pac.DomainUtil
import com.google.common.cache.CacheBuilder
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.kotlin.core.datagram.datagramSocketOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.core.datagram.DatagramPacket
import io.vertx.mutiny.core.datagram.DatagramSocket
import io.vertx.mutiny.core.eventbus.EventBus
import org.slf4j.LoggerFactory
import org.xbill.DNS.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.streams.toList


class DnsVeticle: AbstractVerticle() {

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String
    private lateinit var blockAddress: InetAddress

    private lateinit var serverSocket:DatagramSocket  // 正在监听的服务端口
    private lateinit var eb: EventBus

    private val resultCache = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(1000)
        .build<String, Buffer>()

    override fun asyncStart(): Uni<Void> {
        localPort = config().getInteger("localPort", localPort)
        remote = config().getString("remote")
        remotePort = config().getInteger("remotePort", remotePort)
        fallback = config().getString("fallback")
        val block = config().getString("blockAddress")
        blockAddress = InetAddress.getByName(block)

        this.eb = vertx.eventBus()
        val serverSocket = vertx.createDatagramSocket(datagramSocketOptionsOf())
        serverSocket.listen(localPort, "0.0.0.0")
            .subscribe().with { ss ->
                this.serverSocket = ss
                ss.toMulti().subscribe().with { request ->
                    val message = Message(request.data().bytes)
                    val questionName = message.question.name.toString()
                    val questionType = message.question.type
                    log.debug(questionName)
                    resultCache.getIfPresent(questionName)?.let {
                        log.debug("Found matched answer from cache")
                        val answer = Message(it.bytes)
                        answer.header.id = message.header.id
                        val buffer = Buffer.buffer(answer.toWire())
                        ss.send(buffer, request.sender().port(), request.sender().host())
                            .subscribe().with { }
                        return@with
                    }
                    if (questionType==Type.A) when {
                        DomainUtil.match(questionName) -> {
                            log.debug("gfwlist hint")
                            forwardToRemote(request, questionName)
                        }
                        DomainUtil.matchBlock(questionName) -> {
                            log.debug("adBlock matched")
                            val reply = blockMessage(message)
                            ss.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                                .subscribe().with { }
                        }
                        else -> forwardToFallback(request, questionName)
                    } else {
                        forwardToFallback(request, questionName)
                    }
                }
            }

        return Uni.createFrom().voidItem()
    }

    override fun asyncStop(): Uni<Void> {
        serverSocket.closeAndForget()
        return Uni.createFrom().voidItem()
    }

    private fun forwardToRemote(packet: DatagramPacket, name: String) {
        val startTime = System.currentTimeMillis()
        val clientSocket = vertx.createDatagramSocket()
        clientSocket.send(packet.data(), remotePort, remote)
            .subscribe().with ({
                clientSocket.toMulti().subscribe().with { result ->
                    processResult(packet, result, startTime)
                    clientSocket.closeAndForget()
                    resultCache.put(name, result.data())
                }
            }) {
                forwardToFallback(packet, name)
                clientSocket.closeAndForget()
            }
    }

    private fun forwardToFallback(request: DatagramPacket, name: String) {
        val clientSocket = vertx.createDatagramSocket()
        clientSocket.send(request.data(), 53, fallback)
            .subscribe().with ({
                clientSocket.toMulti().subscribe().with { response ->
                    // Fallback服务当作不可信信息，不操作IPset列表
                    serverSocket.send(response.data(), request.sender().port(), request.sender().host())
                        .subscribe().with {}
                    clientSocket.closeAndForget()
                    resultCache.put(name, response.data())
                }
            }) {
                clientSocket.closeAndForget()
            }
    }

    private fun processResult(request: DatagramPacket, response: DatagramPacket, time: Long) {
        val message = Message(response.data().bytes)
        val question = message.question
        val sectionRRsets = message.getSectionRRsets(Section.ANSWER)
        val messageId = message.header.id
        var questionName = question.name.toString()
        if (questionName.endsWith(".")) {
            questionName = questionName.substring(0, questionName.length - 1)
        }

        val typeName = Type.string(question.type)
        log.info( "query -> id: $messageId, question: $questionName, type: $typeName")

        val aRecordIps =  sectionRRsets.stream()
            .filter { it.type==Type.A }
            .flatMap { it.rrs().stream() }
            .map { it as ARecord }
            .map { it.address.hostAddress }
            .toList()
        log.debug("remote answer -> id: $messageId, ips: $aRecordIps")

        serverSocket.send(response.data(), request.sender().port(), request.sender().host())
            .subscribe().with {}
        val finalTime = System.currentTimeMillis()
        log.debug("reply complete, used ${finalTime - time}ms")

        if (question.type == Type.A && aRecordIps.isNotEmpty()) {
            eb.request<Long>(
                RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                    "domain" to questionName,
                    "address" to aRecordIps
                )
            ).subscribe().with({
                val usingTime = System.currentTimeMillis() - finalTime
                log.debug("gfwlist check task complete, used ${usingTime}ms")
            }) {}
        }
    }

    /***
     * Make the query result to blockAddress and expired in 1 day.
     */
    fun blockMessage(request:Message):ByteArray {
        val response = Message(request.header.id)
        response.header.setFlag(Flags.QR.toInt())
        response.addRecord(request.question, Section.QUESTION)
        val questionName = request.question.name

        response.addRecord(ARecord(questionName, DClass.IN, 86400, blockAddress), Section.ANSWER)

        return response.toWire()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DnsVeticle::class.java)
    }
}
