package cn.foperate.ros

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.service.DnsOverHttpsService
import cn.foperate.ros.verticle.NettyDnsVerticle
import cn.foperate.ros.service.RestService
import cn.foperate.ros.verticle.RestVerticle
import io.vertx.config.ConfigRetriever
import io.vertx.core.Vertx
import io.vertx.core.logging.SLF4JLogDelegateFactory
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL

object IPset {
    private val logger = LoggerFactory.getLogger(IPset::class.java)

    private fun checkFile(path: String): File {
        val classLoader = javaClass.classLoader
        val resource = classLoader.getResource(path)

        if (resource == null) {
            throw RuntimeException("$path not found")
        } else {
            return File(resource.file)
        }
    }

    private fun setLogger() {
        System.setProperty("vertx.logger-delegate-factory-class-name",
            SLF4JLogDelegateFactory::class.java.name)

        // if logback.xml in current work dir, use it
        try {
            val file = checkFile("logback.xml")
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext

            val configurator = JoranConfigurator()
            configurator.context = lc
            lc.reset()
            configurator.doConfigure(file)
        } catch (e:Exception) {}

    }

    @JvmStatic
    fun main(args:Array<String>): Unit = runBlocking {

        setLogger()

        val vertx = Vertx.vertx(vertxOptionsOf(
            preferNativeTransport = true
        ))

        val config = ConfigRetriever.create(vertx)
            .config
            .await()
        logger.info("config file loaded success")

        val lists = config.getJsonObject("list")

        logger.debug(lists.encodePrettily())

        lists.getJsonArray("proxy", jsonArrayOf("gfwlist.txt"))
            .map { it as String }
            .filter(String::isNotBlank)
            .forEach {
                DomainUtil.loadBlackList(checkFile(it))
            }
        lists.getJsonArray("direct")
            .map { it as String }
            .filter(String::isNotBlank)
            .forEach {
                DomainUtil.loadWhiteList(checkFile(it))
            }
        lists.getJsonArray("block")
            .map { it as String }
            .filter(String::isNotBlank)
            .forEach {
                DomainUtil.loadAdblockList(checkFile(it))
            }
        lists.getString("netflix", "netflix.txt")
            .let {
                DomainUtil.loadNetflixList(checkFile(it))
            }

        logger.info("GFWList load completed")

        val dns = vertx.createDnsClient(53, "223.5.5.5")
        lists.getString("netflix", "https://cdn.jsdelivr.net/gh/QiuSimons/Netflix_IP@master/NF_only.txt")
            .let {
                val url = URL(it)
                val host = url.host
                dns.lookup4(host)
                    .compose { ip ->
                        val client = WebClient.create(vertx)
                        val port = if (url.port==-1) 443 else url.port
                        client.get(port, ip, it).send()
                    }
                    .onSuccess { res ->
                        val buffer = res.bodyAsBuffer()
                        DomainUtil.loadNetflixList(buffer)
                        logger.debug("Netflix地址加载成功")
                    }
                    .onFailure { error ->
                        logger.error(error.message, error)
                    }
        }

        val mutiny = io.vertx.mutiny.core.Vertx(vertx)
        RestService.init(mutiny, config.getJsonObject("ros"))
        DnsOverHttpsService.init(mutiny)

        vertx.deployVerticle(
            RestVerticle(), deploymentOptionsOf(
                config = config.getJsonObject("ros")
            )
        ).onFailure { e ->
            logger.error(e.message, e)
            vertx.close()
        }.onSuccess {
            logger.info("RosService init completed")
        }
        vertx.deployVerticle(
            NettyDnsVerticle(), deploymentOptionsOf(
                config = config.getJsonObject("dns")
            )
        )

        logger.info("server started")
    }
}