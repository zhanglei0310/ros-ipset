package cn.foperate.ros.pac

import io.vertx.core.logging.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import kotlin.streams.toList

object DomainUtil {
    private val log = LoggerFactory.getLogger(DomainUtil::class.java)
    private val blackList = HashSet<String>()
    private val whiteList = HashSet<String>()

    /*init {
        blackList.add("google.com")
        blackList.add("apple.com")
        whiteList.add("www.apple.com")
    }*/
    private fun checkFile(path: String): File {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            throw RuntimeException("$path not found")
        }
        return file
    }

    fun loadBlackList(fileName: String) {
        val file = checkFile(fileName)
        loadBlackList(file)
    }

    private fun loadBlackList(file: File) {
        val reader = file.bufferedReader(Charset.defaultCharset())
        val records = reader.lines().filter { it.isNotBlank() }.toList()
        blackList.addAll(records)
        log.info("${records.size} records of black list loaded")
    }

    fun loadWhiteList(fileName: String) {
        try {
            val file = checkFile(fileName)
            loadWhiteList(file)
        } catch (e:RuntimeException) {}
    }

    private fun loadWhiteList(file: File) {
        val reader = file.bufferedReader(Charset.defaultCharset())
        val records = reader.lines().filter { it.isNotBlank() }.toList()
        blackList.addAll(records)
        log.info("${records.size} records of black list loaded")
    }

    fun match(name: String):Boolean {
        val checking = parse(name)
        checking.forEach {
            if (whiteList.contains(it)) {
                return false
            }
        }
        checking.forEach {
            if (blackList.contains(it)) {
                return true
            }
        }
        return false
    }

    fun parse(domain: String):List<String> {
        val result = mutableListOf(domain)
        var index = 0
        do {
            index = domain.indexOf(".", startIndex = index+1)
            if (index>0) {
                result.add(domain.substring(index+1))
            }
        } while (index>0)
        return result.toList()
    }
}