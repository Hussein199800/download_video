package com.tadaffuq.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun status(s: TaskStatus): String = s.name
    @TypeConverter fun status(s: String): TaskStatus = TaskStatus.valueOf(s)
    @TypeConverter fun net(n: NetKind): String = n.name
    @TypeConverter fun net(n: String): NetKind = NetKind.valueOf(n)
}

@Database(
    entities = [TaskEntity::class, SegmentEntity::class, UsageEntity::class, PackageEntity::class, SavedLinkEntity::class, BookmarkEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun usage(): UsageDao
    abstract fun links(): LinkDao
    abstract fun browser(): BrowserDao

    companion object {
        const val NAME = "tadaffuq.db"

        // لا fallbackToDestructiveMigration أبداً: سجل المستخدم لا يُحذف مع التحديثات.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
