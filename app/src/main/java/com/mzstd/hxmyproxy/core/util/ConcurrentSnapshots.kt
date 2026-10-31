package com.mzstd.hxmyproxy.core.util

/** Kotlin toList()/toSet() special-case size 0/1, but a concurrent view's iterator may
 * already differ from that size. In particular, the singleton branch calls next()
 * without hasNext(): a normal concurrent remove can crash shutdown or a relay worker.
 * Java's collection constructor consumes toArray(), whose concurrent-set implementation
 * tolerates growth/shrinkage. This is a weakly consistent copy, not an atomic transaction. */
internal fun <T> concurrentSnapshot(values: Collection<T>): List<T> = ArrayList(values)
