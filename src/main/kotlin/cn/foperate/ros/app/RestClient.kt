package cn.foperate.ros.app

import cn.foperate.ros.service.RestService
import cn.foperate.ros.verticle.DnsOverHttpsVerticle
import io.vertx.config.ConfigRetriever
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
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
                    .subscribe().with { b -> println(b) }
            }

        /*vertx.deployVerticle(DnsOverHttpsVerticle())
            .onItem().transformToUni { id ->
                log.debug(id)
                vertx.eventBus().request<JsonObject>(DnsOverHttpsVerticle.DNS_ADDRESS, jsonObjectOf(
                    "dns" to "qad",
                    "domain" to "www.netflix.com.",
                    "type" to "A"
                ))
            }
            .onItem().transform { it.body() }
            .subscribe().with ({ result: JsonObject ->
                result.getJsonArray("Answer", jsonArrayOf()).forEach {
                    it as JsonObject
                    log.debug(it.encodePrettily())
                }
            }) {
                log.error(it.message)
            }*/

        /*QuadService.init(vertx)
        QuadService.query("www.netflix.com", "A")
            .subscribe().with { result ->
                result.forEach {
                    it as JsonObject
                    log.debug(it.encodePrettily())
                }
            }*/
    }
}