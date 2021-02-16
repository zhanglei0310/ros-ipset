package cn.foperate.ros.verticle

import cn.foperate.ros.munity.rapidApiConnection
import io.vertx.core.json.JsonObject
import org.apache.commons.pool2.PooledObjectFactory
import me.legrange.mikrotik.ApiConnection
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.impl.DefaultPooledObject
import java.lang.Exception

class RosApiConnFactory(config:JsonObject) : PooledObjectFactory<ApiConnection> {

    private val rosIp = config.getString("rosIp")
    private val rosUser = config.getString("rosUser")
    private val rosPwd = config.getString("rosPwd")

    override fun makeObject(): PooledObject<ApiConnection> {
        val rosConn = rapidApiConnection(rosIp)
        rosConn.setTimeout(3000)
        rosConn.login(rosUser, rosPwd)
        return DefaultPooledObject(rosConn)
    }

    override fun destroyObject(p: PooledObject<ApiConnection>) {
        val api = p.getObject()
        if (api.isConnected) try {
            p.getObject().close()
        } catch (e:Exception) {}
    }

    override fun validateObject(p: PooledObject<ApiConnection>): Boolean {
        return p.getObject().isConnected
    }

    override fun activateObject(p: PooledObject<ApiConnection>) {
    }

    override fun passivateObject(p: PooledObject<ApiConnection>) {
    }
}