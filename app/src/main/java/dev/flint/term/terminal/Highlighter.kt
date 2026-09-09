package dev.flint.term.terminal

/**
 * Syntax coloring for the built-in editor: a line of text in, the ranges that
 * deserve a color out.
 *
 * It is deliberately a line-at-a-time regex tokenizer rather than a parser.
 * What is being edited is somebody's `nginx.conf` over a phone keyboard, so the
 * job is to make strings, comments and keywords stand apart at a glance — not
 * to be right about every corner of thirteen grammars. Working per line is also
 * what makes the editor usable: a keystroke only invalidates the line it
 * landed on, and the screen only pays for the lines it draws.
 *
 * The price of that choice is state that spans lines: a C block comment or a
 * Python docstring is only colored when it opens and closes on one line. That is
 * the trade, and it is the right way round — a wrong color for a few lines
 * costs nothing, a stalled text field costs the file.
 *
 * Nothing here touches Android, so it can be tested as plain Kotlin. The map
 * from [Token] to an actual color lives with the editor screen, where the theme is.
 */
object Highlighter {

    /** What a range of characters is, as far as color is concerned. */
    enum class Token { KEYWORD, TYPE, STRING, COMMENT, NUMBER, KEY }

    /** A colored range within the single line it was found on. */
    data class Span(val start: Int, val end: Int, val token: Token)

    /**
     * One tokenizer step: a pattern tried at the current position.
     *
     * A null [token] means "consume these characters, color nothing" — the way
     * an identifier is swallowed whole so that `notify` never colors the `not`
     * inside it. [group] names the capture to color when the pattern has to
     * match more than it wants to paint, and [atStart] limits a rule to column
     * zero, which is where a YAML key or a Dockerfile instruction has to be.
     */
    internal class Rule(
        pattern: String,
        val token: Token?,
        val atStart: Boolean = false,
        val group: Int = 0,
    ) {
        val regex = Regex(pattern)
    }

    /** A file type: how to color it, and what its Tab key owes the file. */
    class Language internal constructor(
        val id: String,
        internal val rules: List<Rule>,
        /** Makefiles and Go want a real tab; everything else here reads better with spaces. */
        val usesTabs: Boolean = false,
    )

    /**
     * The colored ranges of [line], in the order they appear.
     *
     * [line] is one line without its terminator; the caller adds the line's
     * offset to place the spans in the document.
     */
    fun spans(line: String, language: Language): List<Span> {
        if (line.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var i = 0
        while (i < line.length) {
            var consumed = 0
            for (rule in language.rules) {
                if (rule.atStart && i != 0) continue
                val match = rule.regex.matchAt(line, i) ?: continue
                if (match.value.isEmpty()) continue
                consumed = match.value.length
                val range = if (rule.group == 0) match.range else match.groups[rule.group]?.range ?: match.range
                if (rule.token != null && !range.isEmpty()) out.add(Span(range.first, range.last + 1, rule.token))
                break
            }
            i += if (consumed > 0) consumed else 1
        }
        return out
    }

    /**
     * The language for a file, or null when nothing here knows it.
     *
     * [firstLine] is only read for names that carry no clue of their own, which
     * on a server is most of them: `deploy`, `backup`, `healthcheck` are all
     * shebang lines away from being shell scripts.
     */
    fun languageFor(name: String, firstLine: String = ""): Language? {
        val base = name.substringAfterLast('/').lowercase()
        BY_NAME[base]?.let { return it }
        if (base.startsWith("dockerfile") || base.endsWith(".dockerfile")) return DOCKERFILE
        if (base.startsWith("makefile") || base.endsWith(".mk")) return MAKEFILE
        if (base.contains("nginx")) return NGINX
        // ".bashrc" has no extension; it is a name with a dot in front of it.
        val stem = base.removePrefix(".")
        BY_NAME[stem]?.let { return it }
        val ext = base.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext != base) BY_EXT[ext]?.let { return it }
        return fromShebang(firstLine)
    }

    /** What the editor's Tab key inserts in this file. */
    fun indentFor(name: String, firstLine: String = ""): String =
        if (languageFor(name, firstLine)?.usesTabs == true) "\t" else "  "

    private fun fromShebang(firstLine: String): Language? {
        if (!firstLine.startsWith("#!")) return null
        val line = firstLine.lowercase()
        return when {
            line.contains("python") -> PYTHON
            line.contains("node") || line.contains("deno") -> JS
            line.contains("bash") || line.contains("zsh") || line.contains("ksh") || line.contains("/sh") || line.endsWith(" sh") -> SH
            else -> null
        }
    }

    // ---- shared pieces ------------------------------------------------------

    /** `"…"` with backslash escapes; an unterminated one still colors to end of line. */
    private const val DQ = "\"(?:\\\\.|[^\"\\\\])*\"?"
    private const val SQ = "'(?:\\\\.|[^'\\\\])*'?"

    /** Shell and YAML single quotes take no escapes at all. */
    private const val SQ_LITERAL = "'[^']*'?"
    private const val BACKTICK = "`[^`]*`?"
    private const val NUM = "(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)"
    private const val WORD = "[A-Za-z_][A-Za-z0-9_]*"
    private const val LINE_COMMENT_HASH = "#[^\\n]*"
    private const val LINE_COMMENT_SLASH = "//[^\\n]*"
    private const val BLOCK_COMMENT = "/\\*.*?\\*/"

    /** Whole words only, so `information` keeps its `in`. */
    private fun words(vararg w: String) = w.joinToString("|", "(?:", ")(?![A-Za-z0-9_])")

    private fun plainWord() = Rule(WORD, null)
    private fun number() = Rule(NUM, Token.NUMBER)

    // ---- languages ----------------------------------------------------------

    private val SH = Language(
        "sh",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            Rule("\\$\\{[^}]*\\}?|\\$[A-Za-z_][A-Za-z0-9_]*|\\$[0-9?@#*!$-]", Token.KEY),
            Rule(
                words(
                    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
                    "in", "function", "select", "return", "local", "export", "readonly", "declare", "typeset",
                    "alias", "unset", "shift", "trap", "eval", "exec", "set", "continue", "break", "time",
                ),
                Token.KEYWORD,
            ),
            Rule(words("echo", "printf", "read", "cd", "exit", "pwd", "source", "test", "command"), Token.TYPE),
            number(),
            plainWord(),
        ),
    )

    private val YAML = Language(
        "yaml",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            Rule("(?:---|\\.\\.\\.)$", Token.KEYWORD, atStart = true),
            Rule("[ \\t]*(?:-[ \\t]+)*([\\w.\\-/]+)(?=[ \\t]*:(?:[ \\t]|$))", Token.KEY, atStart = true, group = 1),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            Rule("[&*][\\w.\\-]+", Token.TYPE),
            Rule(words("true", "false", "null", "yes", "no", "on", "off"), Token.KEYWORD),
            number(),
            plainWord(),
        ),
    )

    private val JSON = Language(
        "json",
        listOf(
            Rule(LINE_COMMENT_SLASH, Token.COMMENT),
            Rule("$DQ(?=[ \\t]*:)", Token.KEY),
            Rule(DQ, Token.STRING),
            Rule(words("true", "false", "null"), Token.KEYWORD),
            Rule("-?$NUM", Token.NUMBER),
            plainWord(),
        ),
    )

    private val TOML = Language(
        "toml",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            Rule("[ \\t]*\\[\\[?[^\\]]*\\]\\]?", Token.TYPE, atStart = true),
            Rule("[ \\t]*([\\w.\\-\"']+)(?=[ \\t]*=)", Token.KEY, atStart = true, group = 1),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            Rule(words("true", "false"), Token.KEYWORD),
            number(),
            plainWord(),
        ),
    )

    private val INI = Language(
        "ini",
        listOf(
            Rule("[#;][^\\n]*", Token.COMMENT),
            Rule("[ \\t]*\\[[^\\]]*\\]", Token.TYPE, atStart = true),
            Rule("[ \\t]*([^=:\\s\\[\\]#;][^=:]*?)(?=[ \\t]*[=:])", Token.KEY, atStart = true, group = 1),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            Rule(words("true", "false", "yes", "no"), Token.KEYWORD),
            number(),
            plainWord(),
        ),
    )

    private val PYTHON = Language(
        "py",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            Rule("\"\"\".*?\"\"\"|'''.*?'''", Token.STRING),
            Rule("[rRbBfFuU]{0,2}(?:$DQ|$SQ)", Token.STRING),
            Rule("@[\\w.]+", Token.TYPE),
            Rule(
                words(
                    "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or",
                    "is", "None", "True", "False", "import", "from", "as", "pass", "break", "continue", "with",
                    "try", "except", "finally", "raise", "lambda", "global", "nonlocal", "assert", "del",
                    "yield", "async", "await", "match", "case",
                ),
                Token.KEYWORD,
            ),
            Rule(words("self", "cls", "int", "str", "float", "bool", "bytes", "list", "dict", "set", "tuple"), Token.TYPE),
            number(),
            plainWord(),
        ),
    )

    private val JS = Language(
        "js",
        listOf(
            Rule(LINE_COMMENT_SLASH, Token.COMMENT),
            Rule(BLOCK_COMMENT, Token.COMMENT),
            Rule(DQ, Token.STRING),
            Rule(SQ, Token.STRING),
            Rule(BACKTICK, Token.STRING),
            Rule(
                words(
                    "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch",
                    "case", "break", "continue", "new", "class", "extends", "import", "export", "default",
                    "from", "async", "await", "try", "catch", "finally", "throw", "typeof", "instanceof",
                    "this", "null", "undefined", "true", "false", "of", "in", "delete", "void", "yield",
                    "static", "get", "set", "interface", "type", "enum", "implements", "readonly", "as",
                    "satisfies", "declare", "namespace",
                ),
                Token.KEYWORD,
            ),
            Rule(words("string", "number", "boolean", "any", "unknown", "never", "object", "symbol", "bigint"), Token.TYPE),
            number(),
            plainWord(),
        ),
    )

    private val GO = Language(
        "go",
        listOf(
            Rule(LINE_COMMENT_SLASH, Token.COMMENT),
            Rule(BLOCK_COMMENT, Token.COMMENT),
            Rule(DQ, Token.STRING),
            Rule(BACKTICK, Token.STRING),
            Rule(SQ, Token.STRING),
            Rule(
                words(
                    "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan",
                    "go", "defer", "select", "return", "if", "else", "for", "range", "switch", "case",
                    "default", "break", "continue", "fallthrough", "goto", "nil", "true", "false",
                ),
                Token.KEYWORD,
            ),
            Rule(
                words(
                    "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32",
                    "uint64", "uintptr", "byte", "rune", "float32", "float64", "complex64", "complex128",
                    "bool", "error", "any",
                ),
                Token.TYPE,
            ),
            number(),
            plainWord(),
        ),
        usesTabs = true,
    )

    private val RUST = Language(
        "rs",
        listOf(
            Rule(LINE_COMMENT_SLASH, Token.COMMENT),
            Rule(BLOCK_COMMENT, Token.COMMENT),
            Rule("#!?\\[[^\\]]*\\]", Token.TYPE),
            Rule("r#*\"[^\"]*\"#*|$DQ", Token.STRING),
            Rule("'(?:\\\\.|[^'\\\\])'", Token.STRING),
            // A lifetime looks like an unterminated char literal, so it is matched after one.
            Rule("'[A-Za-z_][A-Za-z0-9_]*", Token.TYPE),
            Rule(
                words(
                    "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "for", "while",
                    "loop", "if", "else", "match", "return", "pub", "use", "mod", "crate", "self", "super",
                    "where", "as", "in", "ref", "move", "box", "unsafe", "async", "await", "dyn", "type",
                    "extern", "continue", "break", "true", "false",
                ),
                Token.KEYWORD,
            ),
            Rule("[A-Z][A-Za-z0-9_]*|" + words("u8", "u16", "u32", "u64", "u128", "usize", "i8", "i16", "i32", "i64", "i128", "isize", "f32", "f64", "bool", "char", "str"), Token.TYPE),
            number(),
            plainWord(),
        ),
    )

    private val C = Language(
        "c",
        listOf(
            Rule(LINE_COMMENT_SLASH, Token.COMMENT),
            Rule(BLOCK_COMMENT, Token.COMMENT),
            Rule("[ \\t]*#[a-z]+", Token.KEYWORD, atStart = true),
            Rule(DQ, Token.STRING),
            Rule(SQ, Token.STRING),
            Rule(
                words(
                    "if", "else", "for", "while", "do", "return", "switch", "case", "break", "continue",
                    "default", "goto", "sizeof", "typedef", "struct", "union", "enum", "static", "const",
                    "volatile", "extern", "inline", "register", "NULL", "true", "false",
                ),
                Token.KEYWORD,
            ),
            Rule(
                words(
                    "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned",
                    "size_t", "ssize_t", "bool", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "FILE",
                ),
                Token.TYPE,
            ),
            number(),
            plainWord(),
        ),
    )

    private val NGINX = Language(
        "nginx",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            // Every nginx line opens with its directive, blocks included.
            Rule("[ \\t]*([a-z_][a-z0-9_]*)(?=[ \\t{;])", Token.KEYWORD, atStart = true, group = 1),
            Rule("\\$[A-Za-z_][A-Za-z0-9_]*", Token.KEY),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            Rule(words("on", "off"), Token.TYPE),
            number(),
            plainWord(),
        ),
    )

    private val DOCKERFILE = Language(
        "dockerfile",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            Rule(
                "[ \\t]*((?i:" +
                    "FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|" +
                    "ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL" +
                    "))(?![A-Za-z0-9_])",
                Token.KEYWORD,
                atStart = true,
                group = 1,
            ),
            Rule("\\$\\{[^}]*\\}?|\\$[A-Za-z_][A-Za-z0-9_]*", Token.KEY),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            Rule(words("AS", "as"), Token.KEYWORD),
            number(),
            plainWord(),
        ),
    )

    private val MARKDOWN = Language(
        "md",
        listOf(
            Rule("#{1,6}[ \\t][^\\n]*", Token.KEYWORD, atStart = true),
            Rule("[ \\t]*```[^\\n]*", Token.TYPE, atStart = true),
            Rule("[ \\t]*(?:[-*+]|\\d+\\.|>)(?=[ \\t])", Token.KEY, atStart = true),
            Rule("`[^`]*`", Token.STRING),
            Rule("\\*\\*[^*]+\\*\\*|__[^_]+__", Token.KEYWORD),
            Rule("\\[[^\\]]*\\]\\([^)]*\\)", Token.KEY),
            plainWord(),
        ),
    )

    private val MAKEFILE = Language(
        "make",
        listOf(
            Rule(LINE_COMMENT_HASH, Token.COMMENT),
            Rule("([^\\s:=]+)(?=[ \\t]*:(?!=))", Token.KEY, atStart = true, group = 1),
            Rule("\\$[({][^)}]*[)}]?|\\$[A-Za-z_@<^]", Token.KEY),
            Rule(words("ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "export", "define", "endef"), Token.KEYWORD),
            Rule(DQ, Token.STRING),
            Rule(SQ_LITERAL, Token.STRING),
            plainWord(),
        ),
        usesTabs = true,
    )

    private val BY_EXT: Map<String, Language> = buildMap {
        listOf("sh", "bash", "zsh", "ksh", "profile", "bashrc", "zshrc").forEach { put(it, SH) }
        listOf("yaml", "yml").forEach { put(it, YAML) }
        put("json", JSON)
        put("toml", TOML)
        listOf("ini", "cfg", "conf", "cnf", "properties", "service", "socket", "timer", "desktop", "repo", "env").forEach { put(it, INI) }
        listOf("py", "pyw").forEach { put(it, PYTHON) }
        listOf("js", "mjs", "cjs", "jsx", "ts", "tsx").forEach { put(it, JS) }
        put("go", GO)
        put("rs", RUST)
        listOf("c", "h", "cc", "cpp", "cxx", "hpp", "hh").forEach { put(it, C) }
        put("nginx", NGINX)
        put("dockerfile", DOCKERFILE)
        listOf("md", "markdown", "mdown").forEach { put(it, MARKDOWN) }
        put("mk", MAKEFILE)
    }

    /** Files whose whole name is the clue, dotfiles included (the leading dot is stripped first). */
    private val BY_NAME: Map<String, Language> = buildMap {
        listOf("bashrc", "bash_profile", "bash_aliases", "zshrc", "zprofile", "profile", "zshenv").forEach { put(it, SH) }
        listOf("gitconfig", "npmrc", "editorconfig", "gitmodules").forEach { put(it, INI) }
        put("makefile", MAKEFILE)
        put("gnumakefile", MAKEFILE)
        put("dockerfile", DOCKERFILE)
        put("containerfile", DOCKERFILE)
    }
}
