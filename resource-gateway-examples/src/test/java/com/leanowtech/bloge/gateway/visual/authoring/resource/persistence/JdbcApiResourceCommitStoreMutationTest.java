package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.*;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Mutation-focused JDBC coverage: durable stage, atomic commit, fencing and failure cleanup. */
class JdbcApiResourceCommitStoreMutationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AuthoringScope scope = new AuthoringScope("t", "p", "dev");
    private final CommandKey key = new CommandKey(scope, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k");
    private final String fp = "sha256:" + "1".repeat(64);
    private JdbcTemplate jdbc; private MutableClock clock;

    @BeforeEach void setUp() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:mutation-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); clock = new MutableClock();
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V20260830_001__api_resource_authoring.sql")).execute(ds);
    }

    @Test void stageCommitFindAndFailAreDurableAndFenced() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, key, ExpectedRevision.create());
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        assertThat(store.findHead(scope, "profile")).isEmpty();
        CommandReceipt receipt = receipt(staged);
        assertThat(store.commit(lease, receipt)).isEqualTo(receipt);
        assertThat(store.findHead(scope, "profile")).isPresent();
        assertThat(store.findRevision(scope, "profile", 1)).isPresent();
        assertThatThrownBy(() -> store.fail(lease, CommandFailureCode.INTERNAL)).isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test void expiredLeaseUsesDatabaseTimeAndFailCleansStage() {
        JdbcApiResourceCommitStore store = store(); CommandLease lease = acquire(store, key, ExpectedRevision.create());
        store.stage(lease, "connection", command("one")); clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(() -> store.commit(lease, null)).isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_EXPIRED);
        // A valid takeover fences the old attempt and removes only its staged revision.
        CommandLease newer = acquire(store, key, ExpectedRevision.create());
        assertThat(newer.attemptNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'", Integer.class)).isZero();
    }

    private JdbcApiResourceCommitStore store() { return new JdbcApiResourceCommitStore(jdbc, new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())), JSON, clock, Duration.ofSeconds(1), new ApiResourceDecisions(), (s, r) -> projections(r)); }
    private CommandLease acquire(JdbcApiResourceCommitStore s, CommandKey k, ExpectedRevision e) { return ((ClaimResult.Acquired)s.claim(k, fp, e)).lease(); }
    private static CommandReceipt receipt(StagedApiResource s) { JsonNode b = JSON.createObjectNode().put("result", s.resource().resourceId()); return new CommandReceipt("test.receipt.v1", b, AuthoringFingerprints.of(b), s.strongEtag()); }
    private static ReadyApiResourceProjections projections(ApiResourceSpec r) { return new ReadyApiResourceProjections(doc(ProjectionDocument.Kind.DESCRIPTOR,r),doc(ProjectionDocument.Kind.DESIGN_CONTRACT,r),doc(ProjectionDocument.Kind.OPERATOR,r)); }
    private static ProjectionDocument doc(ProjectionDocument.Kind k, ApiResourceSpec r) { JsonNode b=JSON.createObjectNode().put("kind",k.name()); return new ProjectionDocument(k,r.ref(),b,AuthoringFingerprints.of(b),ProjectionDocument.State.READY); }
    private static ApiResourceCommand command(String n) { Map<String,Object> schema=SchemaEnvelope.object(Map.of("id",Map.of("type","string")),List.of("id")).schema(); SchemaEnvelope e=new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA,"2020-12",schema); JsonNode v=JSON.createObjectNode().put("id","one"); return new ApiResourceCommand(n,null,new ApiResourceCommand.Operation("GET","/profile",List.of()),new ApiResourceCommand.Contract(e,e),new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)),null),ApiResourceCommand.Effect.READ_ONLY,List.of(new ApiResourceCommand.Example("one",v,v))); }
    private static final class MutableClock extends Clock { private Instant now=Instant.parse("2026-01-01T00:00:00Z"); void advance(Duration d){now=now.plus(d);} public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} }
}
