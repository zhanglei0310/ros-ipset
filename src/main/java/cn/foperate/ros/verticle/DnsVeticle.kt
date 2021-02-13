package cn.foperate.ros.verticle

import cn.foperate.ros.pac.DomainUtil
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.kotlin.core.datagram.datagramSocketOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.mutiny.core.datagram.DatagramPacket
import io.vertx.mutiny.core.datagram.DatagramSocket
import io.vertx.mutiny.core.eventbus.EventBus
import org.slf4j.LoggerFactory
import org.xbill.DNS.ARecord
import org.xbill.DNS.Message
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import kotlin.streams.toList

class DnsVeticle: AbstractVerticle() {

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String

    private lateinit var serverSocket:DatagramSocket  // 正在监听的服务端口
    private lateinit var eb: EventBus

    override fun asyncStart(): Uni<Void> {
        localPort = config().getInteger("localPort", localPort)
        remote = config().getString("remote")
        remotePort = config().getInteger("remotePort", remotePort)
        fallback = config().getString("fallback")

        this.eb = vertx.eventBus()
        val serverSocket = vertx.createDatagramSocket(datagramSocketOptionsOf())
        serverSocket.listen(localPort, "0.0.0.0")
            .subscribe().with {
                this.serverSocket = it
                it.toMulti().subscribe().with { request ->
                    val message = Message(request.data().bytes)
                    val questionName = message.question.name.toString()
                    log.debug(questionName)
                    if (DomainUtil.match(questionName)){
                        log.debug("gfwlist hint")
                        forwardToRemote(request)
                    } else {
                        forwardToFallback(request)
                    }

                }
            }

        return Uni.createFrom().voidItem()
    }

    override fun asyncStop(): Uni<Void> {
        serverSocket.closeAndForget()
        return Uni.createFrom().voidItem()
    }

    private fun forwardToRemote(packet: DatagramPacket) {
        val startTime = System.currentTimeMillis()
        val clientSocket = vertx.createDatagramSocket()
        clientSocket.send(packet.data(), remotePort, remote)
            .subscribe().with ({
                clientSocket.toMulti().subscribe().with { result ->
                    processResult(packet, result, startTime)
                    clientSocket.closeAndForget()
                }
            }) {
                forwardToFallback(packet)
                clientSocket.closeAndForget()
            }
    }

    private fun forwardToFallback(request: DatagramPacket) {
        val clientSocket = vertx.createDatagramSocket()
        clientSocket.send(request.data(), 53, fallback)
            .subscribe().with ({
                clientSocket.toMulti().subscribe().with { response ->
                    // Fallback服务当作不可信信息，不操作IPset列表
                    serverSocket.send(response.data(), request.sender().port(), request.sender().host())
                        .subscribe().with {}
                    clientSocket.closeAndForget()
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

    companion object {
        private val log = LoggerFactory.getLogger(DnsVeticle::class.java)
    }
}