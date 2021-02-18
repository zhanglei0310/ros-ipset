package cn.foperate.ros.api

import me.legrange.mikrotik.ApiConnection

data class ApiConnectionOptions(
    val username: String,
    val password: String,
    val host: String,
    val port: Int = ApiConnection.DEFAULT_PORT,
    val idleTimeout: Int = 30
)

fun apiConnectionOptionsOf(username: String,
                           password: String,
                           host: String)
= ApiConnectionOptions(username, password, host)
