package whaleui.nightsky.ltd.reply

// 回复管线：把模型吐出来的原始文本清理成「一行一条」的聊天文本。
//
// 三条正则与处理顺序逐字照抄 Hermes 的 persona_guard/__init__.py
//（EMOJI / ACTION_LINE / HELP_LINE / on_transform_llm_output），不要随手改。
// Python 原文如下（这里只能用行注释：正则里的「\s*/」含 */ 序列，会提前关掉块注释）：
//
//   EMOJI = re.compile(
//       "[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F000-\U0001F2FF\U0000FE0F\U0000200D]+"
//   )
//   ACTION_LINE = re.compile(r"^\s*[（(][^（）()]{1,12}[）)]\s*$")
//   HELP_LINE = re.compile(r"/help|输入\s*/\w+|斜杠命令|有什么(我)?可以帮(你|您)|希望(这|以上)?对你有(所)?帮助")
//
// 纯函数，不依赖 Android，任何一步都不抛异常。
object WhaleReplyPipeline {

    /** 静默标记，模型只回这个（或用它开头）就代表这轮不说话 */
    const val SILENT_MARKER: String = "[SILENT]"

    /**
     * 逐字照抄 Hermes `EMOJI`：
     * `[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F000-\U0001F2FF\U0000FE0F\U0000200D]+`
     *
     * Java/Kotlin 正则没有 `\u{...}` 语法，增补平面的码点用等价的 `\x{...}` 写。
     */
    val EmojiRegex: Regex = Regex(
        "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F2FF}\\x{FE0F}\\x{200D}]+"
    )

    /**
     * 逐字照抄 Hermes `ACTION_LINE`：`^\s*[（(][^（）()]{1,12}[）)]\s*$`
     *
     * 整行只有一个括号里的动作或状态（「（困）」「(揉眼睛)」）才删，行内夹带的不删。
     */
    val ActionLineRegex: Regex = Regex("^\\s*[（(][^（）()]{1,12}[）)]\\s*$")

    // 逐字照抄 Hermes HELP_LINE：
    // /help|输入\s*/\w+|斜杠命令|有什么(我)?可以帮(你|您)|希望(这|以上)?对你有(所)?帮助
    // 只要行内出现就整行删掉（引导用户用斜杠命令的客套话）。
    // 注意：只能用行注释，因为正则里的「\s*/」含 */ 序列，会提前关掉上面的块注释。
    val HelpLineRegex: Regex = Regex(
        "/help|输入\\s*/\\w+|斜杠命令|有什么(我)?可以帮(你|您)|希望(这|以上)?对你有(所)?帮助"
    )

    /** 全角斜杠「／」当换行用（Hermes 的 `re.sub(r"\s*／\s*", "\n", text)`） */
    private val SlashToNewlineRegex: Regex = Regex("\\s*／\\s*")

    /** 压空行（Hermes 的 `re.sub(r"\n{3,}", "\n\n", text)`） */
    private val BlankLineRunRegex: Regex = Regex("\\n{3,}")

    /**
     * 这轮是不是「不说话」。
     *
     * 判据与 Hermes 一致：trim 后以 `[SILENT]` 开头，或者某一行 trim 后恰好是 `[SILENT]`
     * 且这一行位于首行或末行（中间行里的 `[SILENT]` 不算静默）。
     */
    fun isSilent(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith(SILENT_MARKER)) return true
        val lines = splitLines(trimmed)
        return lines.firstOrNull()?.trim() == SILENT_MARKER ||
            lines.lastOrNull()?.trim() == SILENT_MARKER
    }

    /**
     * 清理文本（不拆条）。
     *
     * 顺序与 Hermes `on_transform_llm_output` 完全一致：
     * 1. `\s*／\s*` → 换行；
     * 2. 删 emoji；
     * 3. 逐行删掉匹配 [HelpLineRegex] 或 [ActionLineRegex] 的行；
     * 4. 压空行（`\n{3,}` → `\n\n`）；
     * 5. 去掉空行（每行 trim 后丢弃空行，再用 `\n` 连起来）；
     * 6. 整体 strip。
     *
     * 注意：静默判定在调用方（[split]）先做，这里不做 `[SILENT]` 判定。
     */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""

        var text = SlashToNewlineRegex.replace(raw, "\n")
        text = EmojiRegex.replace(text, "")

        text = splitLines(text)
            .filter { line -> !HelpLineRegex.containsMatchIn(line) && !ActionLineRegex.matches(line) }
            .joinToString("\n")

        text = BlankLineRunRegex.replace(text, "\n\n")

        text = splitLines(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        return text.trim()
    }

    /**
     * 流式显示用的清理：与 [clean] 同一套步骤，只是不分条（返回清理后的整段文本）。
     *
     * 流式期间每来一小段就调一次，界面上就不会先闪出「（困）」或 `/help` 引导再消失。
     *
     * 另外会藏掉「最后一行还没写完」的情况：清理后的最后一行以 `(`、`（` 或 `[SILENT`
     * 开头（含 `[`、`[SILENT` 这种只追出一半的前缀）时，把这一行连同它前面的换行一起
     * 去掉，返回剩下的完整部分——那多半是正在流式输出、还没闭合的括号动作或静默标记。
     */
    fun cleanForDisplay(raw: String): String {
        val cleaned = clean(raw)
        if (cleaned.isEmpty()) return cleaned
        val lastLine = cleaned.substring(cleaned.lastIndexOf('\n') + 1)
        if (!isUnfinishedTrailingLine(lastLine)) return cleaned
        val withoutLastLine = cleaned.substring(0, cleaned.length - lastLine.length)
        return withoutLastLine.removeSuffix("\n")
    }

    /**
     * 最后一行是不是「还在写」的那一行：括号动作没闭合，或者只写出了半截 `[SILENT]`。
     *
     * 已经写完整的括号动作行会被 [ActionLineRegex] 在 [clean] 里整行删掉，走不到这里。
     */
    private fun isUnfinishedTrailingLine(line: String): Boolean {
        if (line.isEmpty()) return false
        if (line.startsWith("(") || line.startsWith("（")) return true
        // 流式输出可能只写了一半的 [SILENT]，此时它还是标记的前缀
        if (line.startsWith(SILENT_MARKER)) return true
        return line.length < SILENT_MARKER.length && SILENT_MARKER.startsWith(line)
    }

    /**
     * 切成一条一条的回复：静默返回空列表；否则 [clean] 之后按换行拆、每行 trim、丢掉空行。
     */
    fun split(raw: String): List<String> {
        if (isSilent(raw)) return emptyList()
        return splitLines(clean(raw))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 自己按 `\n` 切行，保证「用 `\n` 连回去能还原原字符串」——
     * 不依赖 `String.lines()` 对结尾换行的处理。
     */
    private fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (index in text.indices) {
            if (text[index] == '\n') {
                lines.add(text.substring(start, index))
                start = index + 1
            }
        }
        lines.add(text.substring(start))
        return lines
    }
}
