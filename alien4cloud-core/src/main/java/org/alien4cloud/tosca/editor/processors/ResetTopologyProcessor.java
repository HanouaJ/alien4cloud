package org.alien4cloud.tosca.editor.processors;

import alien4cloud.model.topology.Topology;
import alien4cloud.paas.wf.WorkflowsBuilderService;
import alien4cloud.topology.TopologyService;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.tosca.editor.EditionContextManager;
import org.alien4cloud.tosca.editor.EditorService;
import org.alien4cloud.tosca.editor.EditorTopologyRecoveryHelperService;
import org.alien4cloud.tosca.editor.operations.ResetTopologyOperation;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.IOException;

/**
 * process {@link ResetTopologyOperation}
 * This will delete everything inside the topology, leaving it as if it is just created now.
 */
@Slf4j
@Component
public class ResetTopologyProcessor implements IEditorOperationProcessor<ResetTopologyOperation> {

    @Inject
    private EditorTopologyRecoveryHelperService editorTopologyRecoveryHelperService;
    @Inject
    private TopologyService topologyService;
    private EditorService editorService;
    @Inject
    private WorkflowsBuilderService workflowBuilderService;

    @Override
    public void process(ResetTopologyOperation operation) {
        Topology topology = EditionContextManager.getTopology();
        Topology newTopology = new Topology();
        newTopology.setDelegateId(topology.getDelegateId());
        newTopology.setDelegateType(topology.getDelegateType());
        newTopology.setId(topology.getId());
        newTopology.setYamlFilePath(topology.getYamlFilePath());
        workflowBuilderService.initWorkflows(workflowBuilderService.buildTopologyContext(newTopology));
        try {
            EditionContextManager.get().reset(newTopology);
        } catch (IOException e) {
            // FIXME what to do here????
            log.error("Error occurs when trying to reset the topology <" + topology.getId() + ">", e);
        }
    }
}
