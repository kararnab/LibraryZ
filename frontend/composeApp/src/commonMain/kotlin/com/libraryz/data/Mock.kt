package com.libraryz.data

// Mirrors the sample content shown in the design bundle's Browse + WorkDetail
// wireframes. Lets the UI run end-to-end before the backend client is wired.
object MockData {
    val works: List<Work> = listOf(
        work("the-mythical-man-month", "The Mythical Man-Month",
            "Frederick P. Brooks Jr.", 1975, null, listOf("PDF", "EPUB", "MOBI")),
        work("sicp", "Structure and Interpretation of Computer Programs",
            "Harold Abelson, Gerald Jay Sussman", 1996, "978-0262510875",
            listOf("PDF", "EPUB")),
        work("a-pattern-language", "A Pattern Language",
            "Christopher Alexander, Sara Ishikawa, Murray Silverstein",
            1977, null, listOf("PDF")),
        work("geb", "Gödel, Escher, Bach",
            "Douglas Hofstadter", 1979, null, listOf("PDF", "EPUB")),
        work("taocp1", "The Art of Computer Programming, Vol. 1",
            "Donald E. Knuth", 1968, null, listOf("PDF", "EPUB", "MOBI", "DJVU")),
        work("dragon-book", "Compilers: Principles, Techniques, and Tools",
            "Alfred V. Aho, Monica S. Lam, Ravi Sethi, Jeffrey D. Ullman",
            2006, null, listOf("PDF", "EPUB")),
    )

    fun findWork(id: String): Work? = works.firstOrNull { it.id == id }

    private fun work(
        id: String,
        title: String,
        authors: String,
        year: Int?,
        isbn: String?,
        formats: List<String>,
    ): Work {
        val editions = formats.mapIndexed { i, fmt ->
            Edition(
                id = "$id-ed-${i + 1}",
                workId = id,
                format = fmt,
                language = "English",
                sizeBytes = ((i + 1) * 3_500_000L) + 1_500_000L,
                sha256 = "0".repeat(64),
            )
        }
        return Work(
            id = id,
            title = title,
            authors = authors,
            publicationYear = year,
            isbn = isbn,
            language = "English",
            editions = editions,
        )
    }
}
