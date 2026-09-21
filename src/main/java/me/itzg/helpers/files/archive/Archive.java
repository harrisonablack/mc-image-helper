package me.itzg.helpers.files.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.compress.archivers.ArchiveException;

public interface Archive {
    Path extract(Path destination, boolean overwrite) throws IOException, ArchiveException;

    Path extract(Path destination, List<String> files, boolean overwrite) throws IOException, ArchiveException;

    boolean containsPathTraversal() throws IOException;

}