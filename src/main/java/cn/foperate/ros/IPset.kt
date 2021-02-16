package cn.foperate.ros

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import cn.foperate.ros.pac.DomainUtil
import cn.foperate.ros.verticle.DnsVeticle
import cn.foperate.ros.verticle.RosVerticle
import io.vertx.core.VertxOptions
import io.vertx.core.logging.SLF4JLogDelegateFactory
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.mutiny.core.Vertx
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.*


object IPset {
    private val logger = LoggerFactory.getLogger(IPset::class.java)

    private lateinit var gfwlistPath: String
    private lateinit var whitelistPath: String
    private lateinit var rosUser: String
    private lateinit var rosPwd: String
    private lateinit var rosIp: String
    private lateinit var rosFwadrKey: String
    private var rosIdle: Int = 30
    private var maxThread = 8
    private var localPort: Int = 53
    private lateinit var remote: String
    private var remotePort: Int = 53
    private var fallback: String = "223.5.5.5"

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

        val maxThreadStr = properties.getProperty("maxThread")
        if (!maxThreadStr.isNullOrBlank()) {
            maxThread = Integer.valueOf(maxThreadStr)
        }

        val localPortStr = properties.getProperty("localPort")
        if (!localPortStr.isNullOrBlank()) {
            localPort = Integer.valueOf(localPortStr)
        }

        val remotePortStr = properties.getProperty("remotePort")
        if (!remotePortStr.isNullOrBlank()) {
            remotePort = Integer.valueOf(remotePortStr)
        }

        val rosIdleStr = properties.getProperty("rosIdle")
        rosIdle = if (rosIdleStr.isNotBlank()) {
            Integer.valueOf(rosIdleStr)
        } else {
            30
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
    fun main(args:Array<String>) {
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

        val vertx = Vertx.vertx(VertxOptions().setWorkerPoolSize(maxThread))
        vertx.deployVerticle(RosVerticle(), deploymentOptionsOf(
            config = jsonObjectOf(
                "rosFwadrKey" to rosFwadrKey,
                "rosIp" to rosIp,
                "rosUser" to rosUser,
                "rosPwd" to rosPwd,
                "maxThread" to maxThread,
                "rosIdle" to rosIdle
            ), worker = true
        )).onFailure().invoke { e ->
            logger.error(e.message)
            vertx.close().subscribeAsCompletionStage()
        }.subscribe().with {  }
        vertx.deployVerticleAndAwait(DnsVeticle(), deploymentOptionsOf(
            config = jsonObjectOf(
                "remotePort" to remotePort,
                "remote" to remote,
                "localPort" to localPort,
                "fallback" to fallback,
                "blockAddress" to blockAddress
            )
        ))

        logger.info("server started")
    }
}