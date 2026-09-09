package dev.flint.term.data.imports

import dev.flint.term.data.Host
import dev.flint.term.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvImportTest {
    /** Termius's own template row, with the columns it fills in. */
    private val termius = """
        Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password
        Work,Bastion,,bastion.example.com,ssh,2222,pilif,
        Work,Switch,net,10.0.0.1,telnet,,admin,
        ,Rig,,10.0.2.2,ssh,22,pilif,hunter2
    """.trimIndent()

    @Test
    fun theTermiusTemplateNeedsNoQuestionsAsked() {
        val table = CsvImport.read(termius)!!
        assertTrue(table.ready(table.suggested))
        val hosts = CsvImport.hosts(table, table.suggested)
        assertEquals(listOf("Bastion", "Switch", "Rig"), hosts.map { it.label })
        assertEquals(listOf(2222, 23, 22), hosts.map { it.port })
        assertEquals(listOf("pilif", "admin", "pilif"), hosts.map { it.username })
        assertEquals(Protocol.TELNET, hosts[1].protocol)
    }

    @Test
    fun aTelnetRowWithNoPortGetsTelnetsPort() {
        assertEquals(23, CsvImport.parse(termius)[1].port)
    }

    @Test
    fun headersAreMatchedWithoutCaseSpacesOrPunctuation() {
        val text = """
            Host Name,USER_NAME,SSH Port,Nickname
            box.example.com,root,2200,Box
        """.trimIndent()
        val host = CsvImport.parse(text).single()
        assertEquals("box.example.com", host.hostname)
        assertEquals("root", host.username)
        assertEquals(2200, host.port)
        assertEquals("Box", host.label)
    }

    @Test
    fun aFileWithNoRecognizableHeadersIsHandedBackForMapping() {
        val text = """
            a,b,c
            alpha,root,10.0.0.5
        """.trimIndent()
        val table = CsvImport.read(text)!!
        assertFalse(table.ready(table.suggested))
        assertEquals(emptyList<ImportedHost>(), CsvImport.parse(text))

        // …and once somebody has said which column is which, it reads fine.
        val mapping = mapOf(
            CsvImport.Field.LABEL to 0,
            CsvImport.Field.USERNAME to 1,
            CsvImport.Field.HOSTNAME to 2,
        )
        assertTrue(table.ready(mapping))
        val host = CsvImport.hosts(table, mapping).single()
        assertEquals("10.0.0.5", host.hostname)
        assertEquals("root", host.username)
        assertEquals("alpha", host.label)
        assertEquals(22, host.port)
    }

    @Test
    fun quotedFieldsKeepTheirCommasQuotesAndNewlines() {
        val text = "hostname,label\n" +
            "\"h1.example.com\",\"Prod, primary\"\n" +
            "h2.example.com,\"He said \"\"hi\"\"\"\n" +
            "h3.example.com,\"two\nlines\"\n"
        val hosts = CsvImport.parse(text)
        assertEquals(listOf("Prod, primary", "He said \"hi\"", "two\nlines"), hosts.map { it.label })
        assertEquals(3, hosts.size)
    }

    @Test
    fun aSemicolonFileFromAEuropeanSpreadsheetIsReadTheSameWay() {
        val text = "Label;Hostname;Port\nBox;box.example.com;2222\n"
        val host = CsvImport.parse(text).single()
        assertEquals("box.example.com", host.hostname)
        assertEquals(2222, host.port)
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheFirstColumn() {
        val host = CsvImport.parse("﻿hostname,port\nbox.example.com,2222\n").single()
        assertEquals("box.example.com", host.hostname)
        assertEquals(2222, host.port)
    }

    @Test
    fun aPastedTargetInTheHostnameColumnIsTakenApart() {
        val hosts = CsvImport.parse("hostname\nroot@box.example.com:2200\nplain.example.com\n")
        assertEquals("box.example.com", hosts[0].hostname)
        assertEquals("root", hosts[0].username)
        assertEquals(2200, hosts[0].port)
        assertEquals("plain.example.com", hosts[1].hostname)
        assertEquals(22, hosts[1].port)
    }

    @Test
    fun anExplicitPortColumnBeatsOneInTheHostname() {
        val host = CsvImport.parse("hostname,port\nbox.example.com:2200,443\n").single()
        assertEquals(443, host.port)
    }

    @Test
    fun blankAndHeaderOnlyFilesAreNothingRatherThanAnError() {
        assertNull(CsvImport.read(""))
        assertNull(CsvImport.read("\n\n"))
        assertEquals(emptyList<ImportedHost>(), CsvImport.parse("hostname,port\n"))
        assertEquals(emptyList<ImportedHost>(), CsvImport.parse("hostname\n\n,\n"))
    }

    @Test
    fun anOutOfRangePortFallsBackToTheDefaultRatherThanBeingSaved() {
        assertEquals(22, CsvImport.parse("hostname,port\nbox.example.com,0\n").single().port)
        assertEquals(22, CsvImport.parse("hostname,port\nbox.example.com,not a number\n").single().port)
    }

    @Test
    fun hostsAlreadySavedAreCountedNotDuplicated() {
        val hosts = CsvImport.parse(termius)
        val existing = listOf(Host(hostname = "10.0.2.2", port = 22, username = "pilif"))
        val plan = planImport(hosts, existing)
        assertEquals(listOf("Bastion", "Switch"), plan.fresh.map { it.label })
        assertEquals("2 hosts, 1 already here", plan.describe())
    }

    @Test
    fun aFileThatRepeatsAHostOffersItOnceAndDoesNotCallItAnOldOne() {
        val text = "hostname,username\nbox.example.com,root\nBOX.EXAMPLE.COM,root\n"
        val plan = planImport(CsvImport.parse(text), emptyList())
        assertEquals(1, plan.fresh.size)
        assertEquals("1 host", plan.describe())
    }
}
