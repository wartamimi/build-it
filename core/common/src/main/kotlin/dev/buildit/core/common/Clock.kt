package dev.buildit.core.common

/** Wall clock abstraction (epoch milliseconds). */
fun interface Clock {
    fun now(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Generates opaque unique identifiers for projects, blocks, layout nodes, etc. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random: IdGenerator = IdGenerator { java.util.UUID.randomUUID().toString() }
    }
}
