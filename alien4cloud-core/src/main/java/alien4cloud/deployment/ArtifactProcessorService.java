package alien4cloud.deployment;

import static alien4cloud.utils.AlienUtils.safe;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Resource;

import org.alien4cloud.tosca.editor.EditorRepositoryService;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.component.repository.CsarFileRepository;
import alien4cloud.component.repository.IFileRepository;
import alien4cloud.component.repository.exception.CSARVersionNotFoundException;
import alien4cloud.deployment.exceptions.UnresolvableArtifactException;
import alien4cloud.model.components.AbstractArtifact;
import alien4cloud.model.components.DeploymentArtifact;
import alien4cloud.model.components.Interface;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import alien4cloud.repository.services.RepositoryService;
import alien4cloud.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Download all artifacts before deployment
 */
@Slf4j
@Component
public class ArtifactProcessorService {

    @Resource
    private RepositoryService repositoryService;

    @Resource
    private CsarFileRepository repository;

    @Resource
    private EditorRepositoryService editorRepositoryService;

    @Resource
    private IFileRepository artifactRepository;

    private Path tempDir;

    private Path resolveArtifact(AbstractArtifact artifact) {
        return repositoryService.resolveArtifact(artifact.getArtifactRef(), artifact.getRepositoryURL(), artifact.getArtifactRepository(),
                artifact.getRepositoryCredential());
    }

    private void processLocalArtifact(AbstractArtifact artifact) {
        try {
            Path csarPath = repository.getExpandedCSAR(artifact.getArchiveName(), artifact.getArchiveVersion());
            artifact.setArtifactPath(csarPath.resolve(artifact.getArtifactRef()));
            if (!Files.exists(artifact.getArtifactPath())) {
                throw new UnresolvableArtifactException("Artifact could not be accessed " + artifact);
            }
        } catch (CSARVersionNotFoundException e) {
            throw new UnresolvableArtifactException("Artifact could not be found " + artifact, e);
        }
    }

    private void processArtifact(AbstractArtifact artifact) {
        if (ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY.equals(artifact.getArtifactRepository())) {
            artifact.setArtifactPath(artifactRepository.resolveFile(artifact.getArtifactRef()));
            return;
        }
        URL artifactURL = null;
        if (artifact.getRepositoryName() == null) {
            // Short notation
            try {
                // Test if it's an URL
                artifactURL = new URL(artifact.getArtifactRef());
            } catch (MalformedURLException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Processing local artifact {}", artifact);
                }
                // Not a URL then must be a relative path to a file inside the csar
                processLocalArtifact(artifact);
                return;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Processing remote artifact {}", artifact);
        }
        Path artifactPath = resolveArtifact(artifact);
        if (artifactPath == null) {
            if (artifactURL != null) {
                try (InputStream artifactStream = artifactURL.openStream()) {
                    // In a best effort try in a generic manner to obtain the artifact
                    artifactPath = Files.createTempFile(tempDir, "url-artifact", FilenameUtils.getExtension(artifact.getArtifactRef()));
                    Files.copy(artifactStream, artifactPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UnresolvableArtifactException("Artifact could not be found " + artifact, e);
                }
            } else {
                throw new UnresolvableArtifactException("Artifact could not be found " + artifact);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Remote artifact from {} resolved to {}", artifact.getArtifactRef(), artifactPath);
        }
        artifact.setArtifactPath(artifactPath);
        artifact.setArtifactRef(artifactPath.getFileName().toString());
    }

    private void processInterfaces(Map<String, Interface> interfaceMap) {
        if (interfaceMap != null) {
            interfaceMap.values().stream().filter(interfazz -> interfazz.getOperations() != null)
                    .forEach(interfazz -> interfazz.getOperations().values().stream().filter(operation -> operation.getImplementationArtifact() != null)
                            .forEach(operation -> processArtifact(operation.getImplementationArtifact())));
        }
    }

    private void processImplementationArtifacts(PaaSTopologyDeploymentContext deploymentContext) {
        if (deploymentContext.getPaaSTopology().getAllNodes() != null) {
            for (PaaSNodeTemplate paaSNodeTemplate : deploymentContext.getPaaSTopology().getAllNodes().values()) {
                processInterfaces(paaSNodeTemplate.getInterfaces());
                if (paaSNodeTemplate.getRelationshipTemplates() != null) {
                    for (PaaSRelationshipTemplate relationshipTemplate : paaSNodeTemplate.getRelationshipTemplates()) {
                        processInterfaces(relationshipTemplate.getInterfaces());
                    }
                }
            }
        }
    }

    private Stream<DeploymentArtifact> getDeploymentArtifactStream(PaaSTopologyDeploymentContext deploymentContext) {
        return Stream.concat(
                safe(deploymentContext.getDeploymentTopology().getNodeTemplates()).values().stream()
                        .flatMap(nodeTemplate -> safe(nodeTemplate.getArtifacts()).values().stream()),
                safe(deploymentContext.getDeploymentTopology().getNodeTemplates()).values().stream()
                        .flatMap(nodeTemplate -> safe(nodeTemplate.getRelationships()).values().stream())
                        .flatMap(relationship -> safe(relationship.getArtifacts()).values().stream()));
    }

    private void processDeploymentArtifacts(PaaSTopologyDeploymentContext deploymentContext) {
        if (deploymentContext.getDeploymentTopology().getNodeTemplates() != null) {
            // Artifact which comes from the archive
            getDeploymentArtifactStream(deploymentContext).filter(deploymentArtifact -> StringUtils.isNotBlank(deploymentArtifact.getArchiveName()))
                    .forEach(this::processArtifact);
            // Artifact which does not come from the archive, which comes from topology's edition
            getDeploymentArtifactStream(deploymentContext).filter(deploymentArtifact -> StringUtils.isBlank(deploymentArtifact.getArchiveName()))
                    .forEach(deploymentArtifact -> {
                        Path artifactPath = editorRepositoryService.resolveArtifact(deploymentContext.getDeploymentTopology().getInitialTopologyId(),
                                deploymentArtifact.getArtifactRef());
                        deploymentArtifact.setArtifactPath(artifactPath);
                    });
        }
    }

    public void processArtifacts(PaaSTopologyDeploymentContext deploymentContext) {
        processImplementationArtifacts(deploymentContext);
        processDeploymentArtifacts(deploymentContext);
    }

    @Value("${directories.alien}/${directories.upload_temp}")
    public void setTempDir(String tempDir) throws IOException {
        this.tempDir = FileUtil.createDirectoryIfNotExists(tempDir);
    }
}
