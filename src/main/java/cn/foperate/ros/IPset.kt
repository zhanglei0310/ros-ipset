package cn.foperate.ros

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.verticle.NettyDnsVerticle
import cn.foperate.ros.verticle.RestVerticle
import cn.foperate.ros.verticle.RosVerticle
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.logging.SLF4JLogDelegateFactory
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URL
import java.util.*

object IPset {
    private val logger = LoggerFactory.getLogger(IPset::class.java)

    private lateinit var gfwlistPath: String
    private lateinit var netflixPath: String
    private lateinit var whitelistPath: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String
    private lateinit var rosIp: String
    private lateinit var rosFwadrKey: String
    private var localPort: Int = 53
    private lateinit var remote: String
    private var remotePort: Int = 53
    private var fallback: String = "223.6.6.6"

    private lateinit var adblockPath: String
    private var blockAddress: String = "224.0.0.1"

    private fun checkFile(path: String): File {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            throw RuntimeException("$path not found")
        }
        return file
    }

    @Throws(IOException::class)
    private fun readProp(configFilePath: String) {

        val file = checkFile(configFilePath)
        val properties = Properties()
        properties.load(FileInputStream(file))
        gfwlistPath = properties.getProperty("gfwlistPath", "gfwlist.txt")
        netflixPath = properties.getProperty("netflixPath", "https://cdn.jsdelivr.net/gh/QiuSimons/Netflix_IP@master/NF_only.txt")
        whitelistPath = properties.getProperty("whitelistPath", "")
        adblockPath = properties.getProperty("adblockPath", "")
        blockAddress = properties.getProperty("blockAddress", blockAddress)
        rosUser = properties.getProperty("rosUser")
        rosPwd = properties.getProperty("rosPwd")
        rosIp = properties.getProperty("rosIp")
        rosFwadrKey = properties.getProperty("rosFwadrKey")
        remote = properties.getProperty("remote")

        val fb = properties.getProperty("fallback")
        if (!fb.isNullOrBlank()) {
            fallback = fb
        }

        val localPortStr = properties.getProperty("localPort")
        if (!localPortStr.isNullOrBlank()) {
            localPort = Integer.valueOf(localPortStr)
        }

        val remotePortStr = properties.getProperty("remotePort")
        if (!remotePortStr.isNullOrBlank()) {
            remotePort = Integer.valueOf(remotePortStr)
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

        var configFilePath = "jrodns.properties"
        if (args.isNotEmpty()) {
            configFilePath = args[0]
        }

        readProp(configFilePath)

        logger.info("config file verify success")

        logger.info("RosService init completed")

        gfwlistPath.split((","))
            .filter(String::isNotBlank)
            .forEach {
                DomainUtil.loadBlackList(checkFile(it))
            }
        whitelistPath.split(",")
            .filter(String::isNotBlank)
            .forEach {
                DomainUtil.loadWhiteList(it)
            }
        adblockPath.split(",")
            .filter(String::isNotBlank)
            .forEach {
                DomainUtil.loadAdblockList(it)
            }

        logger.info("GFWList load completed")

        val vertx = Vertx.vertx(vertxOptionsOf(
            preferNativeTransport = true
        ))
        val dns = vertx.createDnsClient(53, "223.5.5.5")
        netflixPath.let {
            val url = URL(it)
            val host = dns.lookup4(url.host).await()
            val client = vertx.createHttpClient()
            val request = client.request(HttpMethod.GET, 80, host, it).await()
            request.host = url.host
            val response = request.send().await()
            val body = response.body().await()
            DomainUtil.loadNetflixList(body)
        }
        vertx.deployVerticle(
            RestVerticle(), deploymentOptionsOf(
                config = jsonObjectOf(
                    "rosFwadrKey" to rosFwadrKey,
                    "rosIp" to rosIp,
                    "rosUser" to rosUser,
                    "rosPwd" to rosPwd
                )
            )
        ).onFailure { e ->
            logger.error(e.message)
            vertx.close()
        }
        vertx.deployVerticle(
            NettyDnsVerticle(), deploymentOptionsOf(
                config = jsonObjectOf(
                    "remotePort" to remotePort,
                    "remote" to remote,
                    "localPort" to localPort,
                    "fallback" to fallback,
                    "blockAddress" to blockAddress
                )
            )
        )

        logger.info("server started")
    }
}