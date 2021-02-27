package cn.foperate.ros.api

import io.smallrye.mutiny.subscription.MultiEmitter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

// 从sentence中读取结果的类，计划使用状态机来实现
// 同时提供被动和主动两个实现
class Responser(val defaultListener: ResponseListener): Consumer<List<String>>, MultiEmitter<List<String>> {

    private val listeners = mutableMapOf<String, ResponseListener>()

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

    // 作为发射器的实现（主动实现）
    override fun emit(list: List<String>): MultiEmitter<List<String>> {
        accept(list)
        return this
    }

    override fun fail(ex: Throwable) {
        handleError(ex)
        fireTermination()
    }

    override fun complete() {
        val ex = ApiConnectionException("意料之外的流完成")

        // 通知所有的监听器已经完成工作
        defaultListener.error(ex)
        listeners.values.forEach {
            it.error(ex)
        }

        // 如果有附加任务
        fireTermination()
    }

    private fun fireTermination() {
        if (terminated.compareAndSet(false, true)) {
            val runnable = onTermination.getAndSet(null)
            runnable?.run()
        }
    }

    private val onTermination: AtomicReference<Runnable> = AtomicReference()
    override fun onTermination(task: Runnable): MultiEmitter<List<String>> {
        onTermination.set(task)
        return this
    }

    private val terminated = AtomicBoolean()
    override fun isCancelled(): Boolean {
        return this.terminated.get()
    }

    override fun requested(): Long {
        return this.listeners.size.toLong()
    }

    companion object {
        private val log = LoggerFactory.getLogger(Responser::class.java)
    }
}

