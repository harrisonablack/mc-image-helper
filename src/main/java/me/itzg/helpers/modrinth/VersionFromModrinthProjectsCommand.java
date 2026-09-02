package me.itzg.helpers.modrinth;

import static me.itzg.helpers.McImageHelper.SPLIT_COMMA_NL;
import static me.itzg.helpers.McImageHelper.SPLIT_SYNOPSIS_COMMA_NL;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Finds a compatible Minecraft release across a set of Modrinth projects.
 *
 * <p>Each project is resolved to the Minecraft game versions it supports. Those
 * versions are then compared with the official Minecraft release manifest. The
 * first release supported by every effective project reference is selected.
 *
 * <p>Project references are represented by their index in the input list when
 * constructing the support matrix. For example:
 *
 * <pre>
 * Project A: 1.21.10, 1.21.9, 1.21.7
 * Project B: 1.21.10, 1.21.8, 1.21.7
 * Project C: 1.21.10, 1.21.7
 * Project D: 1.21.9, 1.21.7
 *
 * 1.21.10: 0, 1, 2
 * 1.21.9:  0, 3
 * 1.21.8:  1
 * 1.21.7:  0, 1, 2, 3
 * </pre>
 *
 * <p>In this example, {@code 1.21.7} is supported by all four projects.
 * Optional project references are excluded from version resolution when at
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
     * Parse Modrinth Project References.
     *
     * <p>Each configured value is parsed using {@link ProjectRef#parse(String)}.
     * Required references are preferred over optional references. If every
     * reference is optional, all references are used.
     *
     * @return List of project references to use for version resolution.
     */
    private List<ProjectRef> parseProjects() {

        if (projects == null || projects.isEmpty()) {
            throw new InvalidParameterException("No Modrinth projects provided, please provide at least one Modrinth project");
        }

        final List<ProjectRef> refs = projects.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(ref -> !ref.isEmpty())
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
     * Resolves the Minecraft game versions supported by a Modrinth project.
     *
     * @param client Modrinth API client
     * @param ref Modrinth project 
     * @return List of supported Minecraft versions 
     */
    private Mono<List<String>> resolveVersions(ModrinthApiClient client, ProjectRef ref) {
        final Loader loader = ref.getLoader() != null ? ref.getLoader() : this.loader;
        final VersionType allowedVersionType = ref.hasVersionType() ? ref.getVersionType() : defaultVersionType;

        return client.resolveProjectGameVersions(ref, loader, null, allowedVersionType);
    }

    /**
     * Calculates the highest compatible Minecraft release across the projects.
     *
     * @param officialReleases Minecraft releases
     * @param refs Required Modrinth projects
     * @param versions List of supported Versions for each project
     * @return Compatible Minecraft release if supported, or an empty result if no compatible release
     */
    private Mono<String> calculateVersion(List<VersionManifestV2.Version> officialReleases, List<ProjectRef> refs, List<List<String>> versions) {
        final Map<String, Set<Integer>> versionMatrix = buildVersionMatrix(versions);
        final String version = findHighestCompleteRelease(officialReleases, refs, versionMatrix);

        return Mono.justOrEmpty(version);
    }

    /**
     * Resolves project versions and calculates the highest compatible Minecraft release.
     *
     * @param modrinthApiClient Modrinth API client
     * @param officialReleases Minecraft releases
     * @return Result containing compatible Minecraft release
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
                return calculateVersion(officialReleases, refs, allProjectVersions);
            });
    }


    /**
     * Builds matrix of releases against projects.
     *
     * @param versionMatrix List of versions supported per project
     * @return Map of Minecraft releases against indexs of modrinth projects.
     */
    private Map<String, Set<Integer>> buildVersionMatrix(List<List<String>> versionMatrix) {
        final Map<String, Set<Integer>> supportedByVersion = new HashMap<>();

        for (int projectIndex = 0; projectIndex < versionMatrix.size(); projectIndex++) {
            for (String gameVersion : new HashSet<>(versionMatrix.get(projectIndex))) {
                supportedByVersion
                    .computeIfAbsent(gameVersion, ignored -> new HashSet<>())
                    .add(projectIndex);
            }
        }

        return supportedByVersion;
    }


    /**
     * Finds the first Minecraft release supported by all required projects.
     *
     * @param officialReleases Minecraft releases
     * @param refs Required Modrinth projects
     * @param versionMatrix List of versions supported per project
     * @return the compatible release ID, or {@code null} if no release is supported
     *         by every project
     */
    private String findHighestCompleteRelease(
        List<VersionManifestV2.Version> officialReleases,
        List<ProjectRef> refs,
        Map<String, Set<Integer>> versionMatrix
    ) {
        for (VersionManifestV2.Version release : officialReleases) {
            final Set<Integer> supportedProjects = versionMatrix.getOrDefault(release.getId(), Collections.emptySet());
            final List<String> missingProjects = missingProjectNames(refs, supportedProjects);

            if (missingProjects.isEmpty()) {
                log.debug("{}: {}/{} projects",
                    release.getId(),
                    supportedProjects.size(),
                    refs.size()
                );

                return release.getId();
            }

            log.debug("{}: {}/{} projects; missing: {}",
                release.getId(),
                supportedProjects.size(),
                refs.size(),
                String.join(", ", missingProjects)
            );
        }

        return null;
    }

    /**
     * Finds the project names missing support for a Minecraft release.
     *
     * @param refs Required Modrinth projects
     * @param supportedProjects the indexes of projects supporting the release
     * @return Projects not in {@code supportedProjects}
     */
    private List<String> missingProjectNames(List<ProjectRef> refs, Set<Integer> supportedProjects) {
        final List<String> missingProjects = new ArrayList<>();
        for (int projectIndex = 0; projectIndex < refs.size(); projectIndex++) {
            if (!supportedProjects.contains(projectIndex)) {
                missingProjects.add(refs.get(projectIndex).getIdOrSlug());
            }
        }
        return missingProjects;
    }
}