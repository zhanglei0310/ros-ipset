package cn.foperate.ros.api

/**
 * A command sent to a Mikrotik. This internal class is used to build complex commands
 * with parameters, queries and property lists.
 *
 * @property cmd real command part
 * @property params normally used for filed values for objects
 * @property queries used as conditions for the query command
 * @property props selectors for what fields will returned
 *
 * @author Aston Mei
 */
@Deprecated("改用RestAPI实现")
class Command(val cmd: String,
              val params: Map<String, String?> = mapOf(),
              val queries: Map<String, String> = mapOf(),
              val props: List<String> = listOf()
) {
    var tag = ""
    override fun toString(): String {
        return "cmd[$tag] = $cmd, params = $params, queries = $queries"
    }

    fun toSentence():List<String> {
        val list = mutableListOf<String>()
        list.add(cmd)
        params.forEach { (k, v) ->
            list.add("=$k=$v")
        }
        if (tag.isNotBlank()) {
            list.add(".tag=$tag")
        }
        if (props.isNotEmpty()) {
            val propList = props.joinToString(",")
            list.add("=.proplist=$propList")
        }
        queries.forEach { (k, v) ->
            list.add("?$k=$v")
        }
        return list
    }
}
