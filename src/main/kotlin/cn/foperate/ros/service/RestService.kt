package cn.foperate.ros.service

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpVersion
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import org.slf4j.LoggerFactory

object RestService {
    private val log = LoggerFactory.getLogger(RestService::class.java)
    private const val base = "/rest"
    private lateinit var client: WebClient
    private lateinit var rosListKey: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String

    fun init(vertx: Vertx, config: JsonObject) {
        client = WebClient.create(vertx, webClientOptionsOf(
            connectTimeout = 5000,
            defaultHost = config.getString("host"),
            defaultPort = config.getInteger("port", 443),
            keepAlive = true,
            keepAliveTimeout = 60,
            maxPoolSize = 2,
            protocolVersion = HttpVersion.HTTP_1_1, // 应该没有KEEP_ALIVE功能
            ssl = true,
            trustAll = true,
            verifyHost = false,
        ))
        rosListKey = config.getString("listName")
        rosUser = config.getString("user")
        rosPwd = config.getString("password")
    }

    fun queryAddressListIDs(list: String): Multi<String> {
        return client.get("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
            .addQueryParam("list", list)
            .addQueryParam(".proplist", ".id")
            .send()
            .onItem().transformToMulti {
                Multi.createFrom().iterable(it.bodyAsJsonArray())
                    .onItem().transform { id ->
                        id as JsonObject
                        id.getString(".id")
                    }
            }
    }

    private fun queryAddressList(list: String): Uni<JsonArray> {
        return client.get("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
            .addQueryParam("list", list)
            .send()
            .onItem().transform {
                it.bodyAsJsonArray()
            }
    }

    fun addOrUpdateProxyAddress(ip: String, domain: String): Uni<Boolean> {
        return client.get("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
            .addQueryParam("list", rosListKey)
            .addQueryParam("address", ip)
            .addQueryParam(".proplist", ".id")
            .send()
            .onItem().transform {
                it.bodyAsJsonArray()
            }
            .onItem().transformToUni { result ->
                if (result.isEmpty) {
                    val item = jsonObjectOf(
                        "list" to rosListKey,
                        "address" to ip,
                        "timeout" to "2h",
                        "comment" to domain
                    )
                    client
                        .put("$base/ip/firewall/address-list")
                        .basicAuthentication(rosUser, rosPwd)
                        .sendJsonObject(item)
                        .onItem().transform {
                            true // it.bodyAsJsonObject()
                        }
                } else {
                    val id = result.getJsonObject(0).getString(".id")
                    val item = jsonObjectOf(
                        "timeout" to "2h"
                    )
                    client.put("$base/ip/firewall/address-list/$id")
                        .basicAuthentication(rosUser, rosPwd)
                        .sendJsonObject(item)
                        .onItem().transform {
                            true // it.bodyAsJsonObject()
                        }
                }
            }
            .onFailure().recoverWithItem { e ->
                log.error(e.message)
                false
            }

    }

    fun addStaticAddress(ip: String, domain: String, listName: String = rosListKey ): Uni<Boolean> {
        val item = jsonObjectOf(
            "list" to listName,
            "address" to ip,
            "comment" to domain
        )
        return client
            .put("$base/ip/firewall/address-list")
            .basicAuthentication(rosUser, rosPwd)
            .sendJsonObject(item)
            .onItem().transform{ true }
            .onFailure().recoverWithItem(false)
    }

    fun deleteAddressListItem(id: String): Uni<String> {
        return client
            .delete("$base/ip/firewall/address-list/$id")
            .basicAuthentication(rosUser, rosPwd)
            .send()
            .onItem().transform { id }
    }
}