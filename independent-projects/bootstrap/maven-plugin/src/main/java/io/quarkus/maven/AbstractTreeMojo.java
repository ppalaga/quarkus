package io.quarkus.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;

public class AbstractTreeMojo extends AbstractMojo {
    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    @Component
    protected RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of artifacts and their dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    protected List<RemoteRepository> repos;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Artifact artifact = project.getArtifact();
        final AppArtifact appArtifact = new AppArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                artifact.getClassifier(), artifact.getArtifactHandler().getExtension(), artifact.getVersion());
        final List<AppDependency> managedDeps;
        try {
            final List<Dependency> mDeps = repoSystem.readArtifactDescriptor(repoSession,
                    new ArtifactDescriptorRequest()
                            .setArtifact(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                                    artifact.getClassifier(), artifact.getType(), artifact.getVersion()))
                            .setRepositories(repos)).getManagedDependencies();
            managedDeps = new ArrayList<>(mDeps.size());
            for(Dependency dep : mDeps) {
                managedDeps.add(new AppDependency(new AppArtifact(
                        dep.getArtifact().getGroupId(),
                        dep.getArtifact().getArtifactId(),
                        dep.getArtifact().getClassifier(),
                        dep.getArtifact().getExtension(),
                        dep.getArtifact().getVersion()), dep.getScope(), dep.isOptional()));
            }
        } catch (ArtifactDescriptorException e) {
            throw new MojoFailureException("Failed to descriptor of " + artifact, e);
        }
        //final AppArtifact appArtifact = new AppArtifact(project.getGroupId(), project.getArtifactId(), project.getVersion());
        final BootstrapAppModelResolver modelResolver;
        try {
            modelResolver = new BootstrapAppModelResolver(
                    MavenArtifactResolver.builder()
                            .setRepositorySystem(repoSystem)
                            .setRepositorySystemSession(repoSession)
                            .setRemoteRepositories(repos)
                            .build());
            setupResolver(modelResolver);
            modelResolver.setBuildTreeLogger(s -> getLog().info(s));
            modelResolver.resolveManagedModel(appArtifact, Collections.emptyList(), managedDeps);
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to resolve application model " + appArtifact + " dependencies", e);
        }
    }

    protected void setupResolver(BootstrapAppModelResolver modelResolver) {
    }
}
