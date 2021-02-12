package cn.foperate.ros.verticle

import cn.foperate.ros.munity.executeAsMulti
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.core.Context
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.mutiny.core.eventbus.EventBus
import io.vertx.mutiny.core.eventbus.Message
import me.legrange.mikrotik.ApiConnection
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import tech.stacktrace.jrodns.RosApiConnFactory

class RosVerticle(): AbstractVerticle() {
    private lateinit var rosFwadrKey: String
    private var maxThread: Int=8
    private lateinit var rosIp: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String

    private val cache = mutableSetOf<String>()
    private lateinit var rosConnPool: GenericObjectPool<ApiConnection>

    private fun executeCommandAsMulti(command: String): Multi<Map<String, String>> {
        var apiConnection: ApiConnection? = null
        return try {
            apiConnection = rosConnPool.borrowObject()
            apiConnection.executeAsMulti(command)
        } catch (e: Exception) {
            logger.error("ros command execute error", e)
            Multi.createFrom().failure(e)
        } finally {
            if (apiConnection != null) {
                rosConnPool.returnObject(apiConnection)
            }
        }
    }

    private fun loadCache() {
        val command = "/ip/firewall/address-list/print where list=$rosFwadrKey return address"
        executeCommandAsMulti(command)
            .onItem().transform { map -> map["address"] }
            .filter{ !it.isNullOrBlank() } // 这里已经保证了不会为空
            .onItem().transform { it as String }
            .collectItems().asList()
            .subscribe().with {
                cache.addAll(it)
                logger.info("loaded {} records from ros-firewall", it.size)
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

        loadCache()
    }

    private fun sendAddRequest(ip: String, comment: String) {
        val command = "/ip/firewall/address-list/add list=$rosFwadrKey address=$ip comment=$comment"
        executeCommandAsMulti(command)
            .collectItems().asList()
            .subscribe().with {
                cache.add(ip)
                logger.info("$ip add success")
            }
    }

    private fun add(hostname: String, ips: List<String>) {
        for (ip in ips) {
            if (cache.contains(ip)) {
                logger.info("{} in cache hint, skip", ip)
            } else {
                val flag = cache.add(ip)
                if (flag) {
                    sendAddRequest(ip, hostname)
                } else {
                    logger.warn("concurrent hint")
                }
                logger.info("{} add success", ip)
            }
        }
    }

    fun clear() {
        val commandQuery = "/ip/firewall/address-list/print where list=$rosFwadrKey return .id"
        executeCommandAsMulti(commandQuery)
            .onItem().transform {
                it[".id"].toString()
            }.collectItems().asList()
            .subscribe().with {
                val ids = it.joinToString(",")
                val commandWrite = "/ip/firewall/address-list/remove .id=$ids"
                executeCommandAsMulti(commandWrite)
                    .collectItems().asList()
                    .subscribe().with({}){}
            }
    }

    override fun asyncStart(): Uni<Void> {
        val eb = vertx.eventBus()

        eb.localConsumer<JsonObject>(EVENT_ADDRESS).toMulti()
            .onItem().transform { it.body() }
            .subscribe().with { jsonObject ->
                val domain = jsonObject.getString("domain")
                val address = jsonObject.getJsonArray("address")
                Multi.createFrom().items(address.stream())
                    .filter {
                        if(cache.contains(it)) {
                            logger.info("$it in cache hint, skip")
                            false
                        } else true
                    }
                    .subscribe().with { ip ->
                        ip as String
                        sendAddRequest(ip, domain)
                    }
            }

        return Uni.createFrom().voidItem()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RosVerticle::class.java.name)
        val EVENT_ADDRESS = RosVerticle::class.java.name
    }
}