package io.dockstore.webservice.languages;

import io.dockstore.common.VersionTypeValidation;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.ToolDAO;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BinderHandler implements LanguageHandlerInterface {

    @Override
    public Version parseWorkflowContent(String filepath, String content, Set<SourceFile> sourceFiles, Version version) {
        return version;
    }

    @Override
    public VersionTypeValidation validateWorkflowSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        return new VersionTypeValidation(true, Collections.emptyMap());
    }

    @Override
    public VersionTypeValidation validateToolSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        return new VersionTypeValidation(true, Collections.emptyMap());
    }

    @Override
    public VersionTypeValidation validateTestParameterSet(Set<SourceFile> sourceFiles) {
        return new VersionTypeValidation(true, Collections.emptyMap());
    }

    @Override
    public Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
            SourceCodeRepoInterface sourceCodeRepoInterface, String filepath) {
        return Collections.emptyMap();
    }

    @Override
    public Optional<String> getContent(String mainDescriptorPath, String mainDescriptor,
            Set<SourceFile> secondarySourceFiles, Type type, ToolDAO dao) {
        return Optional.of("[]");
    }
}
