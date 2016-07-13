package alien4cloud.it;

import cucumber.api.CucumberOptions;
import cucumber.api.junit.Cucumber;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(features = {
        //
        // "classpath:alien/rest/csars"
        // "classpath:alien/rest/csars/csar_crud.feature",
        "classpath:alien/rest/csars/delete.feature",
        // "classpath:alien/rest/csars/upload.feature",
        // "classpath:alien/rest/csars/upload_topology.feature",
        // "classpath:alien/rest/csars/upload_rights.feature",
        // "classpath:alien/rest/csars/git.feature"
        // "classpath:alien/rest/csars/override_deployed_csar.feature"
}, format = { "pretty", "html:target/cucumber/csars", "json:target/cucumber/cucumber-csars.json" })
// @Ignore
public class RunCsarIT {
}