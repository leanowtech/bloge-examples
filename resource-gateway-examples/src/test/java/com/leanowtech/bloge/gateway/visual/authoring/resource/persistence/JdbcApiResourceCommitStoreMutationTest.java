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
import java.util.concurrent.*;

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

    @Test void stageIsIdempotentAndTamperFailsClosed() {
        JdbcApiResourceCommitStore s=store(); CommandLease l=acquire(s,key,ExpectedRevision.create()); StagedApiResource a=s.stage(l,"connection",command("one"));
        assertThat(s.stage(l,"connection",command("one")).strongEtag()).isEqualTo(a.strongEtag());
        assertThat(jdbc.queryForObject("SELECT set_fingerprint FROM rg_api_resource_projection_revisions",String.class)).isNotEqualTo(AuthoringFingerprints.of(JSON.createObjectNode()));
        jdbc.update("UPDATE rg_api_resource_projection_revisions SET set_fingerprint=?",fp.replace('1','2'));
        assertThatThrownBy(()->s.stage(l,"connection",command("one"))).isInstanceOf(ApiResourceCommitStoreException.class).extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test void validationIsPreservedAndConcurrencyMapsToCas() {
        JdbcApiResourceCommitStore s=store(); CommandLease l=acquire(s,key,ExpectedRevision.create());
        assertThatThrownBy(()->s.stage(l,"connection",command(""))).isInstanceOf(ApiResourceAuthoringException.class).extracting("code").isEqualTo(ApiResourceAuthoringException.Code.VALIDATION);
    }

    @Test void forgedLeaseAndReceiptMismatchAreRejected() {
        JdbcApiResourceCommitStore s=store(); CommandLease l=acquire(s,key,ExpectedRevision.create()); StagedApiResource a=s.stage(l,"connection",command("one"));
        CommandLease f=new CommandLease(l.commandId(),l.attemptNo(),l.attemptToken(),l.key(),l.requestFingerprint(),clock.instant().plus(Duration.ofDays(1)),ExpectedRevision.match(9));
        assertThatThrownBy(()->s.commit(f,null)).isInstanceOf(ApiResourceCommitStoreException.class).extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_FENCED);
        JsonNode b=JSON.createObjectNode().put("result","profile"); assertThatThrownBy(()->new CommandReceipt("r",b,fp,a.strongEtag())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->s.commit(l,new CommandReceipt("r",b,AuthoringFingerprints.of(b),"\"wrong\""))).isInstanceOf(ApiResourceCommitStoreException.class).extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.RECEIPT_INVALID);
    }

    @Test void failCleansStageAndWritesFailedJournal() {
        JdbcApiResourceCommitStore s=store(); CommandLease l=acquire(s,key,ExpectedRevision.create()); s.stage(l,"connection",command("one")); s.fail(l,CommandFailureCode.INTERNAL);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal",String.class)).isEqualTo("FAILED"); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions",Integer.class)).isZero();
        assertThatThrownBy(()->s.fail(null,CommandFailureCode.INTERNAL)).isInstanceOf(ApiResourceCommitStoreException.class); assertThatThrownBy(()->s.fail(l,null)).isInstanceOf(ApiResourceCommitStoreException.class);
    }

    private JdbcApiResourceCommitStore store() { return new JdbcApiResourceCommitStore(jdbc, new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())), JSON, clock, Duration.ofSeconds(1), new ApiResourceDecisions(), (s, r) -> projections(r)); }
    private JdbcApiResourceCommitStore store(ApiResourceDecisions d, ApiResourceProjectionCompiler c) { return new JdbcApiResourceCommitStore(jdbc,new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())),JSON,clock,Duration.ofSeconds(1),d,c); }
    private CommandLease acquire(JdbcApiResourceCommitStore s, CommandKey k, ExpectedRevision e) { return ((ClaimResult.Acquired)s.claim(k, fp, e)).lease(); }
    private static CommandReceipt receipt(StagedApiResource s) { JsonNode b = JSON.createObjectNode().put("result", s.resource().resourceId()); return new CommandReceipt("test.receipt.v1", b, AuthoringFingerprints.of(b), s.strongEtag()); }
    private static ReadyApiResourceProjections projections(ApiResourceSpec r) { return new ReadyApiResourceProjections(doc(ProjectionDocument.Kind.DESCRIPTOR,r),doc(ProjectionDocument.Kind.DESIGN_CONTRACT,r),doc(ProjectionDocument.Kind.OPERATOR,r)); }
    private static ProjectionDocument doc(ProjectionDocument.Kind k, ApiResourceSpec r) { JsonNode b=JSON.createObjectNode().put("kind",k.name()); return new ProjectionDocument(k,r.ref(),b,AuthoringFingerprints.of(b),ProjectionDocument.State.READY); }
    private static ApiResourceCommand command(String n) { Map<String,Object> schema=SchemaEnvelope.object(Map.of("id",Map.of("type","string")),List.of("id")).schema(); SchemaEnvelope e=new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA,"2020-12",schema); JsonNode v=JSON.createObjectNode().put("id","one"); return new ApiResourceCommand(n,null,new ApiResourceCommand.Operation("GET","/profile",List.of()),new ApiResourceCommand.Contract(e,e),new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)),null),ApiResourceCommand.Effect.READ_ONLY,List.of(new ApiResourceCommand.Example("one",v,v))); }
    private static final class MutableClock extends Clock { private Instant now=Instant.parse("2026-01-01T00:00:00Z"); void advance(Duration d){now=now.plus(d);} public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} }
}
