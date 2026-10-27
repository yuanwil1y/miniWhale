package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.skills.SkillManager
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get())
    }
}
