package cn.foperate.ros.verticle

import cn.foperate.ros.munity.executeAsMulti
import cn.foperate.ros.munity.returnToPool
import cn.foperate.ros.pac.DomainUtil
import com.google.common.cache.CacheBuilder
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import me.legrange.mikrotik.ApiConnection
import me.legrange.mikrotik.impl.ApiCommandException
import org.apache.commons.pool2.impl.BaseObjectPoolConfig.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class RosVerticle : AbstractVerticle() {
    private lateinit var rosFwadrKey: String
    private var maxThread: Int=8
    private var idleTimeout: Int=30
    private lateinit var rosIp: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String

    private val cache = CacheBuilder.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build<String, Long>()
    private lateinit var rosConnPool: GenericObjectPool<ApiConnection>

    private fun executeCommandAsMulti(command: String): Multi<Map<String, String>> {
        // 二度封装Multi有两个原因：borrowObject本身有可能失败，以及考虑在出现错误时关闭api连接
        return Multi.createFrom().emitter { emitter ->
            try {
                val apiConnection = rosConnPool.borrowObject()
                apiConnection.executeAsMulti(command)
                    .onItem().invoke { item ->
                        emitter.emit(item)
                    }
                    .onCompletion().invoke {
                        emitter.complete()
                    }
                    .onFailure().recoverWithItem { e ->
                        log.error("ros command execute error", e)
                        if (e !is ApiCommandException) try {
                            apiConnection.close()
                        } catch (e:Exception) {}
                        emitter.fail(e)
                        mapOf()
                    }.subscribe().with {
                        // 如果之前出现了执行错误，在return时，连接会被回收
                       apiConnection.returnToPool(rosConnPool)
                    }
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

        maxThread = config().getInteger("maxThread")
        idleTimeout = config().getInteger("rosIdle")
        rosIp = config().getString("rosIp")
        rosUser = config().getString("rosUser")
        rosPwd = config().getString("rosPwd")
        rosFwadrKey = config().getString("rosFwadrKey")

        val config = GenericObjectPoolConfig<ApiConnection>()
        config.maxIdle = maxThread
        config.maxTotal = maxThread
        config.minIdle = 2
        config.minEvictableIdleTimeMillis = idleTimeout*1000L
        config.testOnReturn = true
        config.testWhileIdle = true
        // 并不使用common pool的缺省线程执行回收操作，而是由VertX的计时器执行
        config.timeBetweenEvictionRunsMillis = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS
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

        // 定期执行资源池清理操作
        vertx.periodicStream(idleTimeout*1000L).handler {
            log.info("Ros connection pool to be evicted")
            rosConnPool.evict()
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