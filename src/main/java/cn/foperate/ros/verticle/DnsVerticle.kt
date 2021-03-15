package cn.foperate.ros.verticle

import cn.foperate.ros.pac.DomainUtil
import com.github.benmanes.caffeine.cache.Caffeine
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramPacket
import io.vertx.core.datagram.DatagramSocket
import io.vertx.core.eventbus.EventBus
import io.vertx.kotlin.core.datagram.datagramSocketOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.xbill.DNS.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.streams.toList

/*****
 * 进行DNS过滤、解析和转发，并请求将结果保存到ROS中。
 * 改用Kotlin协程来实现，期望语义上更加简洁清晰。
 * @author Aston Mei
 * @since 2021-02-26
 */
class DnsVerticle: CoroutineVerticle() {

    private var localPort: Int = 53  // DNS服务监听的端口
    private lateinit var remote: String  // upstream服务器地址
    private var remotePort: Int = 53  // upstream服务器端口
    private lateinit var fallback: String
    private lateinit var blockAddress: InetAddress

    private lateinit var serverSocket: DatagramSocket  // 正在监听的服务端口
    private lateinit var eb: EventBus

    private val aCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(1000)
        .build<String, Buffer>()
    private val httpsCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(1000)
        .build<String, Buffer>()

    override suspend fun start() {
        localPort = config.getInteger("localPort", localPort)
        remote = config.getString("remote")
        remotePort = config.getInteger("remotePort", remotePort)
        fallback = config.getString("fallback")
        val block = config.getString("blockAddress")
        // 实际不会发生阻塞
        blockAddress = InetAddress.getByName(block)

        eb = vertx.eventBus()
        serverSocket = vertx.createDatagramSocket(datagramSocketOptionsOf())
            .listen(localPort, "0.0.0.0").await()
        log.debug("UDP服务已经启动")
        serverSocket.handler {
            // 由于CoroutineVerticle，切换到协程上下文，并不会切换到其它线程
            launch { udpService(it) }
        }
    }


    private suspend fun udpService(request:DatagramPacket) {
        try {
            // 基于IOException进行的猜测，实际不会出现阻塞
            val message = Message(request.data().bytes)
            val questionName = message.question.name.toString()
            val questionType = message.question.type
            val typeString = Type.string(questionType)
            log.debug("$questionName : $typeString")

            when (questionType) {
                Type.A -> {
                    aCache.getIfPresent(questionName)?.let {
                        log.debug("Found matched answer from cache")
                        val answer = Message(it.bytes)
                        answer.header.id = message.header.id
                        val buffer = Buffer.buffer(answer.toWire())
                        serverSocket.send(buffer, request.sender().port(), request.sender().host())
                        return
                    }
                    when {
                        DomainUtil.match(questionName) -> {
                            log.debug("gfwlist hint")
                            forwardToRemote(request, questionName, questionType)
                        }
                        DomainUtil.matchBlock(questionName) -> {
                            log.debug("adBlock matched")
                            val reply = blockMessage(message)
                            serverSocket.send(Buffer.buffer(reply), request.sender().port(), request.sender().host())
                        }
                        else -> forwardToFallback(request, questionName, questionType)
                    }
                }
                Type.HTTPS -> {
                    // 不确定HTTPS请求是否会有回应。
                    httpsCache.getIfPresent(questionName)?.let {
                        log.debug("Found matched answer from cache")
                        val answer = Message(it.bytes)
                        answer.header.id = message.header.id
                        val buffer = Buffer.buffer(answer.toWire())
                        serverSocket.send(buffer, request.sender().port(), request.sender().host())
                        return
                    }
                    if (DomainUtil.match(questionName)) {
                        log.debug("gfwlist hint")
                        forwardToRemote(request, questionName, questionType)
                    } else {
                        forwardToFallback(request, questionName, questionType)
                    }
                }
                else -> {
                    forwardToFallback(request, questionName, questionType)
                }
            }
        } catch (e:Exception) {
            log.error(e.message, e)
        }
    }

    private fun cacheResult(name:String, type:Int, buffer: Buffer) {
        if (type==Type.A) {
            aCache.put(name, buffer)
        } else if (type==Type.HTTPS) {
            httpsCache.put(name, buffer)
        }
    }

    private suspend fun forwardToRemote(packet: DatagramPacket, name: String, type: Int) {
        val startTime = System.currentTimeMillis()
        val clientSocket = vertx.createDatagramSocket()
        try {
            clientSocket.send(packet.data(), remotePort, remote) // .await()
            val result = withTimeout(3000) {
                clientSocket.toChannel(vertx).receive()
            }
            if (type == Type.A) {
                processResult(packet, result, startTime)
            }
            clientSocket.close()
            cacheResult(name, type, result.data())
        }catch (e:Exception) {
            forwardToFallback(packet, name, type)
            clientSocket.close()
        }
    }

    private suspend fun forwardToFallback(request: DatagramPacket, name: String, type: Int) {
        val clientSocket = vertx.createDatagramSocket()
        try {
            clientSocket.send(request.data(), 53, fallback) //.await()
            val response = withTimeout(3000) {
                clientSocket.toChannel(vertx).receive()
            }
            log.debug("Get answers for ${Type.string(type)}")
            // Fallback服务当作不可信信息，不操作IPset列表
            serverSocket.send(response.data(), request.sender().port(), request.sender().host())
            clientSocket.close()
            cacheResult(name, type, response.data())
        } catch (e:Exception) {
            clientSocket.close()
        }
    }

    private suspend fun processResult(request: DatagramPacket, response: DatagramPacket, time: Long) {
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

        serverSocket.send(response.data(), request.sender().port(), request.sender().host()).await()
        log.debug("answers sent")
        val finalTime = System.currentTimeMillis()
        log.debug("reply complete, used ${finalTime - time}ms")

        if (question.type == Type.A && aRecordIps.isNotEmpty()) {
            try {
                eb.request<Long>(
                    RosVerticle.EVENT_ADDRESS, jsonObjectOf(
                        "domain" to questionName,
                        "address" to aRecordIps
                    )
                ).await()
                val usingTime = System.currentTimeMillis() - finalTime
                log.debug("gfwlist check task complete, used ${usingTime}ms")
            } catch (e:Exception) {
                log.error(e.message, e)
            }
        }
    }

    /***
     * Make the query result to blockAddress and expired in 1 day.
     */
    private fun blockMessage(request:Message):ByteArray {
        val response = Message(request.header.id)
        response.header.setFlag(Flags.QR.toInt())
        response.addRecord(request.question, Section.QUESTION)
        val questionName = request.question.name

        response.addRecord(ARecord(questionName, DClass.IN, 86400, blockAddress), Section.ANSWER)

        return response.toWire()
    }



    override suspend fun stop() {
        serverSocket.close().await()
        // 利用协程上下文机制，停掉本服务的全部在等待的处理协程
        cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DnsVerticle::class.java)
    }
}