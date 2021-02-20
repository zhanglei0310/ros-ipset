package cn.foperate.ros.verticle

import cn.foperate.ros.api.ApiConnectionOptions
import cn.foperate.ros.api.Command
import cn.foperate.ros.api.RxApiConnection
import cn.foperate.ros.munity.AsyncSocketFactory
import cn.foperate.ros.pac.DomainUtil
import com.google.common.cache.CacheBuilder
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.net.netClientOptionsOf
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class RosVerticle : AbstractVerticle() {
    private lateinit var rosFwadrKey: String

    private val cache = CacheBuilder.newBuilder()
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
                        .onFailure().recoverWithItem { e ->
                            log.error("ros command execute error", e)
                            emitter.fail(e)
                            mapOf()
                        }.subscribe().with {
                            // 如果之前出现了执行错误，在return时，连接会被回收
                            //apiConnection.close()
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
                        .onFailure().recoverWithItem { e ->
                            log.error("ros command execute error", e)
                            emitter.fail(e)
                            mapOf()
                        }.subscribe().with {
                            // 如果之前出现了执行错误，在return时，连接会被回收
                            //apiConnection.close()
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

    override fun asyncStart(): Uni<Void> {

        rosFwadrKey = config().getString("rosFwadrKey")

        socketFactory = AsyncSocketFactory(vertx, netClientOptionsOf(
            connectTimeout = 3000
        ))
        apiConnectionOptions = ApiConnectionOptions(
            username = config().getString("rosUser"),
            password = config().getString("rosPwd"),
            host = config().getString("rosIp")
        )

        val eb = vertx.eventBus()

        eb.localConsumer<JsonObject>(EVENT_ADDRESS).toMulti()
            .subscribe().with { message ->
                val jsonObject = message.body()
                val domain = jsonObject.getString("domain")
                val address = jsonObject.getJsonArray("address")
                Multi.createFrom().items(address.stream())
                    .filter {
                        it as String
                        cache.getIfPresent(it)?.let { timeout ->
                            timeout < System.currentTimeMillis()
                        } ?: true
                    }.onItem().transformToUniAndMerge { ip ->
                        ip as String
                        sendAddRequest(ip, domain)
                    }
                    .collect().last()
                    .subscribe().with {
                        message.reply(System.currentTimeMillis())
                    }
            }

        return loadCache()
    }

    /*override fun asyncStop(): Uni<Void> {
        evictTimer.cancel()
        return Uni.createFrom().voidItem()
    }*/

    companion object {
        private val log = LoggerFactory.getLogger(RosVerticle::class.java.name)
        val EVENT_ADDRESS: String = RosVerticle::class.java.name
    }
}