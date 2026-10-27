package me.rerere.rikkahub.data.skills

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File

class SkillManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /**
     * 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能。
     */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> =
        skillDirsIn(root).mapNotNull { parseSkillDir(it, builtin).getOrNull() }

    // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
    private fun skillDirsIn(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()

    /** 解析技能目录；无法识别时为 [InvalidSkillException]，读文件失败时为 IO 异常 */
    private fun parseSkillDir(skillDir: File, builtin: Boolean): Result<SkillMetadata> = runCatching {
        val skillFile = skillDir.resolve("SKILL.md")
        if (!skillFile.isFile) throw InvalidSkillException(InvalidSkillReason.MissingSkillMd)
        val frontmatter = SkillFrontmatterParser.parse(skillFile.readText())
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingName)
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingDescription)
        SkillMetadata(
            name = name,
            description = description,
            compatibility = frontmatter["compatibility"],
            skillDir = skillDir,
            builtin = builtin,
        )
    }
}

enum class InvalidSkillReason {
    MissingSkillMd,
    MissingName,
    MissingDescription,
}

private class InvalidSkillException(val reason: InvalidSkillReason) : Exception(reason.name)

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val skillDir: File,
    /** 内置技能，来自 assets 解压，只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
