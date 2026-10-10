package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorsTest {
    private val dragon = "Alfred V. Aho; Monica S. Lam; Ravi Sethi; Jeffrey D. Ullman"
    private val sicp = "Harold Abelson; Gerald Jay Sussman"
    private val brooks = "Frederick P. Brooks Jr."

    @Test
    fun splitsOnSemicolonsAndDropsEmpties() {
        assertEquals(listOf("A", "B C"), splitAuthors(" A ;; B C ; "))
        assertEquals(emptyList(), splitAuthors(null))
        assertEquals("A; B", joinAuthors(listOf("A", "B")))
    }

    @Test
    fun surnames() {
        assertEquals("Brooks", surname("Frederick P. Brooks Jr."))
        assertEquals("Brooks", surname("Frederick P. Brooks, Jr."))
        assertEquals("Brooks", surname("Brooks, Frederick P."))
        assertEquals("Ullman", surname("Jeffrey D. Ullman"))
        assertEquals("Homer", surname("Homer"))
        assertEquals("King", surname("Martin Luther King III"))
    }

    @Test
    fun listRowsUseUpToThreeSurnames() {
        assertEquals("Frederick P. Brooks Jr.", authorsShort(brooks))
        assertEquals("Abelson & Sussman", authorsShort(sicp))
        assertEquals("Aho, Sethi & Ullman", authorsShort("Alfred V. Aho; Ravi Sethi; Jeffrey D. Ullman"))
        assertEquals("Aho et al.", authorsShort(dragon))
        assertEquals("", authorsShort(" "))
    }

    @Test
    fun detailNamesEveryoneUpToSix() {
        assertEquals("Alfred V. Aho, Monica S. Lam, Ravi Sethi and Jeffrey D. Ullman", authorsFull(dragon))
        assertEquals("Harold Abelson and Gerald Jay Sussman", authorsFull(sicp))
        assertEquals("A, B, C, D, E and 2 more", authorsFull("A; B; C; D; E; F; G"))
        assertEquals("A, B, C, D, E and F", authorsFull("A; B; C; D; E; F"))
    }

    @Test
    fun covers() {
        assertEquals("Aho +3", coverAuthorsM(dragon))
        assertEquals("Brooks", coverAuthorsM(brooks))
        assertEquals("Aho +3", coverAuthorsL(dragon))
        assertEquals("Brooks", coverAuthorsL(brooks)) // 23 characters: too long in full
        assertEquals("George Eliot", coverAuthorsL("George Eliot"))
        assertEquals("Aho, Lam, Sethi & Ullman", coverAuthorsXL(dragon))
        assertEquals("A et al.", coverAuthorsXL("A; B; C; D; E"))
    }
}

class AuthorEntryTest {
    private val two = AuthorEntry(listOf("Alfred V. Aho", "Ravi Sethi"), "")

    @Test
    fun enterOrSemicolonMakesAChip() {
        assertEquals(listOf("Alfred V. Aho", "Ravi Sethi", "Lam"), commitAuthor(two.copy(draft = " Lam ")).names)
        val typed = typeAuthors(two.copy(draft = "Lam"), "Lam;")
        assertEquals(AuthorEntry(listOf("Alfred V. Aho", "Ravi Sethi", "Lam"), ""), typed)
    }

    @Test
    fun aDuplicateIsNotAddedAndPointsAtTheChip() {
        val dup = commitAuthor(two.copy(draft = "ravi sethi"))
        assertEquals(two.names, dup.names)
        assertEquals(1, dup.duplicate)
        assertEquals("ravi sethi", dup.draft)
        assertEquals(null, typeAuthors(dup, "ravi seth").duplicate)
    }

    @Test
    fun pastingSeveralNamesMakesSeveralChips() {
        val pasted = typeAuthors(AuthorEntry(emptyList(), ""), "Harold Abelson; Gerald Jay Sussman; Julie Sussman")
        assertEquals(listOf("Harold Abelson", "Gerald Jay Sussman", "Julie Sussman"), pasted.names)
        assertEquals("", pasted.draft)
        assertEquals(listOf("Alfred V. Aho", "Ravi Sethi", "Lam"), typeAuthors(two, "Ravi Sethi; Lam").names)
    }

    @Test
    fun commasStayInsideAName() {
        assertEquals(listOf("Brooks, Frederick P."), commitAuthor(AuthorEntry(emptyList(), "Brooks, Frederick P.")).names)
    }

    @Test
    fun theStoredValueKeepsAnUnfinishedName() {
        assertEquals("Alfred V. Aho; Ravi Sethi; Ull", two.copy(draft = "Ull ").value)
        assertEquals("Alfred V. Aho; Ravi Sethi", two.value)
    }
}
