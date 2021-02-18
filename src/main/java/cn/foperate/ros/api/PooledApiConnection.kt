package cn.foperate.ros.api

import cn.foperate.ros.munity.AsyncSocketFactory
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.UniEmitter
import io.vertx.mutiny.core.Vertx
import me.legrange.mikrotik.impl.ApiCommandException
import me.legrange.mikrotik.impl.AsyncApiConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

class PooledApiConnection(vertx: Vertx): AsyncApiConnection(vertx) {

    private val key = Random.nextLong()
    private var using = false
    private var idleTime = System.currentTimeMillis()

    override fun close() {
        using = false
        if (isConnected()) {
            returnConnection(this)
        } else {
            connMap.remove(key)
        }
    }

    private fun forceClose() = super.close()

    override fun executeAsMulti(cmd: String): Multi<Map<String, String>> {
        return super.executeAsMulti(cmd)
            .onFailure().invoke { e ->
                if (e !is ApiCommandException) {
                    forceClose()
                }
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PooledApiConnection::class.java)
        private val connMap = mutableMapOf<Long, PooledApiConnection>()
        private val connPool = ConcurrentLinkedQueue<PooledApiConnection>()
        private val requestQueue = ConcurrentLinkedQueue<UniEmitter<in PooledApiConnection>>()

        // 每次只试着纠正一条记录，足以纠正异常的情况
        fun evite() {
            if (connPool.isNotEmpty()) {
                requestQueue.poll()?.complete(connPool.poll())

                connPool.poll()?.let {
                    if (it.idleTime > System.currentTimeMillis() - 30000) {
                        it.forceClose()
                    }
                }
            }

            // 去掉不正常的等待连接，应该是没有任何作用
            connMap.filter { !connPool.contains(it.value) }
                .filter { !it.value.using || !it.value.isConnected() }
                .map { it.key }
                .forEach { connMap.remove(it) }
        }

        private fun returnConnection(conn:PooledApiConnection) {
            conn.idleTime = System.currentTimeMillis()
            requestQueue.poll()?.let {
                conn.using = true
                it.complete(conn)
            } ?: connPool.add(conn)
        }

        fun connect(fact: AsyncSocketFactory, options: ApiConnectionOptions): Uni<PooledApiConnection> {
            connPool.poll()?.let {
                val conn = connPool.remove()
                conn.using = true
                return@connect Uni.createFrom().item(conn)
            }
            return Uni.createFrom().emitter { em ->
                if (connMap.size>=10) {
                    requestQueue.add(em)
                    return@emitter
                }

                val conn = PooledApiConnection(fact.vertx)

                conn.open(options.host, options.port, fact, options.idleTimeout)
                    .onItem().transformToUni { _ ->
                        conn.login(options.username, options.password)
                    }.subscribe().with ({
                        log.info("Login successed")
                        conn.using = true
                        connMap[conn.key] = conn
                        em.complete(conn)
                    }) {
                        conn.forceClose()
                        log.error(it.message)
                        em.fail(it)
                    }
            }
        }
    }
}