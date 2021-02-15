package cn.foperate.ros.pac

import org.slf4j.LoggerFactory
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

    fun loadBlackList(file: File) {
        val reader = file.bufferedReader(Charset.defaultCharset())
        val records = reader.lines().filter { it.isNotBlank() }.toList()
        blackList.addAll(records)
        log.info("${records.size} records of black list loaded")
    }

    fun loadWhiteList(fileName: String) {
        if (fileName.isNotBlank()) try {
            val file = File(fileName)
            log.info("try load white list file $fileName")
            loadWhiteList(file)
        } catch (e:RuntimeException) {}
    }

    private fun loadWhiteList(file: File) {
        val reader = file.bufferedReader(Charset.defaultCharset())
        val records = reader.lines()
            .filter { it.isNotBlank() }
            .map { it.substring(5) } .toList()
        whiteList.addAll(records)
        log.info("${records.size} records of black list loaded")
    }

    fun match(name: String):Boolean {
        val checkName = if (name.endsWith(".")) {
            name.substring(0, name.length-1)
        } else name
        if (whiteList.contains(checkName)) {
            return false
        }
        parse(checkName).forEach {
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

    private val regex = Regex("^((?<hour>\\d+)h)?((?<min>\\d+)m)?((?<sec>\\d+)s)?$")

    fun getTimeout(timeoutValue:String):Int {
        return regex.matchEntire(timeoutValue)?.let {
            val group = it.groups
            val hour = group["hour"]?.value?.toInt() ?: 0
            val min = group["min"]?.value?.toInt() ?: 0
            val sec = group["sec"]?.value?.toInt() ?: 0
            hour*3600 + min*60 + sec
        } ?: 24*3600
    }
}