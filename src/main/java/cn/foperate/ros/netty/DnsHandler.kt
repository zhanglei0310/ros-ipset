package cn.foperate.ros.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.dns.*
import java.io.UnsupportedEncodingException


class DnsHandler : SimpleChannelInboundHandler<DatagramDnsQuery>() {
    @Throws(UnsupportedEncodingException::class)
    public override fun channelRead0(ctx: ChannelHandlerContext, query: DatagramDnsQuery) {
        // 假数据，域名和ip的对应关系应该放到数据库中
        val ipMap: MutableMap<String, ByteArray> = HashMap()
        ipMap["www.baidu.com."] = byteArrayOf(61, 135.toByte(), 169.toByte(), 125)
        val response = DatagramDnsResponse(query.recipient(), query.sender(), query.id())
        try {
            val dnsQuestion = query.recordAt<DefaultDnsQuestion>(DnsSection.QUESTION)
            response.addRecord(DnsSection.QUESTION, dnsQuestion)
            println("查询的域名：" + dnsQuestion.name())
            var buf: ByteBuf? = null
            if (ipMap.containsKey(dnsQuestion.name())) {
                buf = Unpooled.wrappedBuffer(ipMap[dnsQuestion.name()])
            } else {
                // TODO  对于没有的域名采用迭代方式
                // buf = Unpooled.wrappedBuffer(new byte[] { 127, 0, 0, 1});
            }
            // TTL设置为10s, 如果短时间内多次请求，客户端会使用本地缓存
            val queryAnswer = DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, 10, buf)
            response.addRecord(DnsSection.ANSWER, queryAnswer)
        } catch (e: Exception) {
            println("异常了：$e")
        } finally {
            ctx.writeAndFlush(response)
        }
    }

    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
    }
}