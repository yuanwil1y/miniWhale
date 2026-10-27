package whaleui.nightsky.ltd.reply

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart

/**
 * 把一条 assistant 消息的 `parts` 按「一行一个气泡」切成若干切片。
 *
 * 规则（任务书 1.5）：
 * - 每一行文本单独一个切片；
 * - Reasoning 部件留在最前（开头连续的 Reasoning 原顺序不动）；
 * - Tool 跟在它前面那段文字所在的切片里；前面没有文字就放进后面第一个文本切片，
 *   后面也没有文字就单独成片；
 * - Tool 的 output 里有 Audio / Image 的（`text_to_speech`、`draw`）自成一片，
 *   界面上显示成语音条或图片气泡；
 * - 其余部件（ServerTool、Image、Audio、Document……）跟随前文，没有前文就单独成片；
 * - 不产生纯空白切片（整段只有空白的 Text 部件直接丢掉）。
 *
 * 不变式（有单测守着）：把 [sliceParts] 的结果用 [joinSlices] 拼回来，非文本部件的
 * 序列与原 `parts` 完全一致，文本在「相邻 Text 用 `\n` 连接」的规约下与原 `parts` 一致。
 */
object WhaleMessageSlicer {

    /**
     * 按部件顺序切片。返回的每个切片就是界面上的一个气泡；切片之间保持原顺序。
     */
    fun sliceParts(parts: List<UIMessagePart>): List<List<UIMessagePart>> {
        if (parts.isEmpty()) return emptyList()

        // 开头的连续 Reasoning 一起留在最前，不单独拆气泡
        var index = 0
        val leadingReasoning = mutableListOf<UIMessagePart>()
        while (index < parts.size && parts[index] is UIMessagePart.Reasoning) {
            leadingReasoning.add(parts[index])
            index++
        }

        val slices = mutableListOf<MutableList<UIMessagePart>>()
        // 当前还没定稿的切片；定稿（closeCurrent）后才进 slices
        var current: MutableList<UIMessagePart>? = null
        // 当前切片是不是由一行文本开出来的
        var currentIsText = false
        // 还没找到「前面的文字」的 Tool，等下一个文本切片一起放进去
        val pendingTools = mutableListOf<UIMessagePart>()

        fun closeCurrent() {
            current?.let { if (it.isNotEmpty()) slices.add(it) }
            current = null
            currentIsText = false
        }

        fun startTextSlice(text: String, metadata: JsonObject?) {
            closeCurrent()
            val slice = mutableListOf<UIMessagePart>()
            // Tool 原本排在这行文字前面，放进同一个切片时也放前面
            if (pendingTools.isNotEmpty()) {
                slice.addAll(pendingTools)
                pendingTools.clear()
            }
            slice.add(UIMessagePart.Text(text = text, metadata = metadata))
            current = slice
            currentIsText = true
        }

        while (index < parts.size) {
            val part = parts[index]
            index++
            when {
                part is UIMessagePart.Text -> {
                    val chunks = splitTextIntoLineChunks(part.text)
                    chunks.forEachIndexed { chunkIndex, chunk ->
                        // 拆分出来的多行共用原部件的 metadata，只挂在第一段上
                        startTextSlice(chunk, if (chunkIndex == 0) part.metadata else null)
                    }
                }

                part is UIMessagePart.Tool -> when {
                    // 工具输出里带语音/图片：自成一片
                    isMediaTool(part) -> {
                        closeCurrent()
                        flushPendingTools(slices, pendingTools)
                        slices.add(mutableListOf(part))
                    }
                    // 跟在前面那段文字同片
                    currentIsText -> current!!.add(part)
                    // 前面还没出现文字：等后面第一个文本切片
                    else -> pendingTools.add(part)
                }

                // 中段出现的 Reasoning、ServerTool、图片/语音/文档等：跟随前文
                else -> {
                    if (current == null) {
                        val slice = mutableListOf<UIMessagePart>()
                        if (pendingTools.isNotEmpty()) {
                            slice.addAll(pendingTools)
                            pendingTools.clear()
                        }
                        slice.add(part)
                        current = slice
                    } else {
                        current!!.add(part)
                    }
                }
            }
        }

        closeCurrent()
        flushPendingTools(slices, pendingTools)

        if (leadingReasoning.isNotEmpty()) {
            if (slices.isEmpty()) {
                slices.add(leadingReasoning.toMutableList())
            } else {
                slices.first().addAll(0, leadingReasoning)
            }
        }

        return slices.map { it.toList() }
    }

    /**
     * [sliceParts] 的逆操作：按顺序摊平所有切片，相邻 Text 用 `\n` 连接成一个 Text。
     */
    fun joinSlices(slices: List<List<UIMessagePart>>): List<UIMessagePart> =
        mergeAdjacentTextParts(slices.flatten())

    /**
     * 相邻 Text 部件用 `\n` 连接成一个 Text（切片拼回、相邻 assistant 消息合并共用）。
     *
     * 合并后的 Text 保留前一个部件的 metadata。
     */
    internal fun mergeAdjacentTextParts(parts: List<UIMessagePart>): List<UIMessagePart> {
        val merged = mutableListOf<UIMessagePart>()
        for (part in parts) {
            val last = merged.lastOrNull()
            if (part is UIMessagePart.Text && last is UIMessagePart.Text) {
                merged[merged.lastIndex] = last.copy(text = last.text + "\n" + part.text)
            } else {
                merged.add(part)
            }
        }
        return merged
    }

    /** 工具输出里带语音或图片的（`text_to_speech` / `draw`），要单独成一个气泡 */
    private fun isMediaTool(tool: UIMessagePart.Tool): Boolean =
        tool.output.any { it is UIMessagePart.Audio || it is UIMessagePart.Image }

    private fun flushPendingTools(
        slices: MutableList<MutableList<UIMessagePart>>,
        pendingTools: MutableList<UIMessagePart>
    ) {
        if (pendingTools.isEmpty()) return
        slices.add(pendingTools.toMutableList())
        pendingTools.clear()
    }

    /**
     * 把一个 Text 部件按 `\n` 切成若干「行块」：每个非空行开一个块，行与行之间的空行
     * 挂到下一个非空行前面（结尾的空行挂到最后一个块后面），这样把块用 `\n` 连回去
     * 能还原原文，同时不会产生纯空白块。
     */
    private fun splitTextIntoLineChunks(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val lines = ArrayList<String>()
        var start = 0
        for (index in text.indices) {
            if (text[index] == '\n') {
                lines.add(text.substring(start, index))
                start = index + 1
            }
        }
        lines.add(text.substring(start))

        val chunks = mutableListOf<String>()
        val blankLines = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                blankLines.add(line)
            } else {
                if (blankLines.isEmpty()) {
                    chunks.add(line)
                } else {
                    chunks.add(blankLines.joinToString("\n") + "\n" + line)
                    blankLines.clear()
                }
            }
        }
        if (blankLines.isNotEmpty() && chunks.isNotEmpty()) {
            chunks[chunks.lastIndex] = chunks.last() + "\n" + blankLines.joinToString("\n")
        }
        return chunks
    }
}
