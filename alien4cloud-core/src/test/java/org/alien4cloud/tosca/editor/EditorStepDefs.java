package org.alien4cloud.tosca.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.alien4cloud.tosca.editor.operations.AbstractEditorOperation;
import org.alien4cloud.tosca.editor.operations.UpdateFileOperation;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.Assert;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.model.components.*;
import alien4cloud.model.templates.TopologyTemplate;
import alien4cloud.model.topology.Topology;
import alien4cloud.paas.wf.WorkflowsBuilderService;
import alien4cloud.security.model.User;
import alien4cloud.topology.TopologyDTO;
import alien4cloud.topology.TopologyServiceCore;
import alien4cloud.tosca.ArchiveUploadService;
import alien4cloud.tosca.parser.ParsingErrorLevel;
import alien4cloud.tosca.parser.ParsingResult;
import alien4cloud.utils.FileUtil;
import cucumber.api.DataTable;
import cucumber.api.java.Before;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import gherkin.formatter.model.DataTableRow;
import lombok.extern.slf4j.Slf4j;

@ContextConfiguration("classpath:org/alien4cloud/tosca/editor/application-context-test.xml")
@Slf4j
public class EditorStepDefs {
    @Resource
    private ArchiveUploadService csarUploadService;

    @Resource
    private EditorService editorService;

    @Inject
    private EditionContextManager editionContextManager;

    @Resource
    private TopologyServiceCore topologyServiceCore;

    @Resource
    private WorkflowsBuilderService workflowBuilderService;

    @Resource
    private EditorTopologyRecoveryHelperService recoveryHelper;

    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;

    private LinkedList<String> topologyIds = new LinkedList();

    private EvaluationContext topologyEvaluationContext;
    private EvaluationContext dtoEvaluationContext;
    private EvaluationContext exceptionEvaluationContext;

    private Exception thrownException;

    private Map<String, String> topologyIdToLastOperationId = new HashMap<>();

    private List<Class> typesToClean = Lists.newArrayList();
    public static final Path CSAR_TARGET_PATH = Paths.get("target/csars");

    public EditorStepDefs() {
        super();
        typesToClean.add(IndexedArtifactToscaElement.class);
        typesToClean.add(IndexedToscaElement.class);
        typesToClean.add(IndexedCapabilityType.class);
        typesToClean.add(IndexedArtifactType.class);
        typesToClean.add(IndexedRelationshipType.class);
        typesToClean.add(IndexedNodeType.class);
        typesToClean.add(IndexedDataType.class);
        typesToClean.add(PrimitiveIndexedDataType.class);
        typesToClean.add(Csar.class);
    }

    @Before
    public void init() throws IOException {
        thrownException = null;
    }

    @Given("^I am authenticated with \"(.*?)\" role$")
    public void i_am_authenticated_with_role(String role) throws Throwable {
        User user = new User();
        user.setUsername("Username");
        user.setFirstName("firstName");
        user.setLastName("lastname");
        user.setEmail("user@fastco");
        Authentication auth = new TestAuth(user, role);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static class TestAuth extends UsernamePasswordAuthenticationToken {
        Collection<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();

        public TestAuth(User user, String role) {
            super(user, null);
            authorities.add(new SimpleGrantedAuthority(role));
        }

        @Override
        public Collection<GrantedAuthority> getAuthorities() {
            return authorities;
        }
    }

    @Given("^I upload CSAR from path \"(.*?)\"$")
    public void i_upload_CSAR_from_path(String path) throws Throwable {
        uploadCsar(Paths.get(path));
    }

    @When("^I upload unzipped CSAR from path \"(.*?)\"$")
    public void i_upload_unzipped_CSAR_From_path(String path) throws Throwable {
        Path source = Paths.get(path);
        Path csarTargetPath = CSAR_TARGET_PATH.resolve(source.getFileName() + ".csar");
        FileUtil.zip(source, csarTargetPath);
        uploadCsar(csarTargetPath);
    }

    private void uploadCsar(Path path) throws Throwable {
        ParsingResult<Csar> result = csarUploadService.upload(path, CSARSource.UPLOAD);
        Assert.assertFalse(result.hasError(ParsingErrorLevel.ERROR));
        if (result.getContext().getParsingErrors().size() > 0) {
            System.out.println(result);
        }
    }

    @Given("^I cleanup archives$")
    public void i_cleanup_archives() throws Throwable {
        for (Class<?> type : typesToClean) {
            alienDAO.delete(type, QueryBuilders.matchAllQuery());
        }
    }

    @When("^I get the topology related to the template with name \"(.*?)\"$")
    public void iGetTheTopologyRelatedToTheTemplateWithName(String templateName) throws Throwable {
        TopologyTemplate topologyTeplate = topologyServiceCore.searchTopologyTemplateByName(templateName);
        Topology topology = alienDAO.customFind(Topology.class, QueryBuilders.matchQuery("delegateId", topologyTeplate.getId()));
        topologyIds.addLast(topology.getId());
    }

    @When("^I get the edited topology$")
    public void I_get_the_edited_topology() {
        thrownException = null;
        try {
            editionContextManager.init(topologyIds.getLast());
            Topology topology = editionContextManager.getTopology();
            topologyEvaluationContext = new StandardEvaluationContext(topology);
        } catch (Exception e) {
            log.error("Exception ocrured while getting the topology", e);
            thrownException = e;
            exceptionEvaluationContext = new StandardEvaluationContext(e);
        } finally {
            editionContextManager.destroy();
        }
    }

    @Given("^I create an empty topology$")
    public void i_create_an_empty_topology() throws Throwable {
        Topology topology = new Topology();
        topology.setDelegateType(Topology.class.getSimpleName().toLowerCase());
        workflowBuilderService.initWorkflows(workflowBuilderService.buildTopologyContext(topology));
        topologyIds.addLast(topologyServiceCore.saveTopology(topology));
    }

    @Given("^I create an empty topology template \"(.*?)\"$")
    public void i_create_an_empty_topology_template(String topologyTemplateName) throws Throwable {
        Topology topology = new Topology();
        topology.setDelegateType(TopologyTemplate.class.getSimpleName().toLowerCase());
        workflowBuilderService.initWorkflows(workflowBuilderService.buildTopologyContext(topology));
        TopologyTemplate topologyTemplate = topologyServiceCore.createTopologyTemplate(topology, topologyTemplateName, "", null);
        topology.setDelegateId(topologyTemplate.getId());
        topologyIds.addLast(topology.getId());

    }

    @Given("^I execute the operation on the topology number (\\d+)$")
    public void i_execute_the_operation_on_topology_number(int indexOfTopologyId, DataTable operationDT) throws Throwable {
        Map<String, String> operationMap = Maps.newHashMap();
        for (DataTableRow row : operationDT.getGherkinRows()) {
            operationMap.put(row.getCells().get(0), row.getCells().get(1));
        }

        Class operationClass = Class.forName(operationMap.get("type"));
        AbstractEditorOperation operation = (AbstractEditorOperation) operationClass.newInstance();
        EvaluationContext operationContext = new StandardEvaluationContext(operation);
        SpelParserConfiguration config = new SpelParserConfiguration(true, true);
        SpelExpressionParser parser = new SpelExpressionParser(config);
        for (Map.Entry<String, String> operationEntry : operationMap.entrySet()) {
            if (!"type".equals(operationEntry.getKey())) {
                parser.parseRaw(operationEntry.getKey()).setValue(operationContext, operationEntry.getValue());
            }
        }
        doExecuteOperation(operation, topologyIds.get(indexOfTopologyId));
    }

    @Given("^I execute the operation$")
    public void i_execute_the_operation(DataTable operationDT) throws Throwable {
        i_execute_the_operation_on_topology_number(topologyIds.size() - 1, operationDT);
    }

    @Given("^I save the topology$")
    public void i_save_the_topology() throws Throwable {
        editorService.save(topologyIds.getLast(), topologyIdToLastOperationId.get(topologyIds.getLast()));
        topologyIdToLastOperationId.put(topologyIds.getLast(), null);
    }

    @Given("^I upload a file located at \"(.*?)\" to the archive path \"(.*?)\"$")
    public void i_upload_a_file_located_at_to_the_archive_path(String filePath, String archiveTargetPath) throws Throwable {
        UpdateFileOperation updateFileOperation = new UpdateFileOperation(archiveTargetPath, Files.newInputStream(Paths.get(filePath)));
        doExecuteOperation(updateFileOperation);
    }

    private void doExecuteOperation(AbstractEditorOperation operation, String topologyId) {
        thrownException = null;
        operation.setPreviousOperationId(topologyIdToLastOperationId.get(topologyId));
        try {
            TopologyDTO topologyDTO = editorService.execute(topologyId, operation);
            String lastOperationId = topologyDTO.getOperations().get(topologyDTO.getLastOperationIndex()).getId();
            topologyIdToLastOperationId.put(topologyId, lastOperationId);
            topologyEvaluationContext = new StandardEvaluationContext(topologyDTO.getTopology());
            dtoEvaluationContext = new StandardEvaluationContext(topologyDTO);
        } catch (Exception e) {
            log.error("Exception occurred while executing operation", e);
            thrownException = e;
            exceptionEvaluationContext = new StandardEvaluationContext(e);
        }
    }

    private void doExecuteOperation(AbstractEditorOperation operation) {
        doExecuteOperation(operation, topologyIds.getLast());
    }

    private Object evaluateExpression(EvaluationContext context, String spelExpression) {
        ExpressionParser parser = new SpelExpressionParser();
        Expression exp = parser.parseExpression(spelExpression);
        return exp.getValue(context);
    }

    @Then("^The SPEL expression \"([^\"]*)\" should return \"([^\"]*)\"$")
    public void evaluateSpelExpressionUsingCurrentTopologyContext(String spelExpression, String expected) {
        Object result = evaluateExpression(topologyEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The SPEL expression \"([^\"]*)\" should return (true|false)$")
    public void evaluateSpelExpressionUsingCurrentTopologyContext(String spelExpression, Boolean expected) {
        Object result = evaluateExpression(topologyEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The SPEL expression \"([^\"]*)\" should return (\\d+)$")
    public void evaluateSpelExpressionUsingCurrentTopologyContext(String spelExpression, Integer expected) {
        Object result = evaluateExpression(topologyEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The dto SPEL expression \"([^\"]*)\" should return \"([^\"]*)\"$")
    public void evaluateSpelExpressionUsingCurrentDTOContext(String spelExpression, String expected) {
        Object result = evaluateExpression(dtoEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The dto SPEL expression \"([^\"]*)\" should return (true|false)$")
    public void evaluateSpelExpressionUsingCurrentDTOContext(String spelExpression, Boolean expected) {
        Object result = evaluateExpression(dtoEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The dto SPEL expression \"([^\"]*)\" should return (\\d+)$")
    public void evaluateSpelExpressionUsingCurrentDTOContext(String spelExpression, Integer expected) {
        Object result = evaluateExpression(dtoEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The exception SPEL expression \"([^\"]*)\" should return \"([^\"]*)\"$")
    public void evaluateSpelExpressionUsingCurrentExceptionContext(String spelExpression, String expected) {
        Object result = evaluateExpression(exceptionEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The exception SPEL expression \"([^\"]*)\" should return (true|false)$")
    public void evaluateSpelExpressionUsingCurrentExceptionContext(String spelExpression, Boolean expected) {
        Object result = evaluateExpression(exceptionEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    @Then("^The exception SPEL expression \"([^\"]*)\" should return (\\d+)$")
    public void evaluateSpelExpressionUsingCurrentExceptionContext(String spelExpression, Integer expected) {
        Object result = evaluateExpression(exceptionEvaluationContext, spelExpression);
        assertSpelResult(expected, result, spelExpression);
    }

    private void assertSpelResult(Object expected, Object result, String spelExpression) {
        if ("null".equals(expected)) {
            Assert.assertNull(String.format("The SPEL expression [%s] result should be null", spelExpression), result);
        } else {
            Assert.assertNotNull(String.format("The SPEL expression [%s] result should not be null", spelExpression), result);
            Assert.assertEquals(String.format("The SPEL expression [%s] should return [%s]", spelExpression, expected), expected, result);
        }
    }

    @Then("^No exception should be thrown$")
    public void no_exception_should_be_thrown() throws Throwable {
        Assert.assertNull(thrownException);
    }

    @Then("^an exception of type \"(.*?)\" should be thrown$")
    public void an_exception_of_type_should_be_thrown(String exceptionTypesStr) throws Throwable {
        String[] exceptionTypes = exceptionTypesStr.split("/");
        Throwable checkException = thrownException;
        for (String exceptionType : exceptionTypes) {
            Class<?> exceptionClass = Class.forName(exceptionType);
            Assert.assertNotNull(checkException);
            Assert.assertEquals(exceptionClass, checkException.getClass());
            checkException = checkException.getCause();
        }
    }

    // @When("^I ask for updated dependencies from the registered topology$")
    // public void iAskForUpdatedDependenciesFromTheRegisteredTopology() throws Throwable {
    // I_get_the_edited_topology();
    // commonEvaluationContext = new StandardEvaluationContext(
    // recoveryHelper.getUpdatedDependencies((Topology) topologyEvaluationContext.getRootObject().getValue()));
    // }

    @When("^I recover the topology$")
    public void i_Recover_The_Topology() throws Throwable {
        thrownException = null;
        try {
            TopologyDTO dto = editorService.recover(topologyIds.getLast(), topologyIdToLastOperationId.get(topologyIds.getLast()));
            topologyIdToLastOperationId.put(topologyIds.getLast(), null);
            dtoEvaluationContext = new StandardEvaluationContext(dto);
            topologyEvaluationContext = new StandardEvaluationContext(dto.getTopology());
        } catch (Exception e) {
            log.error("Error occurred when recovering the topology", e);
            thrownException = e;
            exceptionEvaluationContext = new StandardEvaluationContext(e);
        }
    }

    @When("^I reset the topology$")
    public void iResetTheTopology() throws Throwable {
        thrownException = null;
        try {
            TopologyDTO dto = editorService.reset(topologyIds.getLast(), topologyIdToLastOperationId.get(topologyIds.getLast()));
            topologyIdToLastOperationId.put(topologyIds.getLast(), null);
            dtoEvaluationContext = new StandardEvaluationContext(dto);
            topologyEvaluationContext = new StandardEvaluationContext(dto.getTopology());
        } catch (Exception e) {
            log.error("Error occurred when resetting the topology", e);
            thrownException = e;
            exceptionEvaluationContext = new StandardEvaluationContext(e);
        }
    }
}