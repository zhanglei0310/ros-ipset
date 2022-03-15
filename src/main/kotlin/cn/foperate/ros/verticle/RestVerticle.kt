package cn.foperate.ros.verticle

import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.service.RestService
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonObjectOf
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
                .onItem().transformToUniAndMerge { ip ->
                    ip as String
                    RestService.addOrUpdateProxyAddress(ip, domain)
                }
                .onFailure().recoverWithItem { e ->
                    log.error(e.message)
                    jsonObjectOf()
                }
                .collect().last()
                .subscribe().with {
                    message.reply(System.currentTimeMillis())
                }
        }
    }

    private suspend fun flushNetflix() {
        RestService.queryAddressListIDs("NETFLIX")
            .onItem().transformToUniAndMerge {
                RestService.deleteAddressListItem(it)
            }
            .onFailure().recoverWithItem { e ->
                log.error(e.message)
                e.message
            }
            .collect().last()
            .awaitSuspending()
        log.debug("旧数据清理完毕")
        DomainUtil.netflixIPs
            .forEach { ip ->
                RestService.addStaticAddress(ip, "NETFLIX", "NETFLIX")
                    .subscribe().with({}){
                        log.error(it.message)
                    }
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RestVerticle::class.java.name)
        val EVENT_ADDRESS: String = RestVerticle::class.java.name
    }
}