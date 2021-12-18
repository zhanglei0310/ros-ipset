package cn.foperate.ros.app

import cn.foperate.ros.verticle.RestService
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.mutiny.core.Vertx

object RestClient {
    @JvmStatic
    fun main(args: Array<String>) {
        val vertx = Vertx.vertx()
        RestService.init(vertx, jsonObjectOf())
        RestService.queryAddressList("PROXY")
            .subscribe().with { println(it.toString()) }
    }
}