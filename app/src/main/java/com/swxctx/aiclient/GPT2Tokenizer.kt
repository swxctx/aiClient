package com.swxctx.aiclient

/**
 * @Author swxctx
 * @Date 2024-04-11
 * @Describe: 文字解码编码
 */
class GPT2Tokenizer(
    // 单词映射到Token ID
    private val encoder: Map<String, Int>,
    // 将Token ID映射回单词
    private val decoder: Map<Int, String>,
    // 存储Byte Pair Encoding (BPE) 合并操作的排名 BPE算法
    private val bpeRanks: Map<Pair<String, String>, Int>
) {
    // 定义一个正则表达式来匹配文本中可能的token，包括缩写、字母、数字和其他字符
    private val encodeRegex =
        Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

    /**
     * 解码方法：将一系列token ID转换回文本字符串
     */
    fun decode(tokens: List<Int>): String {
        // 首先使用decoder将每个token ID转换回对应的字符串
        val text = tokens.joinToString("") { decoder.getOrDefault(it, "") }
        // 对转换后的字符串进行进一步处理，可能涉及到字符编码的转换
        val utfCodepoints = text.map { byteDecoder[it.toString()]!! }
        return String(utfCodepoints.toIntArray(), 0, utfCodepoints.size)
    }

    /**
     * 编码方法：将输入的文本字符串转换为一系列token ID
     */
    fun encode(text: String): MutableList<Int> {
        // 使用encodeRegex正则表达式匹配文本中的所有可能toke
        val tokens = encodeRegex.findAll(text).map { result ->
            // 对于每个匹配的结果，获取它的Unicode code points，并通过byteEncoder转换
            result.value.codePoints()
                .boxed()
                .map { byteEncoder[it]!! }
                .toArray()
                .joinToString("")
        }

        // 对每个token使用BPE方法进行进一步的编码，然后将编码后的token转换为它们对应的ID
        return tokens
            .map { bpe(it) }
            .flatten()
            .map { encoder[it]!! }
            .toMutableList()
    }

    /**
     * BPE算法：根据BPE规则对单个token进行拆分或合并
     */
    private fun bpe(token: String): List<String> {
        if (token.length <= 1) return listOf(token)
        var word = token.map { it.toString() }
        var pairs = getPairs(word)

        while (true) {
            // 如果当前单词对中没有任何一个在bpeRanks中，则终止循环
            if (!pairs.any { bpeRanks.containsKey(it) }) break

            // 找到排名最高（优先合并）的一对字符
            val (first, second) = pairs.minBy { bpeRanks.getOrDefault(it, Int.MAX_VALUE) } ?: break

            var i = 0
            val newWord = mutableListOf<String>()
            while (i < word.size) {
                // 对word进行遍历，将需要合并的字符对合并
                val j = word.withIndex().indexOfFirst { it.index >= i && it.value == first }

                // 根据BPE规则更新word
                if (j != -1) {
                    newWord.addAll(word.subList(i, j))
                    i = j
                } else {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }

                if (word[i] == first && i < word.size - 1 && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }

            // 更新字符对，准备下一轮合并
            word = newWord
            if (word.size == 1) {
                break
            } else {
                pairs = getPairs(word)
            }
        }
        return word
    }

    /**
     * 获取所有相邻字符对
     */
    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        return mutableSetOf<Pair<String, String>>().apply {
            for (i in 0 until word.size - 1) {
                add(word[i] to word[i + 1])
            }
        }
    }
}