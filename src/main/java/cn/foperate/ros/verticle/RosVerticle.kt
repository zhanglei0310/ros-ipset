package cn.foperate.ros.verticle

import cn.foperate.ros.munity.executeAsMulti
import cn.foperate.ros.pac.DomainUtil
import com.google.common.cache.CacheBuilder
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import me.legrange.mikrotik.ApiConnection
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class RosVerticle : AbstractVerticle() {
    private lateinit var rosFwadrKey: String
    private var maxThread: Int=8
    private lateinit var rosIp: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String

    private val cache = CacheBuilder.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build<String, Long>()
    private lateinit var rosConnPool: GenericObjectPool<ApiConnection>

    private fun executeCommandAsMulti(command: String): Multi<Map<String, String>> {
        return Multi.createFrom().emitter { emitter ->
            try {
                val apiConnection = rosConnPool.borrowObject()
                apiConnection.executeAsMulti(command)
                    .onItem().invoke { item ->
                        emitter.emit(item)
                    }
                    .onCompletion().invoke {
                        emitter.complete()
                        rosConnPool.returnObject(apiConnection)
                    }
                    .onFailure().invoke { e ->
                        log.error("ros command execute error", e)
                        apiConnection.close()
                        emitter.fail(e)
                    }.subscribe().with {  }
            } catch (e:Exception) {
                emitter.fail(e)
            }
        }
    }

    private fun loadCache():Uni<Void> {
        val command = "/ip/firewall/address-list/print where list=$rosFwadrKey return address,timeout"
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
                log.info(cache.getIfPresent("123.118.97.191").toString())
                val day = System.currentTimeMillis() + 24*3600*1000
                log.info(day.toString())
                Uni.createFrom().voidItem()
            }
    }

    private fun sendAddRequest(ip: String, comment: String):Uni<Void> {
        val command = "/ip/firewall/address-list/add list=$rosFwadrKey address=$ip timeout=24h comment=$comment"
        return Uni.createFrom().emitter { emitter ->
            executeCommandAsMulti(command)
                .collect().asList()
                .subscribe().with{
                    cache.put(ip, System.currentTimeMillis() + 24*3600*1000)
                    log.info("$ip add success")
                    emitter.complete(null)
                }
        }
    }

    fun clear() {
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
    }

    override fun asyncStart(): Uni<Void> {

        maxThread = config().getInteger("maxThread")
        rosIp = config().getString("rosIp")
        rosUser = config().getString("rosUser")
        rosPwd = config().getString("rosPwd")
        rosFwadrKey = config().getString("rosFwadrKey")

        val config = GenericObjectPoolConfig<ApiConnection>()
        config.maxIdle = maxThread
        config.maxTotal = maxThread
        config.minIdle = maxThread
        rosConnPool = GenericObjectPool(RosApiConnFactory(config()), config)

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

    companion object {
        private val log = LoggerFactory.getLogger(RosVerticle::class.java.name)
        val EVENT_ADDRESS: String = RosVerticle::class.java.name
    }
}