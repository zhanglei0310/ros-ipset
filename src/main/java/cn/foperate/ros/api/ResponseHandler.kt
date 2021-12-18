package cn.foperate.ros.api

import cn.foperate.ros.api.Response.State
import org.slf4j.LoggerFactory
import java.util.function.Consumer

/***
 * Responser的基础实现，单纯对所取得的数据消息的处理
 * 从sentence中读取结果的类，使用状态机来实现
 * @author Aston Mei
 */
@Deprecated("改用RestAPI实现")
open class ResponseHandler(val defaultListener: ResponseListener): Consumer<List<String>> {

    protected val listeners = mutableMapOf<String, ResponseListener>()

    fun waiting(tag: String, listener: ResponseListener) = listeners.put(tag, listener)
    fun forget(tag: String) = listeners.remove(tag)

    // 作为订阅者的实现，是一个被动的实现
    override fun accept(list: List<String>) {
        // 实际上在这里已经知道每个sentence的作用，所以需要首先把sentence解析出来
        val response = Response.fromList(list)

        if (response.state==State.HALT) {
            // 连接中断的情况单独处理
            // FIXME 怀疑这里可能是reason
            log.error("Fatal message received：${response.data}")
            val error = ApiConnectionException(response.data["message"])
            defaultListener.error(error)

            listeners.values.forEach {
                it.error(error)
            }
            return
        }

        if (response.tag.isBlank()) {
            defaultListener.receive(response)
        }

        // 没有登记的命令，一律由default处理
        listeners.getOrDefault(response.tag, defaultListener).let { listener ->
            when (response.state) {
                State.DONE -> listener.completed(response)
                State.RESULT -> listener.receive(response)
                State.TRAP -> listener.error(ApiCommandException(response.data))
                else -> listener.unknown(response)
            }
        }
    }

    fun handleError(ex: Throwable) {
        ex as ApiConnectionException
        log.error("Connection error occured：${ex.message}")
        defaultListener.error(ex)

        listeners.values.forEach {
            it.error(ex)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResponseHandler::class.java)
    }
}

