package tech.stacktrace.jrodns

import io.vertx.core.json.JsonObject
import org.apache.commons.pool2.PooledObjectFactory
import me.legrange.mikrotik.ApiConnection
import kotlin.Throws
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.impl.DefaultPooledObject
import java.lang.Exception

class RosApiConnFactory(val config:JsonObject) : PooledObjectFactory<ApiConnection> {

    private val rosIp = config.getString("rosIp")
    private val rosUser = config.getString("rosUser")
    private val rosPwd = config.getString("rosPwd")

    @Throws(Exception::class)
    override fun makeObject(): PooledObject<ApiConnection> {
        val rosConn = ApiConnection.connect(rosIp)
        rosConn.login(rosUser, rosPwd)
        return DefaultPooledObject(rosConn)
    }

    @Throws(Exception::class)
    override fun destroyObject(p: PooledObject<ApiConnection>) {
        p.getObject().close()
    }

    override fun validateObject(p: PooledObject<ApiConnection>): Boolean {
        return p.getObject().isConnected
    }

    @Throws(Exception::class)
    override fun activateObject(p: PooledObject<ApiConnection>) {
    }

    @Throws(Exception::class)
    override fun passivateObject(p: PooledObject<ApiConnection>) {
    }
}