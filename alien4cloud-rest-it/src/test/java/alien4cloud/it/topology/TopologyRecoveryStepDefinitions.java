package alien4cloud.it.topology;

import static org.junit.Assert.assertFalse;

import alien4cloud.it.Context;
import alien4cloud.it.common.CommonStepDefinitions;
import alien4cloud.model.components.CSARDependency;
import alien4cloud.model.topology.NodeTemplate;
import alien4cloud.rest.model.RestResponse;
import alien4cloud.rest.utils.JsonUtil;
import alien4cloud.topology.TopologyDTO;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import java.io.IOException;
import java.util.List;
import org.junit.Assert;

public class TopologyRecoveryStepDefinitions {

    private CommonStepDefinitions commonSteps = new CommonStepDefinitions();

    @When("^I ask for updated dependencies from the registered topology$")
    public void I_ask_for_updated_dependencies_from_the_registered_topology() throws Throwable {
        Context.getInstance().registerRestResponse(
                Context.getRestClientInstance().get("/rest/v1/topologies/" + Context.getInstance().getTopologyId() + "/updatedDependencies"));
    }

    @When("^I trigger the recovery of the topology$")
    public void I_trigger_the_recovery_of_the_topology() throws Throwable {
        RestResponse<?> response = JsonUtil.read(Context.getInstance().getRestResponse());
        List<CSARDependency> dependencies = JsonUtil.toList(JsonUtil.toString(response.getData()), CSARDependency.class);
        Context.getInstance().registerRestResponse(Context.getRestClientInstance()
                .putJSon("/rest/v1/topologies/" + Context.getInstance().getTopologyId() + "/recover", JsonUtil.toString(dependencies)));
    }

    @When("^I reset the topology$")
    public void I_reset_the_topology() throws Throwable {
        Context.getInstance()
                .registerRestResponse(Context.getRestClientInstance().put("/rest/v1/topologies/" + Context.getInstance().getTopologyId() + "/reset"));
    }

    @Then("^The Response should contain the folowwing dependencies$")
    public void I_ask_for_updated_dependencies_from_the_registered_topology(List<CSARDependency> expectedDependencies) throws Throwable {
        RestResponse<?> response = JsonUtil.read(Context.getInstance().getRestResponse());
        List<CSARDependency> dependencies = JsonUtil.toList(JsonUtil.toString(response.getData()), CSARDependency.class);
        Assert.assertTrue(dependencies.containsAll(expectedDependencies));
    }

    @Then("^the topology dto should contain (\\d+) nodetemplates$")
    public void the_topology_dto_should_contain_nodetemplates(int count) throws Throwable {
        TopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), TopologyDTO.class, Context.getJsonMapper()).getData();
        Assert.assertEquals(count, dto.getTopology().getNodeTemplates().size());
    }

    @Then("^the topology dto should contain an emty topology$")
    public void the_topology_dto_should_contain_an_emty_topology() throws Throwable {
        TopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), TopologyDTO.class, Context.getJsonMapper()).getData();
        Assert.assertTrue(dto.getTopology().isEmpty());
    }

    @Then("^the node \"([^\"]*)\" in the topology dto should have (\\d+) relationshipTemplates$")
    public void the_node_in_the_topology_dto_should_have_relationshiptemplates(String nodeName, int count) throws Throwable {
        TopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), TopologyDTO.class, Context.getJsonMapper()).getData();
        NodeTemplate template = dto.getTopology().getNodeTemplates().get(nodeName);
        Assert.assertNotNull(template);
        Assert.assertEquals(count, template.getRelationships().size());
    }

    @Then("^there should not be the relationship \"([^\"]*)\" in \"([^\"]*)\" node template in the topology dto$")
    public void there_should_not_be_the_relationship_in_node_template_in_the_topology_dto(String relName, String nodeName) throws IOException {
        TopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), TopologyDTO.class, Context.getJsonMapper()).getData();
        NodeTemplate template = dto.getTopology().getNodeTemplates().get(nodeName);
        Assert.assertNotNull(template);
        assertFalse(template.getRelationships().containsKey(relName));
    }

    @Then("^the node \"([^\"]*)\" in the topology dto should not have the capability \"([^\"]*)\"$")
    public void I_node_in_the_topology_dto_should_not_have_the_capability(String nodeName, String capabilityName) throws IOException {
        TopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), TopologyDTO.class, Context.getJsonMapper()).getData();
        NodeTemplate template = dto.getTopology().getNodeTemplates().get(nodeName);
        Assert.assertNotNull(template);
        assertFalse(template.getCapabilities().containsKey(capabilityName));
    }

    @Then("^the node \"([^\"]*)\" in the topology dto should not have the requirement \"([^\"]*)\"$")
    public void I_node_in_the_topology_dto_should_not_have_the_requirement(String nodeName, String requirementName) throws IOException {
        TopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), TopologyDTO.class, Context.getJsonMapper()).getData();
        NodeTemplate template = dto.getTopology().getNodeTemplates().get(nodeName);
        Assert.assertNotNull(template);
        assertFalse(template.getRequirements().containsKey(requirementName));
    }
}
