package cn.foperate.ros.verticle

import cn.foperate.ros.munity.executeAsMulti
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.core.Context
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import me.legrange.mikrotik.ApiConnection
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig

class RosVerticle : AbstractVerticle() {
    private lateinit var rosFwadrKey: String
    private var maxThread: Int=8
    private lateinit var rosIp: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String

    private val cache = HashSet<String>()
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
        val command = "/ip/firewall/address-list/print where list=$rosFwadrKey return address"
        return executeCommandAsMulti(command)
            .onItem().transform { map -> map["address"] }
            .filter { !it.isNullOrBlank() } // 这里已经保证了不会为空
            .onItem().transform { it as String }
            .collect().asList()
            .onItem().transformToUni { list ->
                cache.addAll(list)
                log.info("loaded ${list.size} records from ros-firewall")
                Uni.createFrom().voidItem()
            }
    }

    override fun init(vertx: Vertx, context: Context) {
        super.init(vertx, context)

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
    }

    private fun sendAddRequest(ip: String, comment: String):Uni<Void> {
        val command = "/ip/firewall/address-list/add list=$rosFwadrKey address=$ip comment=$comment"
        return Uni.createFrom().emitter { emitter ->
            executeCommandAsMulti(command)
                .collect().asList()
                .subscribe().with{
                    cache.add(ip)
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
        val eb = vertx.eventBus()

        eb.localConsumer<JsonObject>(EVENT_ADDRESS).toMulti()
            .subscribe().with { message ->
                val jsonObject = message.body()
                val domain = jsonObject.getString("domain")
                val address = jsonObject.getJsonArray("address")
                Multi.createFrom().items(address.stream())
                    .filter {
                        if(cache.contains(it)) {
                            log.info("$it in cache hint, skip")
                            false
                        } else true
                    }.onItem().transformToUniAndMerge { ip ->
                        ip as String
                        sendAddRequest(ip, domain)
                    }
                    .collect().last()
                    .subscribe().with {
                        message.replyAndForget(System.currentTimeMillis())
                    }
            }

        return loadCache()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RosVerticle::class.java.name)
        val EVENT_ADDRESS: String = RosVerticle::class.java.name
    }
}