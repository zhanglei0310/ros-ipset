package cn.foperate.ros.verticle

import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import org.slf4j.LoggerFactory

object RestService {
    private val log = LoggerFactory.getLogger(RestService::class.java)
    private val base = "/rest"
    private lateinit var client: WebClient
    private val rosFwadrKey = "PROXY"

    fun init(vertx: Vertx, config: JsonObject) {
        client = WebClient.create(vertx, webClientOptionsOf(
            defaultHost = "192.168.28.1",
            defaultPort = 443,
            ssl = true,
            trustAll = true,
            verifyHost = false,
        ))
    }

    fun queryAddressListIDs(list: String): Uni<List<String>> {
        return client.get("$base/ip/firewall/address-list")
            .basicAuthentication("jdns", "Ocwesw2r3")
            .addQueryParam("list", list)
            .addQueryParam(".proplist", ".id")
            .send()
            .onItem().transform {
                it.bodyAsJsonArray()
                    .map { id ->
                        id as JsonObject
                        id.getString(".id")
                    }
            }
    }

    fun queryAddressList(list: String): Uni<JsonArray> {
        return client.get("$base/ip/firewall/address-list")
            .basicAuthentication("jdns", "Ocwesw2r3")
            .addQueryParam("list", list)
            .send()
            .onItem().transform {
                it.bodyAsJsonArray()
            }
    }

    fun addProxyAddress(ip: String, domain: String): Uni<JsonObject> {
        val item = jsonObjectOf(
            "list" to rosFwadrKey,
            "address" to ip,
            "timeout" to "24h",
            "comment" to domain
        )
        return client
            .put("$base/ip/firewall/address-list")
            .basicAuthentication("jdns", "Ocwesw2r3")
            .sendJsonObject(item)
            .onItem().transform { it.bodyAsJsonObject() }
    }

    fun addNetflixAddress(ip: String): Uni<JsonObject> {
        val item = jsonObjectOf(
            "list" to "NETFLIX",
            "address" to ip
        )
        return client
            .put("$base/ip/firewall/address-list")
            .basicAuthentication("jdns", "Ocwesw2r3")
            .sendJsonObject(item)
            .onItem().transform { it.bodyAsJsonObject() }
    }

    fun deleteAddressListItem(id: String): Uni<String> {
        return client
            .delete("$base/ip/firewall/address-list/$id")
            .basicAuthentication("jdns", "Ocwesw2r3")
            .send()
            .onItem().transform { id }
    }
}