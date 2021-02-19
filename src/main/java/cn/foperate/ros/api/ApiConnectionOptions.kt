package cn.foperate.ros.api

data class ApiConnectionOptions(
    val username: String,
    val password: String,
    val host: String,
    val port: Int = RxApiConnection.DEFAULT_PORT,
    val idleTimeout: Int = 30
)

fun apiConnectionOptionsOf(username: String,
                           password: String,
                           host: String)
= ApiConnectionOptions(username, password, host)
