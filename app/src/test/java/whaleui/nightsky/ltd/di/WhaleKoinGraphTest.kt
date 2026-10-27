package whaleui.nightsky.ltd.di

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.core.module.Module
import org.koin.core.module.flatten
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.URI
import java.util.jar.JarFile

/**
 * Koin 依赖图校验（W-034，F1 安全网）。
 *
 * ## 要拦住什么
 * round3 忘了注册 `WhaleHistoryLimiter`，App 一启动就崩（`NoDefinitionFoundException`）。
 * 这一类错误的本质是：**代码里某处向 Koin 要类型 X，但启动时加载的模块里没有 X 的定义**。
 *
 * ## 怎么做（不靠手写清单）
 * Koin 的 `get<X>()` / `inject<X>()` / `koinInject<X>()` / `koinViewModel<X>()` 都是 reified inline 函数，
 * 编译后在调用处留下固定的字节码：`ldc X.class` → `Reflection.getOrCreateKotlinClass` →
 * 紧接着调用一个 `org/koin/...` 里参数带 `KClass` 的方法。类型是推断出来的（`koin.get()`、
 * 构造参数里的裸 `get()`）也一样会被 reify 成常量。所以本测试：
 *
 * 1. 从 `RikkaHubApp` 的字节码里读出 `startKoin { modules(...) }` 实际加载的模块（不手写模块清单）；
 * 2. 汇总这些模块里所有定义提供的类型（primary + secondary type，与 Koin 运行时按类型精确匹配一致）；
 * 3. 扫描 app 主代码编译出的**全部** class，找出每一处向 Koin 要类型的地方——
 *    包括模块定义 lambda 里的 `get()`（依赖）、界面里的 `koinViewModel()` / `koinInject()`、
 *    `RikkaHubApp` 里的 `koin.get()`、`by inject()`、`lazy { get() }`、Receiver / Worker 的 `KoinComponent.get()`；
 * 4. 每个被要的类型都必须有定义；否则失败并指出是哪个类的哪个方法要的。
 *
 * 能拦住：漏注册（任何地方要了、但模块里删了/忘了加），依赖没注册（给某个 bean 的构造
 * 加了参数并在定义里 `get()`，而那个类型没注册——无论 bean 是按具体类还是按接口注册的），
 * 可空/带默认值的构造参数用 `get()` 注入（Koin 的 `verify()` 对这种参数只打警告，
 * 但运行时 `get()` 照样抛异常，round3 的 `whaleHistory` 正是这种）。
 *
 * 拦不住（见 [KNOWN_LIMITS]）：用非 reified 的 `KClass` 变量去取；定义 lambda 本身运行时抛异常
 * ；限定符（qualifier）不区分——目前全图没有用 qualifier。
 * 这些交给 `WhaleStartupSmokeTest`（真机/模拟器冷启动）。
 *
 * ## 为什么不用 koin-test 的 `verify()`
 * 读了 koin-test 4.2.2 `Verification.verifyConstructor` 的实现：它只看定义的 **primaryType 的公开构造函数**，
 * - `single<ChatRepository> { RikkaChatRepository(...) }` 这种按接口注册的，接口没有构造函数，什么都不查；
 * - 参数 `isOptional`（带默认值）时缺定义只往 stderr 打一行警告，不失败——
 *   `ChatRequestFactory(whaleHistory: WhaleHistoryLimiter? = null)` 删掉注册它照样通过；
 * - 没有任何地方用构造参数依赖 `ChatViewModel` / `ChatBundleImporter`，它们是界面直接取的「根」，
 *   `verify()` 根本不知道有人要它们。
 * 另外，旧注释说「EmojiData 用 by inject() 注入 List<T> 导致 verify 失败」并不准确：`verify()` 不看字段，
 * 失败的真实原因是它去检查 `EmojiData(version, categories: List<EmojiCategory>)` 这个数据类的构造参数
 * （4.2.2 会把 `List<T>` 拆成元素类型 `EmojiCategory` 去找定义），而定义 lambda 实际调用的是
 * `EmojiUtils.loadEmoji(get())`，根本不走那个构造函数。本测试只看 lambda 真正要了什么，所以没有这个问题。
 */
@OptIn(org.koin.core.annotation.KoinInternalApi::class)
class WhaleKoinGraphTest {

    /**
     * 不由模块定义、但运行时确实能解析到的类型。每一项都要写清楚来源；**不要为了让测试通过往这里加东西**。
     */
    private val externallyProvided = mapOf(
        // RikkaHubApp: startKoin { androidContext(this@RikkaHubApp) } 会注册 Context 与 Application
        "android.content.Context" to "androidContext()",
        "android.app.Application" to "androidContext()",
        // viewModel { } / viewModelOf(::X) 定义里要 SavedStateHandle 时，koin-core-viewmodel 从 CreationExtras 现造一个
        "androidx.lifecycle.SavedStateHandle" to "koin viewModel CreationExtras",
    )

    // ------------------------------------------------------------------------------------------

    @Test
    fun `every type requested from Koin anywhere in the app has a definition`() {
        val graph = KoinGraph.load()
        val missing = graph.lookups
            .filter { it.type !in graph.provided && it.type !in externallyProvided }
            .groupBy { it.type }
        if (missing.isNotEmpty()) {
            val report = missing.entries.joinToString("\n") { (type, sites) ->
                "  - $type\n" + sites.map { "      要它的地方：${it.site}  (${it.api})" }.distinct().joinToString("\n")
            }
            fail(
                "以下类型有代码向 Koin 要，但 RikkaHubApp 加载的模块（${graph.moduleNames}）里没有定义——" +
                    "装到手机上会 NoDefinitionFoundException：\n$report"
            )
        }
    }

    @Test
    fun `scanner sanity - it actually sees the app's Koin usage`() {
        // 扫描器自检：防止 AGP 换了 class 输出位置 / Kotlin 换了字节码形状后，扫描静悄悄地什么都没找到。
        val graph = KoinGraph.load()
        assertTrue("没找到 RikkaHubApp 加载的模块", graph.moduleNames.isNotEmpty())
        assertTrue(
            "RikkaHubApp 没有加载 whaleModule：${graph.moduleNames}",
            graph.moduleNames.any { it.endsWith("WhaleModuleKt.getWhaleModule") },
        )
        assertTrue("扫描到的 class 太少：${graph.scannedClasses}", graph.scannedClasses > 1000)
        assertTrue("扫描到的 Koin 取值点太少：${graph.lookups.size}", graph.lookups.size > 200)
        val fromWhaleModule = graph.lookups.count { it.site.startsWith("whaleui.nightsky.ltd.di.WhaleModuleKt") }
        assertTrue("whaleModule 定义里的 get() 没被扫到：$fromWhaleModule", fromWhaleModule >= 40)
        assertTrue(
            "没扫到任何 koinViewModel() 取值点（界面取 ViewModel 的路径失效）",
            graph.lookups.any { it.api.contains("resolveViewModel") && it.site.startsWith("whaleui.") },
        )
        assertTrue(
            "没扫到 Receiver / Worker 里的 KoinComponent.get()（闹钟链路取调度器的路径失效）",
            graph.lookups.any { it.site.startsWith("whaleui.nightsky.ltd.alarm.WhaleAlarmReceiver") } &&
                graph.lookups.any { it.site.startsWith("whaleui.nightsky.ltd.alarm.WhaleAlarmWorker") },
        )
        assertTrue(
            "没扫到 RikkaHubApp 里的 koin.get()",
            graph.lookups.any { it.site.startsWith("me.rerere.rikkahub.RikkaHubApp") },
        )
        val whaleDefs = graph.providedBy["whaleui.nightsky.ltd.di.WhaleModuleKt.getWhaleModule"].orEmpty()
        assertTrue("whaleModule 定义太少：${whaleDefs.size}", whaleDefs.size >= 40)
        // 明细落到 build 目录，方便排查「为什么没扫到 / 为什么报缺」
        runCatching {
            File("build/koin-graph-lookups.txt").writeText(
                graph.lookups.sortedBy { it.type }.joinToString("\n") { "${it.type}\t${it.site}\t${it.api}" }
            )
        }
        println(
            "[WhaleKoinGraphTest] modules=${graph.moduleNames.size} provided=${graph.provided.size} " +
                "classes=${graph.scannedClasses} lookups=${graph.lookups.size} whaleModuleLookups=$fromWhaleModule"
        )
    }

    // ------------------------------------------------------------------------------------------

    private data class Lookup(val type: String, val site: String, val api: String)

    private class KoinGraph(
        val moduleNames: List<String>,
        val provided: Set<String>,
        val providedBy: Map<String, Set<String>>,
        val lookups: List<Lookup>,
        val scannedClasses: Int,
    ) {
        companion object {
            private var cached: KoinGraph? = null

            fun load(): KoinGraph = cached ?: run {
                val classes = mainClassFiles()
                val lookups = mutableListOf<Lookup>()
                val moduleGetters = linkedSetOf<String>()
                classes.forEach { (name, bytes) ->
                    ClassScan.scan(bytes, lookups, if (name.startsWith("me/rerere/rikkahub/RikkaHubApp")) moduleGetters else null)
                }
                val providedBy = moduleGetters.associateWith { getter ->
                    val owner = getter.substringBeforeLast('.')
                    val method = getter.substringAfterLast('.')
                    val module = Class.forName(owner).getMethod(method).invoke(null) as Module
                    flatten(listOf(module)).flatMap { m ->
                        m.mappings.values.flatMap { f ->
                            listOf(f.beanDefinition.primaryType) + f.beanDefinition.secondaryTypes
                        }
                    }.map { it.java.name }.toSet()
                }
                KoinGraph(
                    moduleNames = moduleGetters.toList(),
                    provided = providedBy.values.flatten().toSet(),
                    providedBy = providedBy,
                    lookups = lookups,
                    scannedClasses = classes.size,
                ).also { cached = it }
            }

            /** app 主代码的编译产物（目录或 jar），定位方式：找 WhaleModuleKt.class 在 classpath 上的位置。 */
            private fun mainClassFiles(): List<Pair<String, ByteArray>> {
                val anchor = "whaleui/nightsky/ltd/di/WhaleModuleKt.class"
                val url = WhaleKoinGraphTest::class.java.classLoader.getResource(anchor)
                    ?: error("classpath 上找不到 $anchor")
                val wanted = { n: String -> n.endsWith(".class") && (n.startsWith("whaleui/") || n.startsWith("me/rerere/")) }
                return when (url.protocol) {
                    "file" -> {
                        val file = File(url.toURI())
                        val root = File(file.path.removeSuffix(anchor.replace('/', File.separatorChar)))
                        root.walkTopDown().filter { it.isFile }.map {
                            it.relativeTo(root).invariantSeparatorsPath to it
                        }.filter { wanted(it.first) }.map { it.first to it.second.readBytes() }.toList()
                    }
                    "jar" -> {
                        val jarPath = url.path.substringBefore("!/")
                        JarFile(File(URI(jarPath))).use { jar ->
                            jar.entries().asSequence().filter { wanted(it.name) }
                                .map { it.name to jar.getInputStream(it).readBytes() }.toList()
                        }
                    }
                    else -> error("不认识的 class 位置：$url")
                }
            }
        }
    }

    /**
     * 极简 class 文件扫描（不引第三方库）：解析常量池，逐条走方法字节码，找
     * `ldc <Class>` + `invokestatic kotlin/jvm/internal/Reflection.getOrCreateKotlinClass`，
     * 再往后最多 [WINDOW] 条指令内第一个「owner 在 org/koin/、描述符里带 KClass」的调用，即为一次 Koin 取值。
     */
    private object ClassScan {
        private const val WINDOW = 40
        private const val KCLASS = "Lkotlin/reflect/KClass;"

        /** 这些 Koin 调用带 KClass 但不是向容器要实例：声明定义、给定义绑类型、从 parametersOf 里取参数、拼日志用的类名。 */
        private val notALookup = listOf(
            "org/koin/ext/KClassExtKt",
            "org/koin/core/definition/",
            "org/koin/dsl/DefinitionBindingKt",
            "org/koin/core/module/dsl/OptionDSLKt",
            "org/koin/core/parameter/ParametersHolder",
        )

        fun scan(bytes: ByteArray, sink: MutableList<Lookup>, moduleGetters: MutableSet<String>?) {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            check(inp.readInt() == 0xCAFEBABE.toInt())
            inp.readUnsignedShort(); inp.readUnsignedShort()
            val count = inp.readUnsignedShort()
            val tag = IntArray(count)
            val utf = arrayOfNulls<String>(count)
            val a = IntArray(count)
            val b = IntArray(count)
            var i = 1
            while (i < count) {
                val t = inp.readUnsignedByte()
                tag[i] = t
                when (t) {
                    1 -> utf[i] = inp.readUTF()
                    3, 4 -> inp.readInt()
                    5, 6 -> { inp.readLong(); i++ }
                    7, 8, 16, 19, 20 -> a[i] = inp.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> { a[i] = inp.readUnsignedShort(); b[i] = inp.readUnsignedShort() }
                    15 -> { inp.readUnsignedByte(); a[i] = inp.readUnsignedShort() }
                    else -> error("未知常量池 tag $t")
                }
                i++
            }
            fun cls(idx: Int) = utf[a[idx]]!!
            fun owner(ref: Int) = cls(a[ref])
            fun name(ref: Int) = utf[a[b[ref]]]!!
            fun desc(ref: Int) = utf[b[b[ref]]]!!

            inp.readUnsignedShort()
            val thisName = cls(inp.readUnsignedShort()).replace('/', '.')
            inp.readUnsignedShort()
            repeat(inp.readUnsignedShort()) { inp.readUnsignedShort() }
            fun skipAttributes() = repeat(inp.readUnsignedShort()) {
                inp.readUnsignedShort(); inp.readFully(ByteArray(inp.readInt()))
            }
            repeat(inp.readUnsignedShort()) { repeat(3) { inp.readUnsignedShort() }; skipAttributes() }
            repeat(inp.readUnsignedShort()) {
                inp.readUnsignedShort()
                val methodName = utf[inp.readUnsignedShort()]!!
                inp.readUnsignedShort()
                repeat(inp.readUnsignedShort()) {
                    val attrName = utf[inp.readUnsignedShort()]
                    val len = inp.readInt()
                    val data = ByteArray(len).also { inp.readFully(it) }
                    if (attrName != "Code") return@repeat
                    val codeLen = ((data[4].toInt() and 0xff) shl 24) or ((data[5].toInt() and 0xff) shl 16) or
                        ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
                    val code = data.copyOfRange(8, 8 + codeLen)
                    val ops = decode(code)
                    val site = "$thisName.$methodName"
                    ops.forEachIndexed { k, (op, ref) ->
                        if (moduleGetters != null && op == 0xb8 && desc(ref) == "()Lorg/koin/core/module/Module;") {
                            moduleGetters += owner(ref).replace('/', '.') + "." + name(ref)
                        }
                        if ((op == 0x12 || op == 0x13) && tag[ref] == 7 && k + 1 < ops.size) {
                            val (nextOp, nextRef) = ops[k + 1]
                            if (nextOp != 0xb8 || owner(nextRef) != "kotlin/jvm/internal/Reflection" ||
                                name(nextRef) != "getOrCreateKotlinClass"
                            ) return@forEachIndexed
                            val type = cls(ref)
                            if (type.startsWith("[")) return@forEachIndexed
                            for (j in k + 2 until minOf(ops.size, k + 2 + WINDOW)) {
                                val (o, r) = ops[j]
                                if ((o == 0x12 || o == 0x13) && tag[r] == 7) break
                                if (o !in 0xb6..0xb9) continue
                                val ow = owner(r)
                                if (!ow.startsWith("org/koin/") || !desc(r).contains(KCLASS)) continue
                                if (notALookup.none { ow.startsWith(it) }) {
                                    sink += Lookup(type.replace('/', '.'), site, "${ow.substringAfterLast('/')}.${name(r)}")
                                }
                                break
                            }
                        }
                    }
                }
            }
        }

        /** 把字节码切成 (opcode, 常量池下标或 -1) 序列。 */
        private fun decode(code: ByteArray): List<Pair<Int, Int>> {
            fun u1(p: Int) = code[p].toInt() and 0xff
            fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)
            fun s4(p: Int) = (u1(p) shl 24) or (u1(p + 1) shl 16) or (u1(p + 2) shl 8) or u1(p + 3)
            val out = ArrayList<Pair<Int, Int>>()
            var pc = 0
            while (pc < code.size) {
                val op = u1(pc)
                val ref = when (op) {
                    0x12 -> u1(pc + 1)
                    0x13, 0x14, in 0xb2..0xb9, 0xbb, 0xbd, 0xc0, 0xc1 -> u2(pc + 1)
                    else -> -1
                }
                out += op to ref
                pc += when (op) {
                    0x10, 0x12, in 0x15..0x19, in 0x36..0x3a, 0xa9, 0xbc -> 2
                    0x11, 0x13, 0x14, 0x84, in 0x99..0xa8, in 0xb2..0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3
                    0xc5 -> 4
                    0xb9, 0xba, 0xc8, 0xc9 -> 5
                    0xc4 -> if (u1(pc + 1) == 0x84) 6 else 4
                    0xaa -> {
                        val start = (pc + 4) and 3.inv()
                        start + 12 + (s4(start + 8) - s4(start + 4) + 1) * 4 - pc
                    }
                    0xab -> {
                        val start = (pc + 4) and 3.inv()
                        start + 8 + s4(start + 4) * 8 - pc
                    }
                    else -> 1
                }
            }
            return out
        }
    }

    private companion object {
        /** 已知局限，写在这里方便以后查。 */
        @Suppress("unused")
        const val KNOWN_LIMITS = """
            1. 只认 reified 取值（get<T>/inject<T>/koinInject<T>/koinViewModel<T>/viewModelOf/new）；
               用 KClass 变量或 Java Class 去取的看不到。
            2. 只检查「有没有定义」，不检查定义 lambda 运行时会不会抛异常。
            3. 不区分 qualifier（目前全图没用 qualifier）。
            4. 死代码里的取值也会被要求有定义（偏保守）。
        """
    }
}
