package dev.chagok.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isSelf: Boolean = false,
    val createdAt: Long,
    val seed: Boolean = false,
)

@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startKm: Float,
    val endKm: Float,
    val startedAt: Long,
    val endedAt: Long,
    val observed: Boolean,
    val personId: Long?,
    val attribution: String,
    val placeName: String?,
    val rearDoor: String?,
    val returnLeg: Boolean,
    val seed: Boolean = false,
)

@Entity(tableName = "waits")
data class WaitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val placeName: String?,
    /** The trip that followed the wait; the wait is for whoever that trip was for. */
    val segmentId: Long?,
    val seed: Boolean = false,
)

@Entity(tableName = "tanks")
data class TankEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startKm: Float,
    val endKm: Float,
    val startedAt: Long,
    val endedAt: Long,
    val seed: Boolean = false,
)

/** A date the family chose, e.g. the first day of school, that "그날" counts from. */
@Entity(tableName = "days")
data class DayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val title: String,
    val fromMillis: Long,
    val seed: Boolean = false,
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM people ORDER BY isSelf DESC, createdAt")
    fun people(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people ORDER BY isSelf DESC, createdAt")
    suspend fun peopleOnce(): List<PersonEntity>

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Query("UPDATE people SET name = :name WHERE id = :id")
    suspend fun renamePerson(id: Long, name: String)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: Long)

    /** Deleting someone removes the name and where they went, but keeps the distance true. */
    @Query("UPDATE segments SET personId = NULL, attribution = 'UNCLASSIFIED', placeName = NULL WHERE personId = :id")
    suspend fun unclassifyPerson(id: Long)

    @Query("UPDATE waits SET placeName = NULL WHERE segmentId IN (SELECT id FROM segments WHERE personId = :id)")
    suspend fun clearWaitPlacesOf(id: Long)

    @Query("DELETE FROM days WHERE personId = :id")
    suspend fun deleteDaysOfPerson(id: Long)

    @Query("SELECT * FROM segments ORDER BY startKm")
    fun segments(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments ORDER BY startKm")
    suspend fun segmentsOnce(): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun segment(id: Long): SegmentEntity?

    @Insert
    suspend fun insertSegment(segment: SegmentEntity): Long

    @Update
    suspend fun updateSegment(segment: SegmentEntity)

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteSegment(id: Long)

    // Column updates only: a full-row update could overwrite a concurrent change to another column.

    @Query("UPDATE segments SET personId = :personId, attribution = 'CONFIRMED' WHERE id = :id")
    suspend fun confirmSegment(id: Long, personId: Long)

    @Query("UPDATE segments SET personId = NULL, attribution = 'UNCLASSIFIED' WHERE id = :id")
    suspend fun unclassifySegment(id: Long)

    @Query("UPDATE segments SET rearDoor = :door WHERE id = :id")
    suspend fun setRearDoor(id: Long, door: String)

    @Query("UPDATE segments SET personId = NULL, attribution = 'OTHER_DRIVER' WHERE id = :id")
    suspend fun markOtherDriver(id: Long)

    /** Puts a stretch back as it was before an answer the driver took back, its distance included. */
    @Query("UPDATE segments SET personId = :personId, attribution = :attribution, placeName = :placeName, returnLeg = :returnLeg, startKm = :startKm, endKm = :endKm WHERE id = :id")
    suspend fun restoreSegment(id: Long, personId: Long?, attribution: String, placeName: String?, returnLeg: Boolean, startKm: Float, endKm: Float)

    /** A proposal may replace nothing or another proposal, never what the driver said. */
    @Query("UPDATE segments SET personId = :personId, attribution = 'SUGGESTED' WHERE id = :id AND attribution NOT IN ('CONFIRMED', 'OTHER_DRIVER')")
    suspend fun suggestPerson(id: Long, personId: Long)

    @Query("UPDATE segments SET personId = :personId, attribution = 'SUGGESTED', returnLeg = 1, placeName = :place WHERE id = :id AND attribution = 'UNCLASSIFIED'")
    suspend fun bindWayBack(id: Long, personId: Long, place: String?)

    @Query("UPDATE segments SET endKm = :endKm WHERE id = :id AND endKm < :endKm")
    suspend fun extendSegment(id: Long, endKm: Float)

    @Query("UPDATE segments SET attribution = 'CONFIRMED' WHERE attribution = 'SUGGESTED' AND endKm > :startKm AND startKm < :endKm")
    suspend fun confirmSuggestionsBetween(startKm: Float, endKm: Float)

    @Query("UPDATE segments SET startKm = startKm - :km, endKm = endKm - :km")
    suspend fun shiftSegments(km: Float)

    @Query("UPDATE tanks SET startKm = startKm - :km, endKm = endKm - :km")
    suspend fun shiftTanks(km: Float)

    @Query("SELECT MAX(endKm) FROM segments")
    suspend fun maxEndKm(): Float?

    @Query("SELECT id FROM days WHERE personId = :personId")
    suspend fun dayIdsOf(personId: Long): List<Long>

    @Query("SELECT * FROM waits ORDER BY startedAt")
    fun waits(): Flow<List<WaitEntity>>

    @Query("SELECT * FROM waits ORDER BY startedAt")
    suspend fun waitsOnce(): List<WaitEntity>

    @Insert
    suspend fun insertWait(wait: WaitEntity): Long

    @Query("SELECT * FROM tanks ORDER BY endKm DESC")
    fun tanks(): Flow<List<TankEntity>>

    @Insert
    suspend fun insertTank(tank: TankEntity): Long

    @Query("SELECT * FROM days ORDER BY fromMillis")
    fun days(): Flow<List<DayEntity>>

    @Insert
    suspend fun insertDay(day: DayEntity): Long

    @Query("DELETE FROM days WHERE id = :id")
    suspend fun deleteDay(id: Long)

    @Query("DELETE FROM segments")
    suspend fun clearSegments()

    @Query("DELETE FROM waits")
    suspend fun clearWaits()

    @Query("DELETE FROM tanks")
    suspend fun clearTanks()

    @Query("DELETE FROM days")
    suspend fun clearDays()

    @Query("DELETE FROM people WHERE isSelf = 0")
    suspend fun clearFamily()
}

@Database(
    entities = [PersonEntity::class, SegmentEntity::class, WaitEntity::class, TankEntity::class, DayEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        fun create(context: Context): LedgerDatabase =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "ledger.db")
                // Pre-release: no installed base to migrate yet.
                .fallbackToDestructiveMigration()
                .build()
    }
}
