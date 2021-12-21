package cn.foperate.ros.app

import cn.foperate.ros.service.CloudflareService
import cn.foperate.ros.service.RestService
import io.vertx.config.ConfigRetriever
import io.vertx.kotlin.core.dns.dnsClientOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.mutiny.core.Vertx
import org.slf4j.LoggerFactory

object RestClient {
    private val log = LoggerFactory.getLogger(RestClient::class.java)
    @JvmStatic
    fun main(args: Array<String>) {
        val vertx = Vertx.vertx()

        ConfigRetriever.create(vertx.delegate)
            .config
            .onSuccess {
                RestService.init(vertx, it.getJsonObject("ros"))
                RestService.addOrUpdateProxyAddress("172.217.24.74", "TEST")
                    .subscribe().with { json -> println(json.encodePrettily()) }
            }
    }
}