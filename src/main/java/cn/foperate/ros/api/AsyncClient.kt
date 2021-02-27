package cn.foperate.ros.api

import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.core.Vertx
import org.slf4j.LoggerFactory

object AsyncClient
{
    private val log = LoggerFactory.getLogger(AsyncClient::class.java)
    @JvmStatic
    fun main(args:Array<String>) {
        log.debug("异步客户端测试开始")

        val vertx = Vertx.vertx()
        val sf = AsyncSocketFactory(vertx, netClientOptionsOf(
            connectTimeout = RxApiConnection.DEFAULT_CONNECTION_TIMEOUT
        ))
        RxApiConnection.connection(sf, apiConnectionOptionsOf(
            host = "192.168.28.1",
            username = "jdns",
            password = "Ocwesw2r3"
        )).subscribe().with ({ conn ->
            log.info("Login successed")
            val command = Command("/ip/firewall/address-list/print", queries= mapOf(
                "list" to "PROXY"
            ), props = listOf("address","timeout")) //,q where  return address,timeout"
            conn.executeAsMulti(command)
                .onCompletion().invoke {
                    conn.close()
                }
                .subscribe().with {
                    log.debug(it.toString())
                }
            }){
                log.error(it.toString())
            }
    }
}