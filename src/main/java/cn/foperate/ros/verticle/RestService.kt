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
    private lateinit var rosListKey: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String

    fun init(vertx: Vertx, config: JsonObject) {
        client = WebClient.create(vertx, webClientOptionsOf(
            defaultHost = config.getString("host"),
            defaultPort = config.getInteger("port", 443),
            ssl = true,
            trustAll = true,
            verifyHost = false
        ))
        rosListKey = config.getString("listName")
        rosUser = config.getString("user")
        rosPwd = config.getString("password")
    }

    fun queryAddressListIDs(list: String): Uni<List<String>> {
        return client.get("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
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
            .basicAuthentication(rosUser, rosPwd)
            .addQueryParam("list", list)
            .send()
            .onItem().transform {
                it.bodyAsJsonArray()
            }
    }

    fun addProxyAddress(ip: String, domain: String): Uni<JsonObject> {
        val item = jsonObjectOf(
            "list" to rosListKey,
            "address" to ip,
            "timeout" to "24h",
            "comment" to domain
        )
        return client
            .put("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
            .sendJsonObject(item)
            .onItem().transform { it.bodyAsJsonObject() }
    }

    fun addStaticAddress(ip: String, domain: String, listName: String = rosListKey ): Uni<JsonObject> {
        val item = jsonObjectOf(
            "list" to listName,
            "address" to ip,
            "comment" to domain
        )
        return client
            .put("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
            .sendJsonObject(item)
            .onItem().transform { it.bodyAsJsonObject() }
    }

    fun deleteAddressListItem(id: String): Uni<String> {
        return client
            .delete("$base/ip/firewall/address-list/$id")
            .basicAuthentication(rosUser, rosPwd)
            .send()
            .onItem().transform { id }
    }
}