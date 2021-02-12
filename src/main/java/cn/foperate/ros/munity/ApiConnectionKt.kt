package cn.foperate.ros.munity

import io.smallrye.mutiny.Multi
import me.legrange.mikrotik.ApiConnection
import me.legrange.mikrotik.MikrotikApiException
import me.legrange.mikrotik.ResultListener

fun ApiConnection.executeAsMulti(command: String): Multi<Map<String, String>> {
    return Multi.createFrom().emitter {
        execute(command, object : ResultListener {
            private var completed = false
            override fun receive(p0: MutableMap<String, String>) {
                if (!completed) it.emit(p0)
            }

            override fun error(p0: MikrotikApiException) {
                if (!completed) it.fail(p0)
            }

            override fun completed() {
                if (!completed) {
                    it.complete()
                }
            }
        })
    }
}