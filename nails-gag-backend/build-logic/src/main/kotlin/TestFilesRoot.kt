import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Каталог хранилища файлов для задачи тестов: передаётся тестам свойством `nails-gag.files.root` и очищается перед
 * прогоном. Путь абсолютный, но не вход задачи (`@Internal`): иначе кэш сборки зависел бы от расположения проекта.
 */
abstract class TestFilesRoot : CommandLineArgumentProvider {

    @get:Internal
    abstract val directory: DirectoryProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dnails-gag.files.root=${directory.get().asFile.absolutePath}")

    /** Удаляет каталог со всем содержимым; по символьным ссылкам не переходит, удаляет только сами ссылки. */
    @OptIn(ExperimentalPathApi::class)
    fun clear() {
        directory.get().asFile.toPath().deleteRecursively()
    }
}
