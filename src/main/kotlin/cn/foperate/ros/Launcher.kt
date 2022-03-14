package cn.foperate.ros

import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.service.RestService
import cn.foperate.ros.verticle.DnsOverHttpsVerticle
import cn.foperate.ros.verticle.NettyDnsVerticle
import cn.foperate.ros.verticle.RestVerticle
import io.vertx.config.ConfigRetriever
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.coroutines.await
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URL

object Launcher {
  private val logger = LoggerFactory.getLogger(Launcher::class.java)

  @JvmStatic
  fun main(args: Array<String>) = runBlocking {

    val vertx = Vertx.vertx(
      vertxOptionsOf(
      preferNativeTransport = false
    ))

    val config = ConfigRetriever.create(vertx.delegate)
      .config
      .await()
    logger.info("config file loaded success")

    val lists = config.getJsonObject("list")

    logger.debug(lists.encodePrettily())

    val fs = vertx.fileSystem()

    lists.getJsonArray("proxy", jsonArrayOf("gfwlist.txt"))
      .map { it as String }
      .filter(String::isNotBlank)
      .forEach {
        DomainUtil.loadBlackList(fs, it)
      }
    lists.getJsonArray("direct")
      .map { it as String }
      .filter(String::isNotBlank)
      .forEach {
        DomainUtil.loadWhiteList(fs, it)
      }
    lists.getJsonArray("block")
      .map { it as String }
      .filter(String::isNotBlank)
      .forEach {
        DomainUtil.loadAdblockList(fs, it)
      }
    lists.getString("netflix", "netflix.txt")
      .let {
        DomainUtil.loadNetflixList(fs, it)
      }

    logger.info("GFWList load completed")

    val dns = vertx.createDnsClient(53, "223.5.5.5")
    lists.getString("netflix", "https://cdn.jsdelivr.net/gh/QiuSimons/Netflix_IP@master/NF_only.txt")
      .let {
        val url = URL(it)
        val host = url.host
        dns.lookup4(host)
          .onItem().transformToUni { ip ->
            val client = WebClient.create(vertx)
            val port = if (url.port==-1) 443 else url.port
            client.get(port, ip, it).send()
          }
          .subscribe().with({ res ->
            val buffer = res.bodyAsBuffer()
            DomainUtil.loadNetflixList(buffer.delegate)
            logger.debug("Netflix地址加载成功")
          }) { error ->
            logger.error(error.message, error)
          }
      }

    RestService.init(vertx, config.getJsonObject("ros"))

    vertx.deployVerticle(DnsOverHttpsVerticle())
      .subscribe().with ({
        logger.info("DnsOverHttpsVerticle init completed")
      }) { e ->
        logger.error(e.message, e)
      }
    vertx.deployVerticle(
      RestVerticle(), deploymentOptionsOf(
        config = config.getJsonObject("ros")
      )
    ).subscribe().with({
      logger.info("RosVerticle init completed")
    }) { e ->
      logger.error(e.message, e)
      //vertx.close()
    }
    vertx.deployVerticle(
      NettyDnsVerticle(), deploymentOptionsOf(
        config = config.getJsonObject("dns")
      )
    ).subscribe().with({
      logger.info("NettyDnsVerticle init completed")
    }){ e ->
      logger.error(e.message, e)
    }

    logger.info("server started")
  }
}