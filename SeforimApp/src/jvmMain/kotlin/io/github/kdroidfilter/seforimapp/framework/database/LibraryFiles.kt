package io.github.kdroidfilter.seforimapp.framework.database

import java.nio.file.Path

/**
 * The files that make up an installed library, all derived from the books database path. The one
 * place that knows these names, so the health check and the providers cannot disagree.
 */
data class LibraryFiles(
    val database: Path,
    val textIndex: Path,
    val lookupIndex: Path,
    val dictionary: Path,
    val catalog: Path,
)

/** `seforim.db` gives `seforim.db.lucene` and `seforim.db.lookup.lucene`; any other name the `*index` forms. */
fun libraryFilesFor(database: Path): LibraryFiles {
    val name = database.fileName?.toString().orEmpty()
    val isDb = name.endsWith(".db")
    val textIndex = database.resolveSibling(if (isDb) "$name.lucene" else "$name.luceneindex")
    return LibraryFiles(
        database = database,
        textIndex = textIndex,
        lookupIndex = database.resolveSibling(if (isDb) "$name.lookup.lucene" else "$name.lookupindex"),
        dictionary = textIndex.resolveSibling(DICTIONARY_FILE_NAME),
        catalog = database.resolveSibling(CATALOG_FILE_NAME),
    )
}

private const val DICTIONARY_FILE_NAME = "lexical.db"
private const val CATALOG_FILE_NAME = "catalog.pb"
