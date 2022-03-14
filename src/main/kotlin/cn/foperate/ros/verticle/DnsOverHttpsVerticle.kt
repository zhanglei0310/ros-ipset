package cn.foperate.ros.verticle

import io.smallrye.mutiny.Uni
import io.vertx.core.AbstractVerticle
import io.vertx.core.Context
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.ProxyType
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.net.proxyOptionsOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.mutiny.ext.web.client.WebClient
import org.slf4j.LoggerFactory

class DnsOverHttpsVerticle: AbstractVerticle() {

  private lateinit var bus: EventBus
  private lateinit var client: WebClient

  override fun init(vertx: Vertx, context: Context) {
    super.init(vertx, context)
    val mutinyVertx = io.vertx.mutiny.core.Vertx(vertx)
    //dns = DnsOverHttpsService(mutinyVertx)
    //quadDns = QuadService(mutinyVertx)
    bus = vertx.eventBus()
    client = WebClient.create(mutinyVertx, webClientOptionsOf(
      ssl = true,
      maxPoolSize = 5,
      keepAlive = true,
      keepAliveTimeout = 60,
      /*proxyOptions = proxyOptionsOf( // FIXME 正常环境是不需要的
        host = "127.0.0.1",
        port = 7890,
        type = ProxyType.SOCKS5
      )*/
    ))
  }

  override fun start(startPromise: Promise<Void>) {
    log.debug("启动Doh转发服务")
    bus.consumer<JsonObject>(DNS_ADDRESS) { msg ->
      val query = msg.body()
      val answer = if (query.getString("dns")=="quad") {
        queryQuad(
          query.getString("domain"),
          query.getString("type")
        )
      } else {
        queryCloudflare( query.getString("domain") )
      }
      answer.subscribe().with { msg.reply(it) }
    }
    startPromise.complete()
  }

  // https://cloudflare-dns.com/dns-query
  private fun queryCloudflare(domain: String): Uni<JsonArray> =
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
  private fun queryQuad(domain: String, type: String): Uni<JsonArray> =
    client.get(5053, "9.9.9.9", "/dns-query")
      .putHeader("Host", "dns.quad9.net:5053")
      .addQueryParam("name", domain)
      .addQueryParam("type", type)
      .timeout(3000L)
      .send()
      .onItem().transform {
        // log.debug(it.bodyAsString())
        it.bodyAsJsonObject().getJsonArray("Answer", jsonArrayOf())
      }
      .onFailure().recoverWithItem{ error ->
        log.error(error.message)
        jsonArrayOf()
      }

  companion object {
    private val log = LoggerFactory.getLogger(DnsOverHttpsVerticle::class.java)
    const val DNS_ADDRESS = "doh.service"
  }
}