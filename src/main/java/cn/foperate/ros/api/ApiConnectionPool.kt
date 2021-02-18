package cn.foperate.ros.api

import io.vertx.core.Context
import io.vertx.core.net.impl.clientconnection.ConnectionProvider
import io.vertx.core.net.impl.clientconnection.Pool
import me.legrange.mikrotik.impl.AsyncApiConnection
import java.util.function.Consumer

class ApiConnectionPool: Pool<AsyncApiConnection> {

    private constructor(ctx: Context,
                connectionProvider: ConnectionProvider<AsyncApiConnection>,
                queueMaxSize:Int,
                maxWeight:Long,
                connectionAdded: Consumer<AsyncApiConnection>,
                connectionRemoved: Consumer<AsyncApiConnection>):super(ctx,
        connectionProvider,
        queueMaxSize,
        1,
        maxWeight,
        connectionAdded,
        connectionRemoved,
        false)
    {}

    fun connectionRemoved(conc:AsyncApiConnection) {

    }

    class ConnectionAdded(val pool: Pool<AsyncApiConnection>):Consumer<AsyncApiConnection> {
        override fun accept(t: AsyncApiConnection) {
            TODO("Not yet implemented")
        }

    }

    companion object {
        /*fun getPool(ctx: Context, options: ApiConnectionOptions,
                    connectionProvider: ConnectionProvider<AsyncApiConnection>):ApiConnectionPool {
            return ApiConnectionPool(ctx,
                connectionProvider,
                options.maxPoolWaiting,
                options.maxPoolSize.toLong(),
                ConnectionAdded,
                { conn -> connectionRemoved(conn) }
            )
        }*/
    }
}

fun ApiConnectionPool.connectionAdded(conn:AsyncApiConnection) {

}

data class ApiConnectionOptions(
    var maxPoolWaiting: Int = 0,
    var maxPoolSize: Int

)