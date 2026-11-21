package orilumn.reader.ui.reader

/**
 * 修订卡片的**纠错词典**（用户层：只给读者挑词，不进排版、不进渲染）。
 *
 * 收的是拼音输入法最常见的同音 / 近音错词：读者把「地」打成「的」、把「再」打成「在」这类。
 * 每组是**一个同音族**：选中族里任何一个词，候选就是同族的其余词（顺序按本文件写的顺序，
 * 例如「的」的候选先「地」后「得」）。
 *
 * 只做**精确整词匹配**：选区多一个字、少一个字、带标点都不算命中——猜错上下文会把对的字改坏，
 * 那是比不改更糟的结果。所以这里不做模糊匹配、不做上下文判断，也不猜「的/地/得」该用哪个。
 *
 * 词典是纯数据 + 纯函数，可单测；增删词只改 [groups]。
 */
internal object CorrectionDictionary {

    /**
     * 同音 / 近音词组。**组内第一个是该组最常被写错的那个**（候选顺序跟着组内顺序走）。
     *
     * 单字组（拼音输入法里最常混的一组）：的得地、在再、做作、象像、那哪、以已、即既、
     * 帐账、查察、须需、带戴、座坐。
     * 双字组：整词同音，选中的是错的那个，候选给对的。
     */
    private val groups: List<List<String>> = listOf(
        listOf("的", "地", "得"),
        listOf("在", "再"),
        listOf("做", "作"),
        listOf("象", "像"),
        listOf("那", "哪"),
        listOf("以", "已"),
        listOf("即", "既"),
        listOf("帐", "账"),
        listOf("查", "察"),
        listOf("须", "需"),
        listOf("带", "戴"),
        listOf("座", "坐"),
        listOf("其它", "其他"),
        listOf("必需", "必须"),
        listOf("权利", "权力"),
        listOf("反映", "反应"),
        listOf("制定", "制订"),
        listOf("截止", "截至"),
        listOf("度过", "渡过"),
        listOf("按耐", "按捺"),
        listOf("报到", "报道"),
        listOf("以至", "以致"),
        listOf("分辨", "分辩"),
        listOf("交代", "交待"),
        listOf("化妆", "化装"),
    )

    /** 词 → 同族其余词（不含它自己）。一次建表，查词 O(1)。 */
    private val index: Map<String, List<String>> = buildMap {
        for (group in groups) {
            for (word in group) put(word, group.filter { it != word })
        }
    }

    /**
     * 选区文字对应的正确候选词；不是词典里的词就返回空表（**不猜**）。
     *
     * 调用方保证传入的是选区原文（不做 trim / 去标点）：多一个标点就当成没命中。
     */
    fun candidatesFor(word: String): List<String> =
        if (word.isEmpty()) emptyList() else index[word].orEmpty()
}
