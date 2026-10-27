package whaleui.nightsky.ltd.tools

/**
 * 工具出错时交给她的话（提示词第 10 批，方案 A）。
 *
 * 这些文字是给**她**看的，不是给设置页的人看的：说清楚实情，用她的视角；
 * 配置类问题（没 Key、Key 不对、余额不足）告诉她「这要对方去设置里处理」，她会自然地提一句；
 * 超时 / 网络这类临时问题只说「等会儿再试」。不写「请充值后再试」「请检查请求参数」这类口气。
 *
 * @param blocked 用不了时的开头，比如「语音暂时发不了」
 */
class WhaleToolErrors(private val blocked: String, private val temporaryTail: String = "等会儿再试") {

    /** 没有配置 [service] 的 Key。 */
    fun noKey(service: String): String = "$blocked：还没设置 $service 的 Key，$SETTINGS_HINT"

    /** [service] 的 Key 无效 / 鉴权失败。 */
    fun badKey(service: String): String = "$blocked：$service 的 Key 不对，$SETTINGS_HINT"

    /** 余额不足。 */
    fun noBalance(): String = "$blocked：服务那边余额不足，$SETTINGS_HINT"

    /** 超时、网络、限流、服务端出错等临时问题。 */
    fun temporary(): String = "$blocked，$temporaryTail"

    companion object {
        const val SETTINGS_HINT: String = "这要对方去设置里处理"

        val Voice = WhaleToolErrors("语音暂时发不了", "等会儿再试，先打字")
        val Draw = WhaleToolErrors("画画暂时用不了")
        val Quota = WhaleToolErrors("额度暂时查不了")
        val Search = WhaleToolErrors("暂时搜不了")
        val Webpage = WhaleToolErrors("网页暂时打不开")
    }
}
