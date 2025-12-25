package info.jiayun.intellijmcp.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.APP)
class LanguageAdapterRegistry {

    private val logger = Logger.getInstance(LanguageAdapterRegistry::class.java)

    /**
     * Get all registered language adapters
     */
    fun getAllAdapters(): List<LanguageAdapter> {
        return LanguageAdapter.EP_NAME.extensionList
    }

    /**
     * Get adapter by file
     */
    fun getAdapter(file: VirtualFile): LanguageAdapter? {
        return getAllAdapters().find { it.supports(file) }
    }

    /**
     * Get adapter by extension
     */
    fun getAdapterByExtension(extension: String): LanguageAdapter? {
        val ext = extension.lowercase().removePrefix(".")
        return getAllAdapters().find { ext in it.supportedExtensions }
    }

    /**
     * Get adapter by language ID
     */
    fun getAdapterByLanguage(languageId: String): LanguageAdapter? {
        return getAllAdapters().find { it.languageId == languageId.lowercase() }
    }

    /**
     * Get all supported languages
     */
    fun getSupportedLanguages(): List<String> {
        return getAllAdapters().map { it.languageId }
    }

    /**
     * Get all supported extensions
     */
    fun getSupportedExtensions(): Set<String> {
        return getAllAdapters().flatMap { it.supportedExtensions }.toSet()
    }

    /**
     * Log loaded adapters (for debugging)
     */
    fun logLoadedAdapters() {
        val adapters = getAllAdapters()
        if (adapters.isEmpty()) {
            logger.warn("No language adapters loaded!")
        } else {
            logger.info("Loaded ${adapters.size} language adapter(s): ${adapters.map { it.languageId }}")
        }
    }

    companion object {
        fun getInstance(): LanguageAdapterRegistry =
            ApplicationManager.getApplication().getService(LanguageAdapterRegistry::class.java)
    }
}
