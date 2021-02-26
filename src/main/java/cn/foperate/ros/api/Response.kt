package cn.foperate.ros.api

import org.slf4j.LoggerFactory

enum class State {
    NONE,
    RESULT,
    DONE,
    TRAP,
    HALT
}

class Response(val state:State) {
    var tag:String = ""
    val data = mutableMapOf<String, String>()

    companion object {
        private val log = LoggerFactory.getLogger(Response::class.java)
        fun fromList(list: List<String>):Response {
            val result = when (list.first()) {
                "!done" -> Response(State.DONE)
                "!re" -> Response(State.RESULT)
                "!trap" -> Response(State.TRAP)
                "!fatal" -> Response(State.HALT)
                else -> {
                    log.error("what is wrong")
                    Response(State.NONE)
                }
            }

            list.stream().skip(1)
                .forEach { word ->
                    // 由于发现一个句子中可能混入了多条记录，这里改变为重新组装
                    // 暂时假定只会出现Result结果的混装
                    if (word=="!re") {
                        log.info("出现重复的头: $list")
                        return@forEach
                    }
                    if (word.startsWith(".tag=")) {
                        result.tag = word.substring(5)
                    } else {
                        val kv = word.split('=', limit = 3)
                        if (kv.size<3) {
                            log.error(word)
                        } else {
                            result.data[kv[1]] = kv[2]
                        }
                    }
                }

            return result
        }
    }
}
