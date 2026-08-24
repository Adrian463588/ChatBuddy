package com.chatbuddy.di

import android.content.Context
import androidx.work.WorkManager
import com.chatbuddy.data.repository.ModelRepositoryImpl
import com.chatbuddy.ai.embedding.OnDeviceEmbeddingRepository
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.repository.SqliteVecVectorStoreRepository
import com.chatbuddy.domain.repository.ModelRepository
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.repository.VectorStoreRepository
import com.chatbuddy.data.repository.DocumentRepositoryImpl
import com.chatbuddy.domain.repository.DocumentRepository
import com.chatbuddy.data.repository.MlKitTranslationRepository
import com.chatbuddy.data.repository.MlKitOcrRepository
import com.chatbuddy.domain.repository.TranslationRepository
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.data.repository.PersonaRepositoryImpl
import com.chatbuddy.domain.repository.PersonaRepository
import com.chatbuddy.data.repository.AndroidVoiceRepository
import com.chatbuddy.domain.repository.VoiceRepository
import com.chatbuddy.ai.voice.WhisperJniEngine
import com.chatbuddy.ai.voice.WhisperEngine
import com.chatbuddy.data.repository.LocalRagChatRepository
import com.chatbuddy.domain.repository.ChatRepository
import com.chatbuddy.ai.llm.LocalLlmEngine
import com.chatbuddy.ai.llm.LlamaJniEngine
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {
    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository

    @Binds
    @Singleton
    abstract fun bindEmbeddingRepository(impl: OnDeviceEmbeddingRepository): EmbeddingRepository

    @Binds
    @Singleton
    abstract fun bindVectorStoreRepository(impl: SqliteVecVectorStoreRepository): VectorStoreRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindTranslationRepository(impl: MlKitTranslationRepository): TranslationRepository

    @Binds
    @Singleton
    abstract fun bindOcrRepository(impl: MlKitOcrRepository): OcrRepository

    @Binds
    @Singleton
    abstract fun bindPersonaRepository(impl: PersonaRepositoryImpl): PersonaRepository

    @Binds
    @Singleton
    abstract fun bindVoiceRepository(impl: AndroidVoiceRepository): VoiceRepository

    @Binds
    @Singleton
    abstract fun bindWhisperEngine(impl: WhisperJniEngine): WhisperEngine

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: LocalRagChatRepository): ChatRepository

    @Binds
    @Singleton
    abstract fun bindLocalLlmEngine(impl: LlamaJniEngine): LocalLlmEngine
}

@Module
@InstallIn(SingletonComponent::class)
object AppProviders {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "chatbuddy.db").build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): android.content.ContentResolver =
        context.contentResolver
}
