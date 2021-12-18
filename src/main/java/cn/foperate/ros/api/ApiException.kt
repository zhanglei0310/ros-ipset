package cn.foperate.ros.api

import java.lang.Exception


/**
 * Thrown by the Mikrotik API to indicate errors
 *
 * @author Aston Mei
 */
open class MikrotikApiException(msg: String? = null, err: Throwable? = null) : Exception(msg, err)


/**
 * Exception thrown if the Api experiences a connection problem
 */
open class ApiConnectionException(msg: String? = null, err: Throwable? = null) : MikrotikApiException(msg, err)

/**
 * Exception thrown if command running timeout
 */
@Deprecated("改用RestAPI实现")
class ApiTimeoutException(message: String):ApiConnectionException(message)

/**
 * Thrown if there is a problem unpacking data from the Api.
 */
@Deprecated("改用RestAPI实现")
class ApiDataException(msg: String? = null, err: Throwable? = null) : MikrotikApiException(msg, err)

@Deprecated("改用RestAPI实现")
class ApiCommandException(map: Map<String, String>) : MikrotikApiException(map["message"]) {
    var category = map["category"]?.toInt() ?: 0
}
