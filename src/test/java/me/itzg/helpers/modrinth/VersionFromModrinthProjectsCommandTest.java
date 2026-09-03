package me.itzg.helpers.modrinth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.stefanbirkner.systemlambda.SystemLambda;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import java.util.List;

import me.itzg.helpers.LatchingExecutionExceptionHandler;
import me.itzg.helpers.McImageHelper;
import me.itzg.helpers.errors.InvalidParameterException;
import me.itzg.helpers.modrinth.model.VersionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.ExitCode;

@WireMockTest
class VersionFromModrinthProjectsCommandTest {

    private final Logger log = (Logger) LoggerFactory.getLogger("me.itzg.helpers");
    private Level logLevel;

    @BeforeEach
    void captureLogLeve() {
        logLevel = log.getLevel();
    }

    @AfterEach
    void setLogLevel() {
        log.setLevel(logLevel);
    }

    @Test
    void testCommand(WireMockRuntimeInfo wmInfo) throws Exception {

        stubGetProjects("viaversion", "viabackwards", "griefprevention", "discordsrv");

        final String stdout = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(new McImageHelper())
                .execute(
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", stubMcApi(wmInfo),
                    "--allowed-version-type", VersionType.release.name(),
                    "--projects", "viaversion,viabackwards,griefprevention,discordsrv"
                );

            assertThat(exitCode)
                .isEqualTo(ExitCode.OK);
        });

        assertThat(stdout).isEqualToNormalizingNewlines("1.21.10\n");
    }

    @Test
    void testCommandFabric(WireMockRuntimeInfo wmInfo) throws Exception {
        stubGetProjects("fabric-api", "nucledoom");

        final String stdout = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(new McImageHelper())
                .execute(
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", stubMcApi(wmInfo),
                    "--projects", "fabric-api, nucledoom"
                );

            assertThat(exitCode)
                .isEqualTo(ExitCode.OK);
        });

        assertThat(stdout).isEqualToNormalizingNewlines("1.21.4\n");
    }

    @Test
    void findsHighestReleaseWhenAllProjectsMatch(WireMockRuntimeInfo wmInfo) throws Exception {
        stubProjectVersions("project-one", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-two", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-three", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-four", List.of("1.21.6", "1.21.7", "1.21.8"));

        final String stdout = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(new McImageHelper())
                .execute(
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", stubMcApi(wmInfo),
                    "--allowed-version-type", VersionType.release.name(),
                    "--projects", "project-one,project-two,project-three,project-four"
                );

            assertThat(exitCode).isEqualTo(ExitCode.OK);
        });

        assertThat(stdout).isEqualToNormalizingNewlines("1.21.8\n");
    }

    @Test
    void findsReleaseWhenOneProjectHasShorterVersionList(WireMockRuntimeInfo wmInfo) throws Exception {
        stubProjectVersions("project-one", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-two", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-three", List.of("1.21.6", "1.21.7"));
        stubProjectVersions("project-four", List.of("1.21.6", "1.21.7", "1.21.8"));

        final String stdout = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(new McImageHelper())
                .execute(
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", stubMcApi(wmInfo),
                    "--allowed-version-type", VersionType.release.name(),
                    "--projects", "project-one,project-two,project-three,project-four"
                );

            assertThat(exitCode).isEqualTo(ExitCode.OK);
        });

        assertThat(stdout).isEqualToNormalizingNewlines("1.21.7\n");
    }

    @Test
    void returnsFailureWhenProjectsHaveNoCommonRelease(WireMockRuntimeInfo wmInfo) throws Exception {
        stubProjectVersions("project-one", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-two", List.of("1.21.6", "1.21.7", "1.21.8"));
        stubProjectVersions("project-three", List.of("1.21.4", "1.21.5"));
        stubProjectVersions("project-four", List.of("1.21.6", "1.21.7", "1.21.8"));

        final String stderr = SystemLambda.tapSystemErr(() -> {
            final int exitCode = new CommandLine(new McImageHelper())
                .execute(
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", stubMcApi(wmInfo),
                    "--allowed-version-type", VersionType.release.name(),
                    "--projects", "project-one,project-two,project-three,project-four"
                );

            assertThat(exitCode).isEqualTo(ExitCode.SOFTWARE);
        });

        assertThat(stderr)
            .contains("Failed to find a compatible Minecraft version across all projects");
    }

    @Test
    void testCommandWithProjectQualifiers(WireMockRuntimeInfo wmInfo) throws Exception {
        stubGetProjects("viaversion", "viabackwards", "griefprevention", "discordsrv");

        final String stdout = SystemLambda.tapSystemOut(() -> {
            final int exitcode = new CommandLine(new McImageHelper())
                .execute(
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", stubMcApi(wmInfo),
                    "--projects", "paper:viaversion,viabackwards,griefprevention:ue7jAjJ5,discordsrv"
                );

            assertThat(exitcode)
                .isEqualTo(ExitCode.OK);
        });

        assertThat(stdout).isEqualToNormalizingNewlines("1.21.10\n");
    }

    @Test
    void rejectsNullProjects() throws Exception {

        final LatchingExecutionExceptionHandler exceptionHandler = new LatchingExecutionExceptionHandler();

        new CommandLine(new McImageHelper())
            .setExecutionExceptionHandler(exceptionHandler)
            .execute(
            "version-from-modrinth-projects"
        );

        assertThat(exceptionHandler.getExecutionException())
            .isInstanceOf(InvalidParameterException.class)
            .hasMessageContaining("No projects provided, please provide at least one Modrinth project");

    }

    @Test
    void rejectsEmptyProjects() throws Exception {

        final LatchingExecutionExceptionHandler exceptionHandler = new LatchingExecutionExceptionHandler();

        new CommandLine(new McImageHelper())
            .setExecutionExceptionHandler(exceptionHandler)
            .execute(
            "version-from-modrinth-projects",
            "--projects="
        );

        assertThat(exceptionHandler.getExecutionException())
            .isInstanceOf(InvalidParameterException.class)
            .hasMessageContaining("No Modrinth projects parsed successfully, please ensure projects follow \"<loader>:<project ID>|<slug>\" and are delimited by commas");

    }

    @Test
    void reportsCoverageAndReturnsFailureWhenNoReleaseMatches(WireMockRuntimeInfo wmInfo) throws Exception {
        stubProjectVersions("project-one", List.of("1.21.10"));
        stubProjectVersions("project-two", List.of("1.21.4"));
        final String mcApiUrl = stubMcApi(wmInfo);

        final String err = SystemLambda.tapSystemErr(() -> {
            final int exitCode = new CommandLine(new McImageHelper())
                .execute(
                    "--debug",
                    "version-from-modrinth-projects",
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--mc-api-base-url", mcApiUrl,
                    "--allowed-version-type", VersionType.release.name(),
                    "--projects", "project-one,project-two"
                );

            assertThat(exitCode)
                .isEqualTo(ExitCode.SOFTWARE);
        });

        assertThat(err)
            .contains(
                "1.21.10: 1/2 projects; missing: project-two",
                "1.21.4: 1/2 projects; missing: project-one"
            )
            .doesNotContain("Closest matches:");
    }

    private void stubGetProjects(String... projects) {
        for (final String project : projects) {
            stubFor(get(urlPathEqualTo("/v2/project/" + project + "/version"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBodyFile("modrinth/project-" + project + "-versions.json")
                )
            );
        }
    }

    private String stubMcApi(WireMockRuntimeInfo wmInfo) {
        stubFor(get(urlPathEqualTo("/mc/game/version_manifest_v2.json"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBodyFile("minecraft-version-manifest.json")
            )
        );

        return wmInfo.getHttpBaseUrl() + "/mc/game/version_manifest_v2.json";
    }

    private void stubProjectVersions(String project, List<String> gameVersions) {
        final ObjectMapper mapper = new ObjectMapper();
        final ArrayNode response = mapper.createArrayNode();

        final ObjectNode version = response.addObject()
            .put("id", "version-" + project)
            .put("date_published", "2026-01-01T00:00:00Z")
            .put("version_type", "release");

        final ArrayNode supportedVersions = version.putArray("game_versions");
        gameVersions.forEach(supportedVersions::add);

        stubFor(get(urlEqualTo("/v2/project/" + project + "/version"))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withJsonBody(response)
                )
        );
    }
}
