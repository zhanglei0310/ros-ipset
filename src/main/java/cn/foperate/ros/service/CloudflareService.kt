package cn.foperate.ros.service

import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import org.slf4j.LoggerFactory

object CloudflareService {
    private val log = LoggerFactory.getLogger(CloudflareService::class.java)
    private lateinit var client: WebClient

    fun init(vertx: Vertx) {
        client = WebClient.create(vertx, webClientOptionsOf(
            ssl = true,
            defaultHost = "1.1.1.1",
            defaultPort = 443,
            maxPoolSize = 5
        ))
    }

    fun query(domain: String): Uni<JsonArray> =
        client.get(443, "1.1.1.1", "https://cloudflare-dns.com/dns-query")
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
}