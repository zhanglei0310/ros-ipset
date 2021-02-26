package cn.foperate.ros.api

interface ResponseListener {
    /** receive data from router
     * @param result The data received
     */
    fun receive(result: Response)

    /** called if the command associated with this listener experiences an error
     * @param ex Exception encountered
     */
    fun error(ex: MikrotikApiException)

    /** called when the command associated with this listener is done  */
    fun completed(result: Response)

    // just ignore the unknown words
    fun unknown(words: Response) {}
}
