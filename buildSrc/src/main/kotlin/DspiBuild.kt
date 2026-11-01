import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.int(name: String): Int = findVersion(name).get().requiredVersion.toInt()

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Files written by tools/gen_kotlin_contract.py / UniFFI: not linted. */
internal val GENERATED_GLOBS =
    listOf("!**/CoreTypes.kt", "!**/DspiConstants.kt", "!**/DspiCoreApi.kt", "!**/FfiDspiCore.kt", "!**/UnimplementedDspiCore.kt", "!**/build/**")
