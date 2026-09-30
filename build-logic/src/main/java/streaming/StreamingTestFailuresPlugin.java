package streaming;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.util.List;

public class StreamingTestFailuresPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        Provider<FailureStreamService> service = registerService(project);

        project.getPlugins().withType(JavaPlugin.class, ignored ->
                project.getTasks().withType(Test.class).configureEach(test -> {
                    test.usesService(service);
                    test.addTestListener(new StreamingFailureListener(service, sourceLocatorFor(project), test.getPath()));
                }));
    }

    private static Provider<FailureStreamService> registerService(Project project) {
        ProviderFactory providers = project.getProviders();

        return project.getGradle().getSharedServices().registerIfAbsent("streamingTestFailures", FailureStreamService.class, spec -> {
            FailureStreamService.Params parameters = spec.getParameters();
            parameters.getMode().set(setting(providers, "streamingFailures.mode", "STREAMING_FAILURES_MODE").orElse("auto"));
            parameters.getFlushIntervalSeconds().set(setting(providers, "streamingFailures.flushIntervalSeconds", "STREAMING_FAILURES_FLUSH_SECONDS").map(Integer::parseInt).orElse(15));
            parameters.getCheckRunId().set(providers.environmentVariable("CHECK_RUN_ID"));
            parameters.getToken().set(providers.environmentVariable("GITHUB_TOKEN"));
            parameters.getRepository().set(providers.environmentVariable("GITHUB_REPOSITORY"));
            parameters.getApiUrl().set(providers.environmentVariable("GITHUB_API_URL").orElse("https://api.github.com"));
            parameters.getRunningInActions().set(providers.environmentVariable("GITHUB_ACTIONS").map(Boolean::parseBoolean).orElse(false));
        });
    }

    private static Provider<String> setting(ProviderFactory providers, String gradleProperty, String environmentVariable) {
        return providers.gradleProperty(gradleProperty).orElse(providers.environmentVariable(environmentVariable));
    }

    private static SourceLocator sourceLocatorFor(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        List<File> sourceDirectories = sourceSets.stream()
                .flatMap(sourceSet -> sourceSet.getAllSource().getSrcDirs().stream())
                .toList();

        return new SourceLocator(project.getRootDir(), sourceDirectories, project.getBuildFile());
    }
}
