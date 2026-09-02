package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Pipes::Pipe}, whose provision body doubles as its update path.
 *
 * <p>{@code PipesService} is stubbed with the store it really keeps, so a second create under a
 * name already on file raises the production {@code ConflictException} instead of a bare
 * {@code verify(never())}.
 */
class PipesCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String SOURCE_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:example-queue";
    private static final String TARGET_QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:example-target-queue";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/example-pipe-role";

    private PipesService pipes;
    private PipesCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    /** The pipes the stubbed service holds, keyed by name, standing in for its storage backend. */
    private Map<String, Pipe> pipesOnFile;

    @BeforeEach
    void setUp() {
        pipes = mock(PipesService.class);
        provisioner = new PipesCfnProvisioner(pipes);
        pipesOnFile = new LinkedHashMap<>();

        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));

        when(pipes.createPipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            if (pipesOnFile.containsKey(name)) {
                throw new AwsException("ConflictException", "Pipe " + name + " already exists.", 409);
            }
            Pipe pipe = new Pipe();
            pipe.setName(name);
            pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/" + name);
            pipe.setSource(i.getArgument(1));
            pipe.setTarget(i.getArgument(2));
            pipesOnFile.put(name, pipe);
            return pipe;
        });
        when(pipes.describePipe(anyString(), anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            Pipe pipe = pipesOnFile.get(name);
            if (pipe == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            return pipe;
        });
        when(pipes.updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString())).thenAnswer(i -> {
            String name = i.getArgument(0);
            Pipe pipe = pipesOnFile.get(name);
            if (pipe == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            if (i.getArgument(1) != null) {
                pipe.setTarget(i.getArgument(1));
            }
            return pipe;
        });
        doAnswer(i -> {
            String name = i.getArgument(0);
            if (pipesOnFile.remove(name) == null) {
                throw new AwsException("NotFoundException", "Pipe " + name + " does not exist.", 404);
            }
            return null;
        }).when(pipes).deletePipe(anyString(), anyString());
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private StackResource provision(String json, String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("MyPipe");
        r.setResourceType("AWS::Pipes::Pipe");
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", priorPhysicalId));
        return r;
    }

    private static String pipeTemplate(String name, String source, String target) {
        return """
                {"Name": "%s", "Source": "%s", "Target": "%s", "RoleArn": "%s"}
                """.formatted(name, source, target, ROLE_ARN);
    }

    @Test
    void refIsThePipeNameAndGetAttExposesArn() {
        StackResource r = provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        verify(pipes).createPipe(eq("MyPipe"), eq(SOURCE_QUEUE_ARN), eq(TARGET_QUEUE_ARN), eq(ROLE_ARN),
                any(), eq(DesiredState.RUNNING), any(), any(), any(), any(), any(), eq(REGION));
        assertEquals("MyPipe", r.getPhysicalId());
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe", r.getAttributes().get("Arn"));
    }

    @Test
    void desiredStateStoppedIsHonouredAndAnythingElseRuns() {
        provision("""
                {"Name": "MyPipe", "DesiredState": "STOPPED"}
                """, null);
        verify(pipes).createPipe(any(), any(), any(), any(), any(), eq(DesiredState.STOPPED),
                any(), any(), any(), any(), any(), any());

        provision("""
                {"Name": "OtherPipe", "DesiredState": "SOMETHING_ELSE"}
                """, null);
        verify(pipes).createPipe(eq("OtherPipe"), any(), any(), any(), any(), eq(DesiredState.RUNNING),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteReachesTheService() {
        pipesOnFile.put("MyPipe", new Pipe());
        provisioner.delete("AWS::Pipes::Pipe", "MyPipe", REGION);
        verify(pipes).deletePipe("MyPipe", REGION);
    }

    /**
     * provision() re-runs on every UpdateStack, so an unchanged name must update the pipe rather
     * than call createPipe again, which the service rejects with ConflictException.
     */
    @Test
    void anUnchangedNameUpdatesThePipeInsteadOfRecreatingIt() {
        StackResource created = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        StackResource updated = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, "arn:aws:sqs:us-east-1:000000000000:new-target-queue"),
                created.getPhysicalId());

        verify(pipes).updatePipe(eq("MyPipe"),
                eq("arn:aws:sqs:us-east-1:000000000000:new-target-queue"), eq(ROLE_ARN), any(),
                eq(DesiredState.RUNNING), any(), any(), any(), any(), eq(REGION));
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertEquals("MyPipe", updated.getPhysicalId(), "Ref is unchanged by the update");
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyPipe",
                updated.getAttributes().get("Arn"), "Fn::GetAtt Arn is unchanged by the update");
    }

    /** Source is create-only on AWS::Pipes::Pipe, which is why updatePipe takes no source. */
    @Test
    void aChangedSourceIsRefusedAsReplacementWorthy() {
        StackResource created = provision(
                pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        AwsException refusal = assertThrows(AwsException.class, () -> provision(
                pipeTemplate("MyPipe", "arn:aws:sqs:us-east-1:000000000000:other-queue", TARGET_QUEUE_ARN),
                created.getPhysicalId()));

        assertEquals("ValidationError", refusal.getErrorCode());
        assertEquals("Updating Source requires resource replacement, which is not supported.",
                refusal.getMessage());
        assertEquals(400, refusal.getHttpStatus());
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString());
    }

    /**
     * On a rename the replacement is created before the original is deleted, so a createPipe that
     * throws leaves the old pipe intact.
     */
    @Test
    void aRenameCreatesTheReplacementBeforeDeletingTheOriginal() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);

        StackResource renamed = provision(
                pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");

        InOrder order = inOrder(pipes);
        order.verify(pipes).createPipe(eq("MyRenamedPipe"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        order.verify(pipes).deletePipe("MyPipe", REGION);
        assertEquals("MyRenamedPipe", renamed.getPhysicalId());
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/MyRenamedPipe",
                renamed.getAttributes().get("Arn"));
    }

    /**
     * A rename whose createPipe fails leaves the prior pipe on file, so the rollback walker must be
     * told not to restore something that was never deleted.
     */
    @Test
    void aFailedRenameKeepsThePriorPipeAndMarksTheResourceRestored() {
        provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), null);
        Pipe squatter = new Pipe();
        squatter.setName("MyRenamedPipe");
        pipesOnFile.put("MyRenamedPipe", squatter);

        StackResource r = new StackResource();
        r.setLogicalId("MyPipe");
        r.setResourceType("AWS::Pipes::Pipe");
        r.setPhysicalId("MyPipe");
        r.setAttributes(new HashMap<>());
        JsonNode template = props(pipeTemplate("MyRenamedPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, ACCOUNT_ID, "TestStack", "MyPipe");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, template, ctx));

        assertEquals("ConflictException", failure.getErrorCode());
        assertTrue(pipesOnFile.containsKey("MyPipe"), "the prior pipe is still on file");
        verify(pipes, never()).deletePipe(anyString(), anyString());
        assertEquals("true", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        assertEquals("MyPipe", r.getPhysicalId(), "the resource still points at the prior pipe");
    }

    /** A pipe with no template Name keeps its generated physical name across updates. */
    @Test
    void anUnnamedPipeKeepsItsGeneratedNameAcrossUpdates() {
        String unnamedTemplate = """
                {"Source": "%s", "Target": "%s", "RoleArn": "%s"}
                """.formatted(SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN, ROLE_ARN);

        StackResource created = provision(unnamedTemplate, null);
        assertTrue(created.getPhysicalId().startsWith("TestStack-MyPipe-"), created.getPhysicalId());

        StackResource updated = provision(unnamedTemplate, created.getPhysicalId());

        assertEquals(created.getPhysicalId(), updated.getPhysicalId());
        verify(pipes).updatePipe(eq(created.getPhysicalId()), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(REGION));
        assertEquals(1, pipesOnFile.size(), "no second pipe was created under a fresh name");
    }

    /** A pipe deleted out of band since the prior deploy is created again under the same name. */
    @Test
    void aPipeDeletedOutOfBandFallsBackToCreate() {
        StackResource r = provision(pipeTemplate("MyPipe", SOURCE_QUEUE_ARN, TARGET_QUEUE_ARN), "MyPipe");

        verify(pipes).createPipe(eq("MyPipe"), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(REGION));
        verify(pipes, never()).updatePipe(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyString());
        assertEquals("MyPipe", r.getPhysicalId());
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
    }
}
