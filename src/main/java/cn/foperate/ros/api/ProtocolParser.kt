package cn.foperate.ros.api

import io.netty.buffer.Unpooled
import io.smallrye.mutiny.subscription.MultiEmitter
import io.vertx.core.Handler
import io.vertx.mutiny.core.buffer.Buffer
import org.slf4j.LoggerFactory
import java.util.function.Consumer


// 从读取的数据协议中获得words并组成sentence的类
class ProtocolParser(val emitter: MultiEmitter<in List<String>>): Handler<Buffer>, Consumer<Buffer> {

    // 借鉴Vertx中的RecordParser的实现来完成协议解析工作
    private var buff: Buffer = EMPTY_BUFFER
    private var event: Buffer = EMPTY_BUFFER
    private var sentence = mutableListOf<String>()

    var output: Handler<List<String>>? = null

    fun handleException(e: Throwable) {
        log.error(e.message, e)
        emitter.fail(ApiConnectionException(e.message, e))
    }

    fun handleEnd() {
        log.debug("API连接收到EOF信息，将沿处理链向下通知")
        emitter.fail(ApiConnectionException("Connection closed."))
    }

    override fun handle(buffer: Buffer) {
        // 首先完成处理前的准备
        buff = if (buff.length()==0) {
            buffer
        } else {
            Buffer.buffer(buff.bytes).appendBuffer(buffer)
        }

        try {
            while (readLen(buff)) {
                // 表示数据已经准备好
                if (event.length()==0) {
                    if (sentence.size==0) {
                        log.debug("忽略空句子")
                    } else {
                        // log.debug("成功读取一个句子")
                        emitter.emit(sentence.toList())
                        sentence = mutableListOf()
                    }
                } else {
                    val word = event.toString()
                    /*if (word.contains(Char.MIN_VALUE)) {
                        emitter.fail(ApiDataException("含有非法数据，推断出错"))
                        continue
                    }*/
                    // log.debug("获得数据 $word")
                    sentence.add(word)
                }
            }
        } catch (e:Exception) {
            // 目前看起来走不到这个地方来
            log.error(e.message, e)
            emitter.fail(ApiDataException(e.message, e))
        }
    }

    /**
     * 作用是试图从当前流中读取一个word。遇到分片可能会多次运算。
     * @param buffer 需要处理的buffer，实际就是当前的buff对象
     * @return 是否正确获取了一个word
     * @throws ApiDataException 如果首字符为保留的控制字符
     **/
    @Throws(ApiDataException::class)
    private fun readLen(buffer: Buffer): Boolean {
        if (buffer.length()==0) return false

        var len = buffer.getUnsignedByte(0).toInt()
        try {
            val head = when {
                len and 0xC0 == 0x80 -> {
                    len = buffer.getUnsignedShort(0) and 0x8000.inv()
                    2
                }
                len and 0xE0 == 0xC0 -> {
                    len = buffer.getUnsignedMedium(0) and 0xC00000.inv()
                    3
                }
                len and 0xF0 == 0xE0 -> {
                    len = buffer.getInt(0) or 0x0FFFFFFF
                    4
                }
                len == 0xF0 -> {
                    len = buffer.getInt(1)
                    5
                }
                // FIXME 该控制字符为保留使用状态，将来有可能合法化
                len >= 0xF8 -> throw ApiDataException("Reversed Control Byte")
                else -> 1
            }
            return when{
                (buffer.length()>len+head) -> {
                    event = buffer.slice(head, len+head)
                    buff = buffer.slice(len+head, buffer.length())
                    true
                }
                (buffer.length()==len+head) ->{
                    event = buffer.slice(head, len+head)
                    buff = EMPTY_BUFFER
                    true
                }
                else ->false
            }
        } catch (ex: Exception) { // 甚至无法完成连接长度数据的提取
            return false
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProtocolParser::class.java)
        private val EMPTY_BUFFER: Buffer = Buffer.buffer(Unpooled.EMPTY_BUFFER)
    }

    override fun accept(buffer: Buffer) = handle(buffer)
}