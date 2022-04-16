package cn.foperate.ros.verticle

import io.vertx.core.*
import io.vertx.core.http.HttpVersion
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.net.openSSLEngineOptionsOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.mutiny.core.eventbus.EventBus
import org.slf4j.LoggerFactory

class DnsOverHttpsVerticle: AbstractVerticle() {

  private lateinit var bus: EventBus
  private lateinit var cloudflareDns: WebClient
  private lateinit var quadDns: WebClient

  override fun init(vertx: Vertx, context: Context) {
    super.init(vertx, context)
    val mutinyVertx = io.vertx.mutiny.core.Vertx(vertx)
    bus = mutinyVertx.eventBus()

    setClients()
  }

  private fun setClients() {
    cloudflareDns = WebClient.create(vertx, webClientOptionsOf(
      http2KeepAliveTimeout = 60,
      http2MaxPoolSize = 1,
      openSslEngineOptions = openSSLEngineOptionsOf(
        sessionCacheEnabled = true
      ),
      protocolVersion = HttpVersion.HTTP_2, // 应该没有KEEP_ALIVE功能
      ssl = true,
      useAlpn = true,
      /*proxyOptions = proxyOptionsOf( // FIXME 正常环境是不需要的
        host = "127.0.0.1",
        port = 7890,
        type = ProxyType.SOCKS5
      )*/
    ))
    quadDns = WebClient.create(vertx, webClientOptionsOf(
      http2KeepAliveTimeout = 60,
      http2MaxPoolSize = 1,
      openSslEngineOptions = openSSLEngineOptionsOf(
        sessionCacheEnabled = true
      ),
      protocolVersion = HttpVersion.HTTP_2, // 应该没有KEEP_ALIVE功能
      ssl = true,
      useAlpn = true,
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
        queryCloudflare(query.getString("domain"))
      }
      answer.onSuccess{
        msg.reply(it)
      }.onFailure {
        log.error(it.message)
        msg.fail(500, it.message)
      }
    }

    // 每10分钟更新一次服务，有助于解决这个问题吗？
    /*vertx.setPeriodic(600000L) {
      val cloudflare = cloudflareDns
      val quad = quadDns

      setClients()

      cloudflare.close()
      quad.close()
    }*/

    startPromise.complete()
  }

  // https://cloudflare-dns.com/dns-query
  private fun queryCloudflare(domain: String): Future<JsonObject> =
    cloudflareDns.get(443, "1.1.1.1", "/dns-query")
      .virtualHost("cloudflare-dns.com")
      .addQueryParam("name", domain)
      .addQueryParam("type", "A")
      .putHeader("Accept", "application/dns-json")
      .timeout(10000L)
      .send()
      .map {
        it.bodyAsJsonObject()
      }
      .recover { error ->
        log.error("GFW查询错误 $domain: ${error.message}")
        // 出现网络异常，将状态设为SERVIAL
        Future.succeededFuture(jsonObjectOf(
          "Status" to 2,
          "Comment" to error.message
        ))
      }

  // https://dns.quad9.net:5053/dns-query
  private fun queryQuad(domain: String, type: String): Future<JsonObject> =
    quadDns.get(5053, "9.9.9.9", "/dns-query")
      .virtualHost("dns.quad9.net")
      .addQueryParam("name", domain)
      .addQueryParam("type", type)
      .timeout(10000L)
      .send()
      .map { it.bodyAsJsonObject() }
      .recover {
        log.error("Netflix查询错误 $domain($type): ${it.message}")
        // 出现网络异常，将状态设为SERVIAL
        Future.succeededFuture(jsonObjectOf(
          "Status" to 2,
          "Comment" to it.message
        ))
      }

  companion object {
    private val log = LoggerFactory.getLogger(DnsOverHttpsVerticle::class.java)
    const val DNS_ADDRESS = "doh.service"
  }
}