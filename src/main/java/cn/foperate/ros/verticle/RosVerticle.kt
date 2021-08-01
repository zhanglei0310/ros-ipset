package cn.foperate.ros.verticle

import cn.foperate.ros.api.ApiConnectionOptions
import cn.foperate.ros.api.Command
import cn.foperate.ros.api.RxApiConnection
import cn.foperate.ros.api.AsyncSocketFactory
import cn.foperate.ros.pac.DomainUtil
import com.github.benmanes.caffeine.cache.Caffeine
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/****
 * 目前由Mutiny能够提供更加清晰的流式处理，
 * kotlin协程的flow实现是参考响应式流实现的，但清晰度不如mutiny，
 * 又需要涉及到上下文切换，修改的意义不太大。但是verticle实现回复到标准实现
 * @author Aston Mei
 */
class RosVerticle: CoroutineVerticle() {
    private lateinit var rosFwadrKey: String

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build<String, Long>()
    private lateinit var socketFactory: AsyncSocketFactory
    private lateinit var apiConnectionOptions:ApiConnectionOptions

    private fun executeCommandAsMulti(command: Command): Multi<Map<String, String>> {
        // 二度封装Multi有两个原因：borrowObject本身有可能失败，以及考虑在出现错误时关闭api连接
        return Multi.createFrom().emitter { emitter ->
            RxApiConnection.connection(socketFactory, apiConnectionOptions)
                .subscribe().with { apiConnection ->
                    apiConnection.executeAsMulti(command)
                        .onItem().invoke { item ->
                            emitter.emit(item)
                        }
                        .onCompletion().invoke {
                            emitter.complete()
                        }
                        .subscribe().with ({
                            // 如果之前出现了执行错误，在return时，连接会被回收
                            //apiConnection.close()
                        }) { e ->
                            log.error("ros command execute error: ${e.message}")
                            emitter.fail(e)
                        }
                }
        }
    }

    private fun executeCommandAsUni(command: Command): Uni<Map<String, String>> {
        return Uni.createFrom().emitter { emitter ->
            RxApiConnection.connection(socketFactory, apiConnectionOptions)
                .subscribe().with { apiConnection ->
                    apiConnection.executeAsUni(command)
                        .onItem().invoke { item ->
                            emitter.complete(item)
                        }
                        .subscribe().with ({
                            // 如果之前出现了执行错误，在return时，连接会被回收
                            //apiConnection.close()
                        }) { e ->
                            log.error("ros command execute error: ${e.message}")
                            emitter.fail(e)
                        }
                }
        }
    }

    private fun loadCache():Uni<Void> {
        val command = Command("/ip/firewall/address-list/print", queries = mapOf(
            "list" to rosFwadrKey
        ), props = listOf("address", "timeout"))
        return executeCommandAsMulti(command)
            .onItem().transform {
                val timeout = if (it.containsKey("timeout")) {
                    DomainUtil.getTimeout(it["timeout"]!!)
                } else 24*3600
                cache.put(it["address"]!!, System.currentTimeMillis() + timeout*1000)
                it["address"]!!
            }
            .collect().asList()
            .onItem().transformToUni { list ->
                log.info("loaded ${list.size} records from ros-firewall")
                Uni.createFrom().voidItem()
            }
    }

    private fun sendAddRequest(ip: String, comment: String):Uni<Map<String, String>> {
        val command = Command("/ip/firewall/address-list/add", params = mapOf(
            "list" to rosFwadrKey,
            "address" to ip,
            "timeout" to "24h",
            "comment" to comment
        ))
        return executeCommandAsUni(command)
                .onItem().invoke { _ ->
                    cache.put(ip, System.currentTimeMillis() + 24*3600*1000)
                    log.info("$ip add success")
                }
    }

    /*fun clear() {
        val commandQuery = "/ip/firewall/address-list/print where list=$rosFwadrKey return .id"
        executeCommandAsMulti(commandQuery)
            .onItem().transform {
                it[".id"].toString()
            }.collect().asList()
            .subscribe().with {
                val ids = it.joinToString(",")
                val commandWrite = "/ip/firewall/address-list/remove .id=$ids"
                executeCommandAsMulti(commandWrite)
                    .collect().asList()
                    .subscribe().with({}){}
            }
    }*/

    private fun sendNetflixRequest(ip: String):Uni<Map<String, String>> {
        val command = Command("/ip/firewall/address-list/add", params = mapOf(
            "list" to "NETFLIX",
            "address" to ip,
            "timeout" to "7d"
        ))
        return executeCommandAsUni(command)
            .onItem().invoke { _ ->
                log.info("$ip add success")
            }
    }

    suspend fun flushNetflix() {
        val commandQuery = Command("/ip/firewall/address-list/print", queries = mapOf(
            "list" to "NETFLIX"
        ), props = listOf(".id"))
        executeCommandAsMulti(commandQuery)
            .onItem().transform { it[".id"].toString() }
            .collect().asList()
            .onItem().transformToMulti {
                val ids = it.joinToString(",")
                val commandWrite = Command("/ip/firewall/address-list/remove", params = mapOf(
                   ".id" to ids
                ))
                executeCommandAsMulti(commandWrite)
            }.collect().asList().awaitSuspending()
        log.debug("旧数据清理完毕")
        DomainUtil.netflixList
            .forEach { ip ->
                sendNetflixRequest(ip).subscribe().with({}){}
            }
    }

    override suspend fun start() {

        rosFwadrKey = config.getString("rosFwadrKey")

        socketFactory = AsyncSocketFactory(vertx, netClientOptionsOf(
            connectTimeout = 3000
        ))
        apiConnectionOptions = ApiConnectionOptions(
            username = config.getString("rosUser"),
            password = config.getString("rosPwd"),
            host = config.getString("rosIp")
        )
        flushNetflix()

        val eb = vertx.eventBus()

        eb.localConsumer<JsonObject>(EVENT_ADDRESS).handler { message ->
            val jsonObject = message.body()
            val domain = jsonObject.getString("domain")
            val address = jsonObject.getJsonArray("address")
            Multi.createFrom().items(address.stream())
                .filter {
                    it as String
                    cache.getIfPresent(it)?.let { timeout ->
                        timeout < System.currentTimeMillis()
                    } ?: true
                }.onItem().call { ip ->
                    ip as String
                    sendAddRequest(ip, domain)
                        .onFailure().recoverWithItem(mapOf())
                }
                .collect().last()
                .subscribe().with ({
                    message.reply(System.currentTimeMillis())
                }) {
                    message.reply(System.currentTimeMillis())
                }
        }
        loadCache().awaitSuspending()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RosVerticle::class.java.name)
        val EVENT_ADDRESS: String = RosVerticle::class.java.name
    }
}