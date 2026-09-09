package dev.flint.term.terminal

import dev.flint.term.terminal.Highlighter.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One representative line per language. The point is not an exhaustive
 * grammar: it is that a string is a string, a comment runs to the end of the
 * line, and a keyword is not swallowed by the identifier next to it.
 */
class HighlighterTest {

    private fun colored(name: String, line: String): List<Pair<String, Token>> {
        val language = requireNotNull(Highlighter.languageFor(name)) { "no language for $name" }
        return Highlighter.spans(line, language).map { line.substring(it.start, it.end) to it.token }
    }

    private fun assertColored(name: String, line: String, vararg expected: Pair<String, Token>) {
        val actual = colored(name, line)
        expected.forEach { assertTrue("$it missing from $actual", it in actual) }
    }

    @Test
    fun `shell colors keywords, expansions and a trailing comment`() {
        assertColored(
            "deploy.sh",
            """if [ -f "${'$'}HOME/.bashrc" ]; then source ~/.bashrc; fi  # login shells only""",
            "if" to Token.KEYWORD,
            "then" to Token.KEYWORD,
            "\"\$HOME/.bashrc\"" to Token.STRING,
            "# login shells only" to Token.COMMENT,
        )
    }

    @Test
    fun `yaml colors the key, not the value`() {
        assertColored(
            "compose.yaml",
            "  image: nginx:latest   # pinned",
            "image" to Token.KEY,
            "# pinned" to Token.COMMENT,
        )
        assertColored("compose.yml", "- name: \"web\"", "name" to Token.KEY, "\"web\"" to Token.STRING)
    }

    @Test
    fun `json separates keys from strings`() {
        assertColored(
            "config.json",
            """{"name": "androidterm", "port": 22, "debug": true}""",
            "\"name\"" to Token.KEY,
            "\"androidterm\"" to Token.STRING,
            "22" to Token.NUMBER,
            "true" to Token.KEYWORD,
        )
    }

    @Test
    fun `toml colors tables and keys`() {
        assertColored("Cargo.toml", "[package]", "[package]" to Token.TYPE)
        assertColored(
            "Cargo.toml",
            "name = \"androidterm\"  # the crate",
            "name" to Token.KEY,
            "\"androidterm\"" to Token.STRING,
            "# the crate" to Token.COMMENT,
        )
    }

    @Test
    fun `ini colors sections, keys and semicolon comments`() {
        assertColored("sshd.conf", "[Unit]", "[Unit]" to Token.TYPE)
        assertColored("sshd.conf", "Description=A service", "Description" to Token.KEY)
        assertColored("sshd.conf", "; not enabled yet", "; not enabled yet" to Token.COMMENT)
    }

    @Test
    fun `python colors def, f-strings and comments`() {
        assertColored("app.py", "def greet(name):  # say hello", "def" to Token.KEYWORD, "# say hello" to Token.COMMENT)
        assertColored("app.py", "    msg = f\"hello {name}\"", "f\"hello {name}\"" to Token.STRING)
    }

    @Test
    fun `javascript colors const and a url that is not a comment`() {
        assertColored(
            "main.ts",
            """const url = "https://example.com"; // fetched once""",
            "const" to Token.KEYWORD,
            "\"https://example.com\"" to Token.STRING,
            "// fetched once" to Token.COMMENT,
        )
    }

    @Test
    fun `go colors keywords and builtin types`() {
        assertColored(
            "main.go",
            """	var s string = "hi" // greeting""",
            "var" to Token.KEYWORD,
            "string" to Token.TYPE,
            "\"hi\"" to Token.STRING,
            "// greeting" to Token.COMMENT,
        )
    }

    @Test
    fun `rust colors let, capitalized types and lifetimes`() {
        assertColored(
            "lib.rs",
            """    let s: String = "hi".into(); // owned""",
            "let" to Token.KEYWORD,
            "String" to Token.TYPE,
            "\"hi\"" to Token.STRING,
            "// owned" to Token.COMMENT,
        )
        assertColored("lib.rs", "fn f<'a>(x: &'a str) {}", "'a" to Token.TYPE)
    }

    @Test
    fun `c colors preprocessor lines and types`() {
        assertColored("main.c", "#include <stdio.h>", "#include" to Token.KEYWORD)
        assertColored("term.h", "  int count = 42; // rows", "int" to Token.TYPE, "42" to Token.NUMBER, "// rows" to Token.COMMENT)
    }

    @Test
    fun `nginx colors the directive and its variables`() {
        assertColored(
            "nginx.conf",
            "    proxy_pass http://127.0.0.1:8080;  # api",
            "proxy_pass" to Token.KEYWORD,
            "# api" to Token.COMMENT,
        )
        assertColored("nginx.conf", "    proxy_set_header Host \$host;", "\$host" to Token.KEY)
    }

    @Test
    fun `dockerfile colors instructions`() {
        assertColored(
            "Dockerfile",
            "FROM alpine:3.19 AS base  # smallest",
            "FROM" to Token.KEYWORD,
            "AS" to Token.KEYWORD,
            "# smallest" to Token.COMMENT,
        )
    }

    @Test
    fun `markdown colors headings and inline code`() {
        assertColored("README.md", "## Install", "## Install" to Token.KEYWORD)
        assertColored("README.md", "Run `make` first.", "`make`" to Token.STRING)
    }

    @Test
    fun `a keyword inside a longer word is left alone`() {
        assertTrue(colored("app.py", "information = 1").none { it.second == Token.KEYWORD })
        assertTrue(colored("deploy.sh", "notify_iffy=1").none { it.second == Token.KEYWORD })
    }

    @Test
    fun `the language comes from the name, the dotfile or the shebang`() {
        assertEquals("sh", Highlighter.languageFor(".bashrc")?.id)
        assertEquals("make", Highlighter.languageFor("/home/pi/Makefile")?.id)
        assertEquals("dockerfile", Highlighter.languageFor("Dockerfile.ci")?.id)
        assertEquals("sh", Highlighter.languageFor("deploy", "#!/usr/bin/env bash")?.id)
        assertEquals("py", Highlighter.languageFor("healthcheck", "#!/usr/bin/python3")?.id)
        assertNull(Highlighter.languageFor("core.dump"))
    }
}
