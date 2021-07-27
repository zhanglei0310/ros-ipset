package cn.foperate.ros.pac

import io.netty.handler.codec.dns.DnsQuestion
import io.netty.handler.codec.dns.DnsRecordType
import io.vertx.core.buffer.Buffer
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import kotlin.streams.toList

object DomainUtil {
    private val log = LoggerFactory.getLogger(DomainUtil::class.java)
    private val blackList = HashSet<String>()
    private val whiteList = HashSet<String>()
    private val adblockList = HashSet<String>()
    val netflixList = HashSet<String>()

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

    fun loadNetflixList(buffer: Buffer) {
        val reader = StringReader(buffer.toString(Charsets.UTF_8))
        val records = reader.readLines()
        netflixList.addAll(records)
        log.info("${records.size} records of netflix list loaded")
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

    fun loadAdblockList(fileName: String) {
        if (fileName.isNotBlank()) try {
            val file = File(fileName)
            log.info("try load adblock list file $fileName")
            loadAdblockList(file)
        } catch (e:java.lang.RuntimeException) {}
    }

    private fun loadAdblockList(file:File) {
        val reader = file.bufferedReader(Charset.defaultCharset())
        val records = reader.lines()
            .filter { it.isNotBlank() }
            .toList()
        adblockList.addAll(records)
        log.info("${records.size} records of adblock list loaded")
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

    fun match(dnsQuestion: DnsQuestion):Boolean {
        if (dnsQuestion.type()== DnsRecordType.A) {
            val name = dnsQuestion.name()
            return match(name)
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

    fun matchBlock(name: String): Boolean {
        val checkName = if (name.endsWith(".")) {
            name.substring(0, name.length-1)
        } else name
        parse(checkName).forEach {
            if (adblockList.contains(it)) {
                return true
            }
        }
        return false
    }
}