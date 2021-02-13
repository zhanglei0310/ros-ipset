package cn.foperate.ros.munity

import io.smallrye.mutiny.Multi
import me.legrange.mikrotik.ApiConnection
import me.legrange.mikrotik.MikrotikApiException
import me.legrange.mikrotik.ResultListener
import javax.net.SocketFactory

fun ApiConnection.executeAsMulti(command: String): Multi<Map<String, String>> {
    return Multi.createFrom().emitter {
        execute(command, object : ResultListener {
            private var completed = false
            override fun receive(p0: MutableMap<String, String>) {
                if (!completed) it.emit(p0)
            }

            override fun error(p0: MikrotikApiException) {
                // 已经加入某条记录的错误直接忽略掉。这里假设每次只会加入一条记录
                if (p0.message!!.contains("already have such entry")) {
                    it.complete()
                    completed = true
                }
                else if (!completed) it.fail(p0)
            }

            override fun completed() {
                if (!completed) {
                    it.complete()
                }
            }
        })
    }
}

fun rapidApiConnection(host:String) = ApiConnection.connect(SocketFactory.getDefault(), host, 8728, 5000)
