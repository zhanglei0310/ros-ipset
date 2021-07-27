package cn.foperate.ros.api

import io.smallrye.mutiny.subscription.MultiEmitter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/****
 * Responser的主动式实现，基于Mutiny的响应式流接口
 * @author Aston Mei
 */
class ResponseEmitter(defaultListener: ResponseListener): ResponseHandler(defaultListener), MultiEmitter<List<String>> {

    // 作为发射器的实现（主动实现）
    override fun emit(list: List<String>): MultiEmitter<List<String>> {
        //log.debug(list.toString())
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
        log.debug("有附加的停止任务需要处理")
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
        return terminated.get()
    }

    override fun requested(): Long {
        return listeners.size.toLong()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResponseEmitter::class.java)
    }
}

