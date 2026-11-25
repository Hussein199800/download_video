package com.tadaffuq.app.di

import android.content.Context
import com.tadaffuq.app.download.Conditions
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.engine.HttpClients
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun db(@ApplicationContext c: Context): AppDatabase = AppDatabase.build(c)
    @Provides @Singleton fun settings(@ApplicationContext c: Context) = SettingsRepository(c)
    @Provides @Singleton fun client(): OkHttpClient = HttpClients.create()
    @Provides @Singleton fun conditions(@ApplicationContext c: Context) = Conditions(c)
}
