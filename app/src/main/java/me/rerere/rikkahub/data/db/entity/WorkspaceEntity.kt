package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原 RikkaHub「工作区」表。工作区功能（:workspace 模块与全部业务代码）已在 trim S6 删除。
 *
 * 表仍留在 AppDatabase 里（DAO 在 R2 删了，没有读写）：不改数据库版本、不写迁移，
 * 老数据原样保留，等 R 阶段统一清理不再使用的表时再删。这里的列名、类型、默认值、索引必须和
 * schemas/ 里已导出的版本保持一致，否则 Room 会判定 schema 变化。
 */
@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = "DISABLED",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    @ColumnInfo("type", defaultValue = "LOCAL")
    val type: String = "LOCAL",
    // SSH 工作区的连接配置 (JSON, 凭据为 Keystore 密文), 本地工作区为 null
    @ColumnInfo("ssh_config")
    val sshConfig: String? = null,
)
