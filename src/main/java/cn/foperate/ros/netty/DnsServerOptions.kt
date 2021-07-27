package cn.foperate.ros.netty

import io.vertx.codegen.annotations.DataObject
import io.vertx.core.datagram.DatagramSocketOptions
import io.vertx.core.dns.DnsClientOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonObjectOf

/**
 * Configuration options for Vert.x DNS client.
 *
 * @author [Julien Viet](mailto:julien@julienviet.com)
 */
@DataObject(generateConverter = true)
class DnsServerOptions(): DatagramSocketOptions() {
    /**
     * Get the port to be used by this client in requests.
     *
     * @return  the port
     */
    var port = DEFAULT_PORT
        private set

    /**
     * Get the host name to be used by this client in requests.
     *
     * @return  the host name
     */
    var host = DEFAULT_HOST
        private set

    /**
     * @return the query timeout in milliseconds
     */
    var queryTimeout = DEFAULT_QUERY_TIMEOUT
        private set

    /**
     * Return whether or not recursion is desired
     *
     * @return `true` when recursion is desired
     */
    var isRecursionDesired = DEFAULT_RECURSION_DESIRED
        private set

    constructor(json: JsonObject):this() {
        port = json.getInteger("port", DEFAULT_PORT)
        host = json.getString("host", DEFAULT_HOST)
        queryTimeout = json.getLong("queryTimeout", DEFAULT_QUERY_TIMEOUT)
        logActivity = json.getBoolean("logActivity", DEFAULT_LOG_ENABLED)
        isRecursionDesired = json.getBoolean("recursionDesired", DEFAULT_RECURSION_DESIRED)
    }

    constructor(other: DnsServerOptions):this() {
        port = other.port
        host = other.host
        queryTimeout = other.queryTimeout
        logActivity = other.logActivity
        isRecursionDesired = other.isRecursionDesired
    }

    /**
     * Set the port to be used by this client in requests.
     *
     * @return a reference to this, so the API can be used fluently
     */
    fun setPort(port: Int): DnsServerOptions {
        require(!(port < 1 && port != DEFAULT_PORT)) { "DNS client port $port must be > 0 or equal to DEFAULT_PORT" }
        this.port = port
        return this
    }

    /**
     * Set the host name to be used by this client in requests.
     *
     * @return a reference to this, so the API can be used fluently
     */
    fun setHost(host: String): DnsServerOptions {
        this.host = host
        return this
    }

    /**
     * Set the query timeout in milliseconds, i.e the amount of time after a query is considered to be failed.
     *
     * @param queryTimeout the query timeout in milliseconds
     * @return a reference to this, so the API can be used fluently
     */
    fun setQueryTimeout(queryTimeout: Long): DnsServerOptions {
        require(queryTimeout >= 1) { "queryTimeout must be > 0" }
        this.queryTimeout = queryTimeout
        return this
    }

    /**
     * Set to true to enabled network activity logging: Netty's pipeline is configured for logging on Netty's logger.
     *
     * @param logActivity true for logging the network activity
     * @return a reference to this, so the API can be used fluently
     */
    override fun setLogActivity(logActivity: Boolean): DnsServerOptions {
        this.logActivity = logActivity
        return this
    }

    /**
     * Set whether or not recursion is desired
     *
     * @param recursionDesired the new value
     * @return a reference to this, so the API can be used fluently
     */
    fun setRecursionDesired(recursionDesired: Boolean): DnsServerOptions {
        isRecursionDesired = recursionDesired
        return this
    }

    override fun toJson() = jsonObjectOf(
        "port" to port,
        "host" to host,
        "queryTimeout" to queryTimeout,
        "logActivity" to logActivity,
        "isRecursionDesired" to isRecursionDesired
    )

    companion object {
        /**
         * The default value for the port = `-1` (configured by [VertxOptions.getAddressResolverOptions])
         */
        const val DEFAULT_PORT = 53

        /**
         * The default value for the host = `null` (configured by [VertxOptions.getAddressResolverOptions])
         */
        const val DEFAULT_HOST: String = "127.0.0.1"

        /**
         * The default value for the query timeout in millis = `5000`
         */
        const val DEFAULT_QUERY_TIMEOUT: Long = 5000

        /**
         * The default log enabled = false
         */
        const val DEFAULT_LOG_ENABLED = false

        /**
         * The default value for the recursion desired flag (RD) = `true`
         */
        const val DEFAULT_RECURSION_DESIRED = true
    }
}

fun dnsServerOptionsOf(
    host: String? = null,
    logActivity: Boolean? = null,
    port: Int? = null,
    queryTimeout: Long? = null,
    recursionDesired: Boolean? = null): DnsServerOptions = DnsServerOptions().apply {

    if (host != null) {
        this.setHost(host)
    }
    if (logActivity != null) {
        this.setLogActivity(logActivity)
    }
    if (port != null) {
        this.setPort(port)
    }
    if (queryTimeout != null) {
        this.setQueryTimeout(queryTimeout)
    }
    if (recursionDesired != null) {
        this.setRecursionDesired(recursionDesired)
    }
}
