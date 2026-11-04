package com.dpx.captions.core

/**
 * A port of Python's `difflib.SequenceMatcher` without junk heuristics (`autojunk=False`), so the
 * correction learning and the text-edit retiming align words exactly the way the desktop app does.
 */
class SequenceMatcher<T>(private val a: List<T>, private val b: List<T>) {
    data class Block(val a: Int, val b: Int, val size: Int)
    data class Opcode(val tag: Tag, val i1: Int, val i2: Int, val j1: Int, val j2: Int)
    enum class Tag { EQUAL, REPLACE, DELETE, INSERT }

    private val b2j: Map<T, IntArray> = HashMap<T, MutableList<Int>>().also { map ->
        b.forEachIndexed { j, item -> map.getOrPut(item) { mutableListOf() }.add(j) }
    }.mapValues { it.value.toIntArray() }

    private fun longestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Block {
        var bestI = alo
        var bestJ = blo
        var bestSize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            val positions = b2j[a[i]]
            if (positions != null) {
                for (j in positions) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    next[j] = k
                    if (k > bestSize) {
                        bestI = i - k + 1
                        bestJ = j - k + 1
                        bestSize = k
                    }
                }
            }
            j2len = next
        }
        return Block(bestI, bestJ, bestSize)
    }

    val matchingBlocks: List<Block> by lazy {
        val found = mutableListOf<Block>()
        val queue = ArrayDeque<IntArray>().apply { add(intArrayOf(0, a.size, 0, b.size)) }
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val m = longestMatch(alo, ahi, blo, bhi)
            if (m.size > 0) {
                found += m
                if (alo < m.a && blo < m.b) queue.add(intArrayOf(alo, m.a, blo, m.b))
                if (m.a + m.size < ahi && m.b + m.size < bhi) queue.add(intArrayOf(m.a + m.size, ahi, m.b + m.size, bhi))
            }
        }
        found.sortWith(compareBy<Block>({ it.a }, { it.b }))

        val merged = mutableListOf<Block>()
        var i1 = 0
        var j1 = 0
        var k1 = 0
        for (block in found) {
            if (i1 + k1 == block.a && j1 + k1 == block.b) {
                k1 += block.size
            } else {
                if (k1 > 0) merged += Block(i1, j1, k1)
                i1 = block.a
                j1 = block.b
                k1 = block.size
            }
        }
        if (k1 > 0) merged += Block(i1, j1, k1)
        merged += Block(a.size, b.size, 0)
        merged
    }

    val opcodes: List<Opcode> by lazy {
        var i = 0
        var j = 0
        val out = mutableListOf<Opcode>()
        for (block in matchingBlocks) {
            val tag = when {
                i < block.a && j < block.b -> Tag.REPLACE
                i < block.a -> Tag.DELETE
                j < block.b -> Tag.INSERT
                else -> null
            }
            if (tag != null) out += Opcode(tag, i, block.a, j, block.b)
            i = block.a + block.size
            j = block.b + block.size
            if (block.size > 0) out += Opcode(Tag.EQUAL, block.a, i, block.b, j)
        }
        out
    }

    fun matchedCount(): Int = matchingBlocks.sumOf { it.size }

    fun ratio(): Double {
        val total = a.size + b.size
        return if (total == 0) 1.0 else 2.0 * matchedCount() / total
    }

    companion object {
        fun chars(s: String): List<Char> = s.toList()

        /** Similarity of two strings, as Python's `SequenceMatcher(None, s, t).ratio()`. */
        fun ratio(s: String, t: String): Double = SequenceMatcher(chars(s), chars(t)).ratio()

        fun matched(s: String, t: String): Int = SequenceMatcher(chars(s), chars(t)).matchedCount()
    }
}
