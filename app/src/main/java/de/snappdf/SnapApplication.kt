package de.snappdf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import de.snappdf.data.DocumentRepository
import de.snappdf.data.SettingsStore
import de.snappdf.processing.DocumentProcessor

class SnapApplication : Application() {
    val repository by lazy { DocumentRepository(this) }
    val settings by lazy { SettingsStore(this) }
    val processor by lazy { DocumentProcessor(this) }
    override fun onCreate() { super.onCreate(); PDFBoxResourceLoader.init(applicationContext) }
}
