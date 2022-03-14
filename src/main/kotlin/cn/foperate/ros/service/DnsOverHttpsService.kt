package cn.foperate.ros.service

import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import org.slf4j.LoggerFactory

class DnsOverHttpsService(vertx: Vertx) {
    private val log = LoggerFactory.getLogger(DnsOverHttpsService::class.java)
    private val client: WebClient

    init {
        client = WebClient.create(vertx, webClientOptionsOf(
            ssl = true,
            maxPoolSize = 5,
            keepAlive = true,
            keepAliveTimeout = 60
        ))
    }

    // https://cloudflare-dns.com/dns-query
    fun queryCloudflare(domain: String): Uni<JsonArray> =
        client.get(443, "1.1.1.1", "/dns-query")
            .putHeader("Host", "cloudflare-dns.com:443")
            .addQueryParam("name", domain)
            .addQueryParam("type", "A")
            .putHeader("Accept", "application/dns-json")
            .timeout(3000L)
            .send()
            .onItem().transform {
                it.bodyAsJsonObject().getJsonArray("Answer", jsonArrayOf())
            }
            .onFailure().recoverWithItem{ error ->
                log.error(error.message)
                jsonArrayOf()
            }

    // https://dns.quad9.net:5053/dns-query
    fun queryQuad(domain: String, type: String): Uni<JsonArray> =
        client.get(5053, "9.9.9.9", "/dns-query")
            .putHeader("Host", "dns.quad9.net:5053")
            .addQueryParam("name", domain)
            .addQueryParam("type", type)
            .timeout(3000L)
            .send()
            .onItem().transform {
                log.debug(it.bodyAsString())
                it.bodyAsJsonObject().getJsonArray("Answer", jsonArrayOf())
            }
            .onFailure().recoverWithItem{ error ->
                log.error(error.message)
                jsonArrayOf()
            }
}