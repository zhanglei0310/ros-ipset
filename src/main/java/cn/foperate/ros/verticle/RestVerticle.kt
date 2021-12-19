package cn.foperate.ros.verticle

import cn.foperate.ros.pac.DomainUtil
import com.github.benmanes.caffeine.cache.Caffeine
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class RestVerticle: CoroutineVerticle() {
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build<String, Long>()
    private var rosListKey = "PROXY"

    override suspend fun start() {
        rosListKey = config.getString("listName")
        flushNetflix()

        val eb = vertx.eventBus()
        eb.consumer<JsonObject>(EVENT_ADDRESS).handler { message ->
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
                    RestService.addProxyAddress(ip, domain)
                        .onFailure().recoverWithNull()
                }
                .collect().last()
                .subscribe().with ({
                    message.reply(System.currentTimeMillis())
                }) {
                    message.reply(System.currentTimeMillis())
                }
        }
        loadCache().subscribe()
            .with({ log.info("loaded $it records from ros-firewall") }) {
                log.error(it.message)
            }
    }

    private suspend fun flushNetflix() {
        RestService.queryAddressListIDs("NETFLIX")
            .onItem().transformToMulti { Multi.createFrom().iterable(it) }
            .onItem().transform {
                RestService.deleteAddressListItem(it)
            }
            .collect().asList()
            .awaitSuspending()
        log.debug("旧数据清理完毕")
        DomainUtil.netflixList
            .forEach { ip ->
                RestService.addStaticAddress(ip, "NETFLIX", "NETFLIX")
                    .subscribe().with({}){}
            }
    }

    private fun loadCache() = RestService.queryAddressList(rosListKey)
        .onItem().transformToMulti { Multi.createFrom().iterable(it) }
        .onItem().transform { it as JsonObject
            val timeout = if (it.containsKey("timeout")) {
                DomainUtil.getTimeout(it.getString("timeout"))
            } else 24*3600
            cache.put(it.getString("address"), System.currentTimeMillis() + timeout*1000)
            it.getString("address")
        }
        .collect().asList()
        .onItem().transform { it.size }

    companion object {
        private val log = LoggerFactory.getLogger(RestVerticle::class.java.name)
        val EVENT_ADDRESS: String = RestVerticle::class.java.name
    }
}