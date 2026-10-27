package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillPaths

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                打开一个技能，读它的说明，或者它目录里的某个文件。
                需要的时候再打开，技能列表在系统提示的最后。
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**技能**")
                    appendLine("下面这些技能你可以用，需要的时候用 use_skill 打开。")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
                        appendLine("    <name>${skill.name.escapeXml()}</name>")
                        appendLine("    <description>${skill.description.take(MAX_SKILL_DESCRIPTION_LENGTH).escapeXml()}</description>")
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "技能名，就是列表里 <name> 的那个")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "技能目录里某个文件的相对路径，可不填；不填就是读这个技能的说明。只用说明里 Markdown 链接写出来的路径，别自己猜。"
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name 不能为空")
                // 模型可能照抄系统提示中转义后的名称，两种形式都接受
                val skill = available.firstOrNull { skill -> skill.name == name || skill.name.escapeXml() == name }
                    ?: error("没有「$name」这个技能，能用的有：${available.joinToString("、") { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "「$name」这个技能的说明找不到了" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("「$path」不在这个技能的目录里")
                    require(target.exists()) { "「$name」技能里没有「$path」这个文件" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
