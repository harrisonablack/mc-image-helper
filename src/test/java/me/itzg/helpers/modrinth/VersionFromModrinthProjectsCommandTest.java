package me.itzg.helpers.modrinth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.util.List;
import me.itzg.helpers.errors.InvalidParameterException;
import me.itzg.helpers.modrinth.model.VersionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ExitCode;
import org.slf4j.LoggerFactory;

@WireMockTest
class VersionFromModrinthProjectsCommandTest {

    @Test
    void testCommand(WireMockRuntimeInfo wmInfo) throws Exception {

        stubGetProjects("viaversion", "viabackwards", "griefprevention", "discordsrv");

        final String out = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(command(wmInfo))
                .execute(
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--projects", "viaversion,viabackwards,griefprevention,discordsrv"
                );

            assertThat(exitCode)
                .isEqualTo(ExitCode.OK);
        });

        assertThat(out).isEqualToNormalizingNewlines("1.21.10\n");
    }

    @Test
    void testCommandFabric(WireMockRuntimeInfo wmInfo) throws Exception {

        stubGetProjects("fabric-api", "nucledoom");

        final String out = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(command(wmInfo))
                .execute(
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--projects", "fabric-api, nucledoom"
                );

            assertThat(exitCode)
                .isEqualTo(ExitCode.OK);
        });

        assertThat(out).isEqualToNormalizingNewlines("1.21.4\n");
    }

    @Test
    void testCommandWithProjectQualifiers(WireMockRuntimeInfo wmInfo) throws Exception {

        stubGetProjects("viaversion", "viabackwards", "griefprevention", "discordsrv");

        final String out = SystemLambda.tapSystemOut(() -> {
            final int exitCode = new CommandLine(command(wmInfo))
                .execute(
                    "--api-base-url", wmInfo.getHttpBaseUrl(),
                    "--projects", "paper:viaversion,viabackwards,griefprevention:ue7jAjJ5,discordsrv"
                );

            assertThat(exitCode)
                .isEqualTo(ExitCode.OK);
        });

        assertThat(out).isEqualToNormalizingNewlines("1.21.10\n");
    }

    @Test
    void rejectsNullProjects() {
        assertThatThrownBy(() -> new VersionFromModrinthProjectsCommand().call())
            .isInstanceOf(InvalidParameterException.class)
            .hasMessage("No projects provided, please provide at least one Modrinth project");
    }

    @Test
    void rejectsEmptyProjects() {
        final VersionFromModrinthProjectsCommand command = new VersionFromModrinthProjectsCommand();
        command.projects = List.of();

        assertThatThrownBy(command::call)
            .isInstanceOf(InvalidParameterException.class)
            .hasMessage("No projects provided, please provide at least one Modrinth project");
    }

    @Test
    void reportsCoverageAndReturnsFailureWhenNoReleaseMatches(WireMockRuntimeInfo wmInfo) throws Exception {
        stubProjectVersions("project-one", "1.21.10");
        stubProjectVersions("project-two", "1.21.4");
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

    private void stubProjectVersions(String project, String gameVersion) {
        stubFor(get(urlPathEqualTo("/v2/project/" + project + "/version"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(String.format(
                    "[{\"game_versions\":[\"%s\"],\"id\":\"version-%s\",\"date_published\":\"2026-01-01T00:00:00Z\",\"version_type\":\"release\"}]",
                    gameVersion,
                    project
                ))
            )
        );
    }
}