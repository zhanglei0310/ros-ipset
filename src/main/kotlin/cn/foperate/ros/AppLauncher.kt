package cn.foperate.ros

import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.service.RestService
import cn.foperate.ros.verticle.DnsOverHttpsVerticle
import cn.foperate.ros.verticle.NettyDnsVerticle
import cn.foperate.ros.verticle.RestVerticle
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import io.smallrye.mutiny.vertx.UniHelper
import io.smallrye.mutiny.vertx.core.AbstractVerticle
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.core.Context
import io.vertx.core.Launcher
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.config.configStoreOptionsOf
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.mutiny.ext.web.client.WebClient
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.URL

/***
 * 2022-4-22 做了个比较完整的修改，将启动模式转回到Vertx标准模式。
 */
class AppLauncher: AbstractVerticle() {

  override fun init(vertx: Vertx, context: Context) {
    super.init(vertx, context)
    Infrastructure.setDroppedExceptionHandler { err ->
      logger.error("发现Mutiny未处理的异常：", err)
    }
  }

  override fun asyncStart(): Uni<Void> {
    val dispatcher: CoroutineDispatcher = vertx.delegate.dispatcher()
    val retriever = ConfigRetriever.create(vertx.delegate, ConfigRetrieverOptions().addStore(
      configStoreOptionsOf(
        type = "file",
        config = jsonObjectOf(
          "path" to "conf/config.json"
        ))
    ))


    return UniHelper.toUni(retriever.config)
      .onItem().transformToUni { config ->
        logger.info("config file loaded success")

        val lists = config.getJsonObject("list")
        logger.debug(lists.encodePrettily())

        val fs = vertx.fileSystem()
        Uni.createFrom().emitter<JsonObject> { em ->
          CoroutineScope(dispatcher).launch {
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
                    val port = if (url.port == -1) 443 else url.port
                    client.get(port, ip, it)
                      .virtualHost("cdn.jsdelivr.net")
                      .ssl(true)
                      .send()
                  }
                  .subscribe().with({ res ->
                    val buffer = res.bodyAsBuffer()
                    DomainUtil.loadNetflixList(buffer)
                    logger.info("Netflix地址加载成功")
                  }) { error ->
                    logger.error(error.message, error)
                  }
              }
            em.complete(config)
          }
        }
      }
      .onItem().transformToUni { config ->

        RestService.init(vertx, config.getJsonObject("ros"))

        Uni.join().all(
          vertx.deployVerticle(DnsOverHttpsVerticle()),
          vertx.deployVerticle(
            RestVerticle(), deploymentOptionsOf(
              config = config.getJsonObject("ros")
            )
          ).onItem().transformToUni { _ ->
            logger.info("RosVerticle init completed")
            vertx.deployVerticle(
              NettyDnsVerticle(), deploymentOptionsOf(
                config = config.getJsonObject("dns")
              )
            )
          }
        ).andCollectFailures()
      }.onItem().transformToUni<Void?> { t, u ->
        logger.info("server started")
        Uni.createFrom().voidItem()
      }.onFailure().invoke { e->
        logger.error(e.message, e)
      }
  }

  class NativeLauncher: Launcher() {
    override fun beforeStartingVertx(options: VertxOptions) {
      options.preferNativeTransport = true
    }
  }
  companion object {
    private val logger = LoggerFactory.getLogger(AppLauncher::class.java)

    @JvmStatic
    fun main(vararg args: String) {
      NativeLauncher().dispatch(arrayOf(
        "run", "cn.foperate.ros.AppLauncher"
      ))
    }
    /*fun main(args: Array<String>): Unit = runBlocking {
      val config = ConfigRetriever.create(vertx.delegate)
        .config
        .await()

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
              val port = if (url.port == -1) 443 else url.port
              client.get(port, ip, it)
                .virtualHost("cdn.jsdelivr.net")
                .ssl(true)
                .send()
            }
            .subscribe().with({ res ->
              val buffer = res.bodyAsBuffer()
              DomainUtil.loadNetflixList(buffer)
              logger.info("Netflix地址加载成功")
            }) { error ->
              logger.error(error.message, error)
            }
        }

      RestService.init(vertx, config.getJsonObject("ros"))

      vertx.deployVerticle(DnsOverHttpsVerticle())
        .subscribe().with({
          logger.info("DnsOverHttpsVerticle init completed")
        }) { e ->
          logger.error(e.message, e)
        }
      vertx.deployVerticle(
        RestVerticle(), deploymentOptionsOf(
          config = config.getJsonObject("ros")
        )
      ).onItem().transformToUni { _ ->
        logger.info("RosVerticle init completed")
        vertx.deployVerticle(
          NettyDnsVerticle(), deploymentOptionsOf(
            config = config.getJsonObject("dns")
          )
        )
      }.subscribe().with({
        logger.info("NettyDnsVerticle init completed")
      }) { e ->
        logger.error(e.message, e)
      }
      Infrastructure.setDroppedExceptionHandler { err ->
        logger.error("发现Mutiny未处理的异常：", err)
      }

      logger.info("server started")
    }*/
  }
}