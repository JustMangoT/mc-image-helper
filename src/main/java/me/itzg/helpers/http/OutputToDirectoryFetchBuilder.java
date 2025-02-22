package me.itzg.helpers.http;

import static java.util.Objects.requireNonNull;

import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

@Slf4j
@Accessors(fluent = true)
public class OutputToDirectoryFetchBuilder extends FetchBuilderBase<OutputToDirectoryFetchBuilder> {

    private final Path outputDirectory;
    @Setter
    private boolean skipExisting;
    private FileDownloadStatusHandler statusHandler = (status, uri, file) -> {};
    private FileDownloadedHandler downloadedHandler = (uri, file, contentSizeBytes) -> {};

    protected OutputToDirectoryFetchBuilder(State state, Path outputDirectory) {
        super(state);

        if (!Files.isDirectory(outputDirectory)) {
            throw new IllegalArgumentException(outputDirectory + " is not a directory or does not exist");
        }
        this.outputDirectory = outputDirectory;
    }

    @SuppressWarnings("unused")
    public OutputToDirectoryFetchBuilder handleStatus(FileDownloadStatusHandler statusHandler) {
        requireNonNull(statusHandler);
        this.statusHandler = statusHandler;
        return self();
    }

    @SuppressWarnings("unused")
    public OutputToDirectoryFetchBuilder handleDownloaded(FileDownloadedHandler downloadedHandler) {
        requireNonNull(downloadedHandler);
        this.downloadedHandler = downloadedHandler;
        return self();
    }

    public Path execute() throws IOException {
        return assemble()
            .block();
    }

    public Mono<Path> assemble() {
        return useReactiveClient(client ->
            client
                .followRedirect(true)
                .doOnRequest(debugLogRequest(log, "file head fetch"))
                .head()
                .uri(uri())
                .response()
                .flatMap(resp ->
                    notSuccess(resp) ? failedRequestMono(resp, "Extracting filename")
                        : Mono.just(outputDirectory.resolve(extractFilename(resp)))
                    )
                .flatMap(outputFile ->
                    assembleFileDownload(client, outputFile)
                )
        );
    }

    private Mono<Path> assembleFileDownload(HttpClient client, Path outputFile) {
        if (skipExisting && Files.exists(outputFile)) {
            log.debug("File {} already exists", outputFile);
            statusHandler.call(FileDownloadStatus.SKIP_FILE_EXISTS, uri(), outputFile);
            return Mono.just(outputFile);
        }

        return client
            .doOnRequest((httpClientRequest, connection) -> statusHandler.call(FileDownloadStatus.DOWNLOADING, uri(), null))
            .followRedirect(true)
            .doOnRequest(debugLogRequest(log, "file fetch"))
            .get()
            .uri(uri())
            .responseSingle((resp, byteBufMono) -> {
                if (notSuccess(resp)) {
                    return failedRequestMono(resp, "Downloading file");
                }

                return byteBufMono.asInputStream()
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(inputStream -> {
                        try {
                            @SuppressWarnings("BlockingMethodInNonBlockingContext") // false warning, see above
                            final long size = Files.copy(inputStream, outputFile, StandardCopyOption.REPLACE_EXISTING);
                            statusHandler.call(FileDownloadStatus.DOWNLOADED, uri(), outputFile);
                            downloadedHandler.call(uri(), outputFile, size);
                            return Mono.just(outputFile);
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                    });
            });
    }

    private String extractFilename(HttpClientResponse resp) {
        final String contentDisposition = resp.responseHeaders().get(HttpHeaderNames.CONTENT_DISPOSITION);
        final String dispositionFilename = FilenameExtractor.filenameFromContentDisposition(contentDisposition);
        if (dispositionFilename != null) {
            return dispositionFilename;
        }
        if (resp.redirectedFrom().length > 0) {
            final String lastUrl = resp.redirectedFrom()[resp.redirectedFrom().length - 1];
            final int pos = lastUrl.lastIndexOf('/');
            return lastUrl.substring(pos + 1);
        }
        final int pos = resp.path().lastIndexOf('/');
        return resp.path().substring(pos + 1);
    }

}
