package info.jiayun.intellijmcp.rust

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RustBinaryCompatibilityTest {
    @Test
    fun `adapter bytecode does not reference removed Rust Kotlin file facades`() {
        val resource = "info/jiayun/intellijmcp/rust/RustLanguageAdapter.class"
        val bytes = assertNotNull(javaClass.classLoader.getResourceAsStream(resource)).use { it.readBytes() }
        val classFile = bytes.toString(Charsets.ISO_8859_1)

        assertFalse(
            classFile.contains("org/rust/lang/core/psi/ext/RsQualifiedNamedElementKt"),
            "RustLanguageAdapter must not use the unstable qualifiedName extension"
        )
        assertFalse(
            classFile.contains("org/rust/lang/core/psi/ext/RsAbstractableKt"),
            "RustLanguageAdapter must not use the unstable owner extension"
        )
    }
}
