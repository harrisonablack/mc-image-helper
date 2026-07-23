package me.itzg.helpers.curseforge;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import me.itzg.helpers.cache.ApiCachingDisabled;
import me.itzg.helpers.curseforge.model.CurseForgeFile;
import me.itzg.helpers.curseforge.model.CurseForgeMod;
import me.itzg.helpers.http.SharedFetch.Options;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

@WireMockTest
class CurseForgeApiClientTest {

    @Test
    void fallbackUrlEncodesSpaces(@TempDir Path tempDir) throws IOException {

        // Create a file, such that client.download skipsExisting
        // Don't set downloadUrl so client uses fallBackUrl 
        final Path outputFile = Files.createFile(tempDir.resolve("downloaded.jar"));
        final CurseForgeFile file = new CurseForgeFile();
        file.setId(5228909);
        file.setFileName("Butchersdelight beta 1.20.1 2.1.0.jar");

        assertThat(captureFallbackUrl(file, outputFile).getRawPath())
            .isEqualTo("/files/5228/909/Butchersdelight%20beta%201.20.1%202.1.0.jar");
    }

    private URI captureFallbackUrl(CurseForgeFile file, Path outputFile) {
        final AtomicReference<URI> requestedUri = new AtomicReference<>();

        try (CurseForgeApiClient client = new CurseForgeApiClient(
            "https://example.invalid",
            "key",
            Options.builder().build(),
            "432",
            new ApiCachingDisabled()
        )) {
            client.download(
                file, 
                outputFile, 
                // Capture URI from FileDownloadStatusHandler
                (status, uri, path) -> requestedUri.set(uri)
                ).block();
        }

        return requestedUri.get();
    }

    @Test
    void apiKeyHeaderIsTrimmed(WireMockRuntimeInfo wmInfo) {
        @Language("JSON") final String body = "{\"data\": []}";
        stubFor(get("/v1/categories?gameId=test&classesOnly=true")
            .willReturn(aResponse()
                .withBody(body)
                .withHeader("Content-Type", "application/json")
            )
        );

        final CategoryInfo result;
        try (CurseForgeApiClient client = new CurseForgeApiClient(wmInfo.getHttpBaseUrl(), "key\n", Options.builder().build(),
            "test", new ApiCachingDisabled()
        )) {
            result = client.loadCategoryInfo(Collections.singleton("mc-mods"))
                .block();
        }

        assertThat(result).isNotNull();

        verify(getRequestedFor(urlEqualTo("/v1/categories?gameId=test&classesOnly=true"))
            .withHeader("x-api-key", equalTo("key"))
        );
    }

    @Test
    void unknownModDoesNotPreventResolvingOthers(WireMockRuntimeInfo wmInfo) {
        stubFor(get(urlPathEqualTo("/v1/mods/search"))
            .withQueryParam("slug", equalTo("unknown-mod"))
            .willReturn(jsonResponse("{\"data\": []}", 200))
        );
        stubFor(get(urlPathEqualTo("/v1/mods/search"))
            .withQueryParam("slug", equalTo("known-mod"))
            .willReturn(jsonResponse("{\"data\": [{\"id\": 100, \"classId\": 6, \"gameId\": 432}]}", 200))
        );

        try (CurseForgeApiClient client = new CurseForgeApiClient(wmInfo.getHttpBaseUrl(),
            "key", Options.builder().build(), "432", new ApiCachingDisabled()
        )) {
            final Set<Integer> ids = Flux.just("unknown-mod", "known-mod")
                .flatMap(slug -> client.searchMod(slug, 6)
                    .map(CurseForgeMod::getId)
                    .onErrorComplete(UnknownModException.class)
                )
                .collect(Collectors.toSet())
                .block();

            assertThat(ids).containsExactly(100);
        }
    }
}