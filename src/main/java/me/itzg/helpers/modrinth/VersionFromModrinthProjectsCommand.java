package me.itzg.helpers.modrinth;

import static me.itzg.helpers.McImageHelper.SPLIT_COMMA_NL;
import static me.itzg.helpers.McImageHelper.SPLIT_SYNOPSIS_COMMA_NL;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import me.itzg.helpers.errors.InvalidParameterException;
import me.itzg.helpers.http.SharedFetch;
import me.itzg.helpers.http.SharedFetchArgs;
import me.itzg.helpers.modrinth.model.VersionType;
import me.itzg.helpers.versions.MinecraftVersionsApi;
import me.itzg.helpers.versions.VersionManifestV2;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Finds the highest Minecraft release supported by all effective Modrinth
 * project references.
 *
 * <p>Each effective project reference is resolved to the Minecraft game versions
 * it supports. Those versions are then compared with the official Minecraft
 * release manifest. Releases are checked from newest to oldest, and the first
 * release supported by every effective project reference is selected.
 *
 * <p>Each supported-version list remains aligned with its project reference by
 * index:
 *
 * <pre>
 * refs[0] = Project A
 * versionsByProject[0] = [1.21.10, 1.21.9, 1.21.7]
 *
 * refs[1] = Project B
 * versionsByProject[1] = [1.21.10, 1.21.8, 1.21.7]
 *
 * refs[2] = Project C
 * versionsByProject[2] = [1.21.10, 1.21.7]
 *
 * refs[3] = Project D
 * versionsByProject[3] = [1.21.9, 1.21.7]
 *
 * Selected release: 1.21.7
 * </pre>
 *
 * <p>Optional project references are excluded from version resolution when at
 * least one required reference is present. If all references are optional, all
 * references are used.
 */
@Command(name = "version-from-modrinth-projects", description = "Finds a compatible Minecraft version across given Modrinth projects")
@Slf4j
public class VersionFromModrinthProjectsCommand implements Callable<Integer> {
    @Option(
        names = "--projects",
        description = "Project ID or Slug. Can be <project ID>|<slug>,"
            + " <loader>:<project ID>|<slug>,"
            + " <loader>:<project ID>|<slug>:<version ID|version number|release type>,"
            + " '@'<filename with ref per line (supports # comments)>"
            + "%nAppend '?' to mark a project as optional (excluded from version resolution)."
            + "%nExamples: fabric-api, fabric:fabric-api, fabric:fabric-api:0.76.1+1.19.2,"
            + " datapack:terralith, pl3xmap?, @/path/to/modrinth-mods.txt"
            + "%nValid release types: release, beta, alpha"
            + "%nValid loaders: fabric, forge, paper, datapack, etc.",
        split = SPLIT_COMMA_NL,
        splitSynopsisLabel = SPLIT_SYNOPSIS_COMMA_NL,
        paramLabel = "[loader:]id|slug[?][:version]",
        // at least one is required
        arity = "1..*"
    )
    List<String> projects;

    @Option(names = "--loader", description = "Valid values: ${COMPLETION-CANDIDATES}")
    Loader loader;

    @Option(names = "--allowed-version-type", defaultValue = "release", description = "Valid values: ${COMPLETION-CANDIDATES}")
    VersionType defaultVersionType;

    @Option(names = "--api-base-url", defaultValue = "${env:MODRINTH_API_BASE_URL:-https://api.modrinth.com}",
        description = "Default: ${DEFAULT-VALUE}"
    )
    String baseUrl;

    @Option(names = "--mc-api-base-url", defaultValue = "${env:MC_API_BASE_URL:-https://launchermeta.mojang.com/mc/game/version_manifest_v2.json}",
    description = "Default: ${DEFAULT-VALUE}"
    )
    String mcBaseUrl;

    @ArgGroup(exclusive = false)
    SharedFetchArgs sharedFetchArgs = new SharedFetchArgs();

    @Override
    public Integer call() throws Exception {

        if (projects == null || projects.isEmpty()) {
            throw new InvalidParameterException("No projects provided, please provide at least one Modrinth project");
        }

        try (
            ModrinthApiClient modrinthApiClient = new ModrinthApiClient(baseUrl, "modrinth", sharedFetchArgs.options());
            SharedFetch minecraftVersionsFetch = new SharedFetch("minecraft-versions-api", sharedFetchArgs.options())
        ) {
            final MinecraftVersionsApi minecraftVersionsApi = new MinecraftVersionsApi(minecraftVersionsFetch).setManifestUrl(URI.create(mcBaseUrl));

            final String version = minecraftVersionsApi
                .getAllReleases()
                .flatMap(releases -> versionFromProjects(
                    modrinthApiClient,
                    releases
                ))
                .block();

            if (version == null) {
                log.error("Failed to find a compatible Minecraft version across all projects");
                return ExitCode.SOFTWARE;
            }

            System.out.println(version);
            return ExitCode.OK;
        }
    }

    /**
     * Parses the configured Modrinth project references.
     *
     * <p>Null and blank values are ignored, non-blank values are trimmed, and
     * duplicate values are removed before each remaining value is parsed using
     * {@link ProjectRef#parse(String)}. Required references are preferred over
     * optional references. If every reference is optional, all references are
     * used.
     *
     * @return the effective project references to use for version resolution
     */
    private List<ProjectRef> parseProjects() {

        if (projects == null || projects.isEmpty()) {
            throw new InvalidParameterException("No Modrinth projects provided, please provide at least one Modrinth project");
        }

        final List<ProjectRef> refs = projects.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(ref -> !ref.isEmpty())
            .distinct()
            .map(ProjectRef::parse)
            .collect(Collectors.toList());

        if (refs.isEmpty()) {
            throw new InvalidParameterException("No Modrinth projects parsed successfully, please ensure projects follow \"<loader>:<project ID>|<slug>\" and are delimited by commas");
        }

        final List<ProjectRef> requiredRefs = refs.stream()
            .filter(ref -> !ref.isOptional())
            .collect(Collectors.toList());

        final List<ProjectRef> effectiveRefs;

        if (requiredRefs.isEmpty()) {
            log.warn("All Modrinth projects are marked as optional, using all for version resolution");
            effectiveRefs = refs;
        } else {
            effectiveRefs = requiredRefs;
        }

        return effectiveRefs;
    }

    /**
     * Resolves the Minecraft game versions supported by a Modrinth project
     * reference.
     *     
     * @param client Modrinth API client
     * @param ref Modrinth project reference
     * @return a {@code Mono} emitting the supported Minecraft versions
     */
    private Mono<List<String>> resolveVersions(ModrinthApiClient client, ProjectRef ref) {
        final Loader loader = ref.getLoader() != null ? ref.getLoader() : this.loader;
        final VersionType allowedVersionType = ref.hasVersionType() ? ref.getVersionType() : defaultVersionType;

        return client.resolveProjectGameVersions(ref, loader, null, allowedVersionType);
    }

    /**
     * Resolves supported game versions for each effective project reference and
     * calculates the highest compatible Minecraft release.
     *
     * @param modrinthApiClient Modrinth API client
     * @param officialReleases Minecraft releases in newest to oldest order
     * @return {@code Mono} emitting the compatible Minecraft version, or 
     *                      empty if no compatible version exists
     */
    private Mono<String> versionFromProjects(
        ModrinthApiClient modrinthApiClient,
        List<VersionManifestV2.Version> officialReleases
    ) {
        final List<ProjectRef> refs = parseProjects();

        return Flux.fromIterable(refs)
            .flatMapSequential(projectRef -> {
                return resolveVersions(modrinthApiClient, projectRef);
            })
            .collectList()
            .flatMap(allProjectVersions -> {
                return Mono.justOrEmpty(findHighestCompleteRelease(officialReleases, refs, allProjectVersions));
            });
    }

    /**
     * Finds the highest Minecraft release supported by all effective project
     * references.
     *
     * <p>The releases are traversed in the supplied order, so the list must be
     * ordered from newest to oldest for the first matching release to be the
     * highest compatible release.
     *
     * @param officialReleases Minecraft releases in newest-to-oldest order
     * @param refs effective Modrinth project references
     * @param versionsByProject one list of supported versions Minecraft per modrinth project reference
     * @return Compatible minecraft version, or {@code null} if no releases is supported by all projects
     */
    private String findHighestCompleteRelease(
        List<VersionManifestV2.Version> officialReleases,
        List<ProjectRef> refs,
        List<List<String>> versionsByProject
    ) {
        for (VersionManifestV2.Version release : officialReleases) {
            final List<String> missingProjects = missingProjectNames(refs, versionsByProject, release.getId());
            final int supportedProjectCount = refs.size() - missingProjects.size();

            if (missingProjects.isEmpty()) {
                log.debug("{}: {}/{} projects",
                    release.getId(),
                    supportedProjectCount,
                    refs.size()
                );

                return release.getId();
            }

            log.debug("{}: {}/{} projects; missing: {}",
                release.getId(),
                supportedProjectCount,
                refs.size(),
                String.join(", ", missingProjects)
            );
        }

        return null;
    }

    /**
     * Finds the project identifiers missing support for a Minecraft release.
     *
     * @param refs project references corresponding to {@code versionsByProject}
     * @param versionsByProject one list of supported versions Minecraft per modrinth project reference
     * @param releaseId Minecraft release ID to check
     * @return Projects that do not support {@code releaseId}
     */
    private static List<String> missingProjectNames(
        List<ProjectRef> refs,
        List<List<String>> versionsByProject,
        String releaseId
    ) {
        final List<String> missingProjects = new ArrayList<>();
        for (int projectIndex = 0; projectIndex < refs.size(); projectIndex++) {
            if (!versionsByProject.get(projectIndex).contains(releaseId)) {
                missingProjects.add(refs.get(projectIndex).getIdOrSlug());
            }
        }
        return missingProjects;
    }
}