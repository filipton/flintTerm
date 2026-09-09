package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where these fixtures come from.
 *
 * The docker ones are real: `docker ps -a --format '{{json .}}'` and
 * `docker stats --no-stream --format '{{json .}}'` run against docker 29.7.2 on
 * the machine this was written on, copied verbatim and then trimmed of the
 * containers that had nothing to do with this. The compose-labelled rows are
 * the same key set with the labels compose stamps on what it starts
 * (`com.docker.compose.project`, `.service`) filled in, since none of the real
 * containers on that machine were started by compose. The field list matches
 * the table in docker's own `docker container ls` reference.
 *
 * The podman ones are built from podman's source rather than from a run —
 * podman was not installed and none was contacted. `{{json .}}` hands the
 * value straight to Go's marshaller, so the shapes are the structs themselves:
 * `ListContainer` in pkg/domain/entities/types/container_ps.go (`Id` with that
 * spelling, `Names` and `Command` arrays, `Labels` a map, `Ports` a list),
 * `PortMapping` in containers/common libnetwork/types/network.go (`host_ip`,
 * `container_port`, `host_port`, `range`, `protocol`), and `ContainerStats` in
 * libpod/define/containerstate.go, whose numbers are plain numbers. Podman's
 * other spelling — the one `podman stats --format json` prints, with `%` in
 * the strings — is the `jstat` struct in cmd/podman/containers/stats.go, and
 * the example in the podman-stats man page. The project label podman-compose
 * writes alongside docker's is `io.podman.compose.project` (podman_compose.py).
 */
class ContainersParserTest {

    // ---- docker -----------------------------------------------------------

    /** Verbatim from `docker ps -a --format '{{json .}}'`, docker 29.7.2. */
    private val dockerBuildx =
        """{"Command":"\"buildkitd --allow-i…\"","CreatedAt":"2026-01-20 19:54:51 +0100 CET","HealthStatus":"none","ID":"1c98015b1ebc","Image":"moby/buildkit:buildx-stable-1","Labels":"","LocalVolumes":"1","Mounts":"buildx_buildki…","Names":"buildx_buildkit_mybuilder0","Networks":"bridge","Platform":null,"Ports":"","RunningFor":"7 months ago","Size":"0B (virtual 227MB)","State":"running","Status":"Up 36 hours"}"""

    /**
     * Verbatim but for the container's own name and labels: this one is the
     * real reason the label splitter exists — docker joins labels with commas
     * and this image's description has a comma in it.
     */
    private val dockerQbittorrent =
        """{"Command":"\"/bin/bash /start.sh\"","CreatedAt":"2025-12-29 22:03:58 +0100 CET","HealthStatus":"none","ID":"b806af832297","Image":"tenseiken/qbittorrent-wireguard:latest","Labels":"org.opencontainers.image.description=An advanced BitTorrent client programmed in C++, based on Qt toolkit and libtorrent-rasterbar,org.opencontainers.image.version=5.1.4","LocalVolumes":"0","Mounts":"/home/pilif/qb…","Names":"qbittorrent-wireguard","Networks":"bridge","Platform":null,"Ports":"","RunningFor":"8 months ago","Size":"2.15kB (virtual 226MB)","State":"exited","Status":"Exited (137) 6 months ago"}"""

    private val dockerComposeWeb =
        """{"Command":"\"/docker-entrypoint.…\"","CreatedAt":"2026-09-01 10:11:12 +0200 CEST","HealthStatus":"healthy","ID":"3f1a9c2b77de","Image":"nginx:1.27","Labels":"com.docker.compose.container-number=1,com.docker.compose.project=shop,com.docker.compose.service=web","LocalVolumes":"0","Mounts":"","Names":"shop-web-1","Networks":"shop_default","Platform":null,"Ports":"0.0.0.0:8080->80/tcp, [::]:8080->80/tcp","RunningFor":"8 days ago","Size":"0B (virtual 192MB)","State":"running","Status":"Up 8 days (healthy)"}"""

    private val dockerComposeDb =
        """{"Command":"\"docker-entrypoint.s…\"","CreatedAt":"2026-09-01 10:11:10 +0200 CEST","HealthStatus":"none","ID":"9ab3c4d5e6f7","Image":"postgres:16","Labels":"com.docker.compose.container-number=1,com.docker.compose.project=shop,com.docker.compose.service=db","LocalVolumes":"1","Mounts":"shop_pgdata","Names":"shop-db-1","Networks":"shop_default","Platform":null,"Ports":"5432/tcp","RunningFor":"8 days ago","Size":"63B (virtual 438MB)","State":"paused","Status":"Up 8 days (Paused)"}"""

    /** Verbatim from `docker stats --no-stream --format '{{json .}}'`. */
    private val dockerStats =
        """{"BlockIO":"239MB / 13.6MB","CPUPerc":"0.00%","Container":"1c98015b1ebc51856bf507463e1311e1f415f7c4d3bbb549cec53e3d732e43c4","ID":"1c98015b1ebc","MemPerc":"0.09%","MemUsage":"15.02MiB / 15.54GiB","Name":"buildx_buildkit_mybuilder0","NetIO":"2.19MB / 126B","PIDs":"19"}"""

    @Test
    fun `docker ps reads the fields it flattens to strings`() {
        val list = Containers.parsePs("$dockerBuildx\n$dockerComposeWeb\n")
        assertEquals(2, list.size)
        val buildx = list[0]
        assertEquals("1c98015b1ebc", buildx.id)
        assertEquals("buildx_buildkit_mybuilder0", buildx.name)
        assertEquals("moby/buildkit:buildx-stable-1", buildx.image)
        assertEquals(ContainerState.RUNNING, buildx.state)
        assertEquals("Up 36 hours", buildx.status)
        assertNull(buildx.project)

        val web = list[1]
        assertEquals("shop", web.project)
        assertEquals("web", web.service)
        assertEquals("0.0.0.0:8080->80/tcp, [::]:8080->80/tcp", web.ports)
    }

    @Test
    fun `docker paused and exited containers keep their state apart`() {
        val list = Containers.parsePs("$dockerComposeDb\n$dockerQbittorrent\n")
        assertEquals(ContainerState.PAUSED, list[0].state)
        assertEquals(ContainerState.EXITED, list[1].state)
        assertEquals("Exited (137) 6 months ago", list[1].status)
    }

    @Test
    fun `a docker label whose value contains a comma survives the split`() {
        val labels = Containers.labels(
            "org.opencontainers.image.description=An advanced BitTorrent client programmed in C++, " +
                "based on Qt toolkit and libtorrent-rasterbar,org.opencontainers.image.version=5.1.4",
        )
        assertEquals("5.1.4", labels["org.opencontainers.image.version"])
        assertEquals(
            "An advanced BitTorrent client programmed in C++, based on Qt toolkit and libtorrent-rasterbar",
            labels["org.opencontainers.image.description"],
        )
    }

    @Test
    fun `docker stats are strings with units and percent signs`() {
        val stats = Containers.parseStats(dockerStats)
        assertEquals(1, stats.size)
        assertEquals(0.0, stats[0].cpuPercent!!, 0.001)
        assertEquals(0.09, stats[0].memoryPercent!!, 0.001)
        assertEquals((15.02 * 1024 * 1024).toLong(), stats[0].memoryBytes)
        assertEquals((15.54 * 1024 * 1024 * 1024).toLong(), stats[0].memoryLimitBytes)
    }

    @Test
    fun `docker stats find their container by the short id`() {
        val merged = Containers.merge(Containers.parsePs(dockerBuildx), Containers.parseStats(dockerStats))
        assertEquals(0.09, merged[0].memoryPercent!!, 0.001)
        assertEquals((15.02 * 1024 * 1024).toLong(), merged[0].memoryBytes)
    }

    // ---- podman -----------------------------------------------------------

    private val podmanComposeWeb =
        """{"AutoRemove":false,"Command":["nginx","-g","daemon off;"],"Created":"2026-09-01T10:11:12.239834+02:00","CreatedAt":"","Exited":false,"ExitedAt":-1,"ExitCode":0,"Id":"6b1f0d7a2c3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3","Image":"docker.io/library/nginx:1.27","ImageID":"a72860cb95fd","IsInfra":false,"Labels":{"com.docker.compose.project":"shop","com.docker.compose.service":"web","io.podman.compose.project":"shop","io.podman.compose.service":"web"},"Mounts":[],"Names":["shop_web_1"],"Networks":["shop_default"],"Pid":41231,"Pod":"","PodName":"","Ports":[{"host_ip":"","container_port":80,"host_port":8080,"range":1,"protocol":"tcp"}],"Restarts":0,"StartedAt":1756713072,"State":"running","Status":"Up 8 days"}"""

    private val podmanLoose =
        """{"AutoRemove":false,"Command":["redis-server"],"Created":"2026-08-20T09:00:00.123456+02:00","CreatedAt":"","Exited":true,"ExitedAt":1756000000,"ExitCode":0,"Id":"1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3d4e5f60718293a4b5c6d7e8f90","Image":"docker.io/library/redis:7","ImageID":"b1946ac92492","IsInfra":false,"Labels":{},"Mounts":[],"Names":["cache"],"Networks":["podman"],"Pid":0,"Pod":"","PodName":"","Ports":[],"Restarts":0,"StartedAt":1755000000,"State":"exited","Status":"Exited (0) 2 weeks ago"}"""

    /** `podman stats --no-stream --format '{{json .}}'`: the struct, so numbers. */
    private val podmanStatsTemplate =
        """{"AvgCPU":1.2345,"ContainerID":"6b1f0d7a2c3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3","Name":"shop_web_1","CPU":0.53,"CPUNano":123456789,"CPUSystemNano":987654321,"SystemNano":1756713072000000000,"MemUsage":3092000,"MemLimit":16700000000,"MemPerc":0.0185,"Network":{"eth0":{"RxBytes":1024,"TxBytes":2048}},"BlockInput":0,"BlockOutput":4096,"PIDs":2,"UpTime":691200000000000,"Duration":691200000000000}"""

    /** `podman stats --no-stream --format json`: strings, with the percent signs. */
    private val podmanStatsJson =
        """{"id":"6b1f0d7a2c3e","name":"shop_web_1","cpu_time":"8 days","cpu_percent":"0.53%","avg_cpu":"1.23%","mem_usage":"3.092MB / 16.7GB","mem_percent":"0.02%","net_io":"1.024kB / 2.048kB","block_io":"-- / 4.096kB","pids":"2"}"""

    @Test
    fun `podman ps reads arrays and maps where docker had strings`() {
        val list = Containers.parsePs("$podmanComposeWeb\n$podmanLoose\n")
        assertEquals(2, list.size)
        val web = list[0]
        assertEquals("6b1f0d7a2c3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3", web.id)
        assertEquals("shop_web_1", web.name)
        assertEquals("docker.io/library/nginx:1.27", web.image)
        assertEquals(ContainerState.RUNNING, web.state)
        assertEquals("shop", web.project)
        assertEquals("web", web.service)
        // An unset host_ip means every interface, which is how docker prints it.
        assertEquals("0.0.0.0:8080->80/tcp", web.ports)

        val cache = list[1]
        assertEquals("cache", cache.name)
        assertEquals(ContainerState.EXITED, cache.state)
        assertNull(cache.project)
        assertEquals("", cache.ports)
    }

    @Test
    fun `podman-compose's own project label groups a container just as well`() {
        val onlyPodmanLabels = podmanComposeWeb.replace(
            """"com.docker.compose.project":"shop","com.docker.compose.service":"web",""",
            "",
        )
        val container = Containers.parsePs(onlyPodmanLabels).single()
        assertEquals("shop", container.project)
        assertEquals("web", container.service)
    }

    @Test
    fun `podman stats read as numbers or as printed strings`() {
        val fromTemplate = Containers.parseStats(podmanStatsTemplate).single()
        assertEquals(0.53, fromTemplate.cpuPercent!!, 0.001)
        assertEquals(0.0185, fromTemplate.memoryPercent!!, 0.0001)
        assertEquals(3_092_000L, fromTemplate.memoryBytes)
        assertEquals(16_700_000_000L, fromTemplate.memoryLimitBytes)

        val fromJson = Containers.parseStats(podmanStatsJson).single()
        assertEquals(0.53, fromJson.cpuPercent!!, 0.001)
        assertEquals(0.02, fromJson.memoryPercent!!, 0.001)
        assertEquals(3_092_000L, fromJson.memoryBytes)
        assertEquals(16_700_000_000L, fromJson.memoryLimitBytes)
    }

    @Test
    fun `podman stats find their container by the long id or the name`() {
        val containers = Containers.parsePs(podmanComposeWeb)
        assertEquals(0.53, Containers.merge(containers, Containers.parseStats(podmanStatsTemplate))[0].cpuPercent!!, 0.001)
        assertEquals(0.53, Containers.merge(containers, Containers.parseStats(podmanStatsJson))[0].cpuPercent!!, 0.001)
    }

    @Test
    fun `a container docker has no sample for gets no numbers rather than zeroes`() {
        val stats = Containers.parseStats("""{"ID":"1c98015b1ebc","CPUPerc":"--","MemUsage":"-- / --","MemPerc":"--"}""")
        assertNull(stats[0].cpuPercent)
        assertNull(stats[0].memoryBytes)
        assertNull(stats[0].memoryPercent)
    }

    // ---- the whole reply, and the grouping --------------------------------

    @Test
    fun `the reply is cut at its markers, so a chatty login is not parsed`() {
        val output = buildString {
            appendLine("You have mail.")
            appendLine("@@ps")
            appendLine(dockerComposeWeb)
            appendLine(dockerComposeDb)
            appendLine(dockerBuildx)
            appendLine("@@stats")
            appendLine(dockerStats)
            appendLine("@@end")
        }
        val list = Containers.parse(output)
        assertEquals(3, list.size)
        assertEquals(listOf("shop-web-1", "shop-db-1", "buildx_buildkit_mybuilder0"), list.map { it.name })
        assertEquals((15.02 * 1024 * 1024).toLong(), list[2].memoryBytes)
    }

    @Test
    fun `projects come first in name order and the rest go last`() {
        val groups = Containers.group(
            Containers.parsePs("$dockerBuildx\n$dockerComposeWeb\n$dockerComposeDb\n$podmanLoose\n"),
        )
        assertEquals(listOf("shop", null), groups.map { it.project })
        // Inside a project the rows are in service order: db before web.
        assertEquals(listOf("db", "web"), groups[0].containers.map { it.service })
        assertEquals(listOf("buildx_buildkit_mybuilder0", "cache"), groups[1].containers.map { it.name })
    }

    // ---- the commands -----------------------------------------------------

    @Test
    fun `detection asks what this login can list, not what is installed`() {
        assertTrue(Containers.DETECT.contains("command -v docker"))
        assertTrue(Containers.DETECT.contains("docker ps -q"))
        assertTrue(Containers.DETECT.contains("command -v podman"))
        assertEquals(ContainerRuntime.DOCKER, Containers.runtimeFrom("Welcome to Debian\ndocker\n"))
        assertEquals(ContainerRuntime.PODMAN, Containers.runtimeFrom("podman"))
        assertNull(Containers.runtimeFrom(""))
        assertNull(Containers.runtimeFrom("sh: docker: not found"))
    }

    @Test
    fun `the list script asks both runtimes the same two questions`() {
        val script = Containers.listScript(ContainerRuntime.PODMAN)
        assertTrue(script.contains("podman ps -a --format '{{json .}}'"))
        assertTrue(script.contains("podman stats --no-stream --format '{{json .}}'"))
        // A runtime that answers with an error must not fail the whole read.
        assertTrue(script.contains("|| true"))
    }

    @Test
    fun `actions name the container and nothing else`() {
        assertEquals(
            "docker restart 3f1a9c2b77de",
            Containers.actionCommand(ContainerRuntime.DOCKER, ContainerAction.RESTART, "3f1a9c2b77de"),
        )
        assertEquals(
            "podman unpause shop_web_1",
            Containers.actionCommand(ContainerRuntime.PODMAN, ContainerAction.UNPAUSE, "shop_web_1"),
        )
        assertEquals("docker logs --tail 200 shop-web-1", Containers.logsCommand(ContainerRuntime.DOCKER, "shop-web-1"))
    }

    @Test
    fun `an id that is not an id is refused rather than quoted`() {
        assertNull(Containers.actionCommand(ContainerRuntime.DOCKER, ContainerAction.STOP, "abc; rm -rf /"))
        assertNull(Containers.actionCommand(ContainerRuntime.DOCKER, ContainerAction.STOP, "\$(reboot)"))
        assertNull(Containers.actionCommand(ContainerRuntime.DOCKER, ContainerAction.STOP, ""))
        assertNull(Containers.logsCommand(ContainerRuntime.PODMAN, "a b"))
    }

    @Test
    fun `stopping is the only action that asks first`() {
        assertEquals(
            listOf(ContainerAction.STOP),
            ContainerAction.entries.filter { it.asksFirst },
        )
    }

    // ---- following a log --------------------------------------------------

    @Test
    fun `only the lines past the overlap are printed again`() {
        val first = listOf("a", "b", "c")
        assertEquals(emptyList<String>(), Containers.newTail(first, first))
        assertEquals(listOf("d"), Containers.newTail(first, listOf("b", "c", "d")))
        assertEquals(listOf("d", "e"), Containers.newTail(first, listOf("c", "d", "e")))
        // Repeated lines are not an overlap to be swallowed: a service logging
        // the same line twice must print it twice.
        assertEquals(listOf("x"), Containers.newTail(listOf("x", "x"), listOf("x", "x", "x")))
        // Nothing in common: the window moved further than it is wide.
        assertEquals(listOf("y", "z"), Containers.newTail(first, listOf("y", "z")))
        assertEquals(listOf("a", "b"), Containers.newTail(emptyList(), listOf("a", "b")))
    }
}
