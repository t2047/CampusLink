package com.campuslink.mobile.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.campuslink.mobile.core.security.DatabaseKeyStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class CampusDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context, keyStore: DatabaseKeyStore): CampusDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, CampusDatabase::class.java, "campuslink-chat.db")
                .openHelperFactory(SupportOpenHelperFactory(keyStore.getOrCreate()))
                .build()
        }
    }
}
