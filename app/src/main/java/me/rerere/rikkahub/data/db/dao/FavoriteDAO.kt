package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.FavoriteEntity

@Dao
interface FavoriteDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    fun listAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT substr(ref_key, length('node:' || :conversationId || ':') + 1) FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String>

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
