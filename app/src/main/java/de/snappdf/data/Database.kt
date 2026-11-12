package de.snappdf.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents", indices = [Index("createdAt"), Index("folderId")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val pageCount: Int,
    val pdfPath: String,
    val thumbnailPath: String,
    val folderId: String? = null,
    val byteSize: Long = 0,
    val ocrText: String = ""
)

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(@PrimaryKey val id: String, val name: String)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Insert suspend fun insertDocument(document: DocumentEntity)
    @Query("UPDATE documents SET name = :name WHERE id = :id")
    suspend fun renameDocument(id: String, name: String)
    @Query("UPDATE documents SET folderId = :folderId WHERE id = :id")
    suspend fun moveDocument(id: String, folderId: String?)
    @Insert suspend fun insertFolder(folder: FolderEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :id)")
    suspend fun folderExists(id: String): Boolean
    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

@Database(entities = [DocumentEntity::class, FolderEntity::class], version = 1, exportSchema = true)
abstract class SnapDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
}
