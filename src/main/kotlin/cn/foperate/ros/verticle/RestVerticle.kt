package cn.foperate.ros.verticle

import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.service.RestService
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory

class RestVerticle: CoroutineVerticle() {
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
                .onItem().call { ip ->
                    ip as String
                    RestService.addOrUpdateProxyAddress(ip, domain)
                        .onFailure().recoverWithNull()
                }
                .collect().last()
                .subscribe().with ({
                    message.reply(System.currentTimeMillis())
                }) {
                    message.reply(System.currentTimeMillis())
                }
        }
    }

    private suspend fun flushNetflix() {
        RestService.queryAddressListIDs("NETFLIX")
            .onItem().transformToMulti { Multi.createFrom().iterable(it) }
            .onItem().transform {
                RestService.deleteAddressListItem(it)
            }
            .collect().asList()
            .onFailure().recoverWithNull()
            .awaitSuspending()
        log.debug("旧数据清理完毕")
        DomainUtil.netflixIPs
            .forEach { ip ->
                RestService.addStaticAddress(ip, "NETFLIX", "NETFLIX")
                    .subscribe().with({}){}
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RestVerticle::class.java.name)
        val EVENT_ADDRESS: String = RestVerticle::class.java.name
    }
}