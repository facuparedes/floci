package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::Pipes::Pipe}. */
@ApplicationScoped
public class PipesCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(PipesCfnProvisioner.class);

    private static final int PIPE_NAME_MAX_LENGTH = 64;

    private final PipesService pipesService;
    private final ObjectMapper objectMapper;

    @Inject
    public PipesCfnProvisioner(PipesService pipesService, ObjectMapper objectMapper) {
        this.pipesService = pipesService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Pipes::Pipe");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight. The one an earlier update left behind goes
        // before this run decides whether it mutates the pipe at all, so a rollback never puts back
        // a target the pipe stopped carrying two updates ago.
        r.getAttributes().remove(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
        String priorPhysicalId = ctx.priorPhysicalId();
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PIPE_NAME_MAX_LENGTH, false);

        String source = ctx.resolveOptional(props, "Source");
        String target = ctx.resolveOptional(props, "Target");
        String roleArn = ctx.resolveOptional(props, "RoleArn");
        String description = ctx.resolveOptional(props, "Description");
        String enrichment = ctx.resolveOptional(props, "Enrichment");

        String stateStr = ctx.resolveOptional(props, "DesiredState");
        DesiredState desiredState = "STOPPED".equals(stateStr) ? DesiredState.STOPPED : DesiredState.RUNNING;

        JsonNode sourceParameters = resolvedObject(props, "SourceParameters", ctx);
        JsonNode targetParameters = resolvedObject(props, "TargetParameters", ctx);
        JsonNode enrichmentParameters = resolvedObject(props, "EnrichmentParameters", ctx);

        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);

        // provision is also the update path. createPipe throws ConflictException on an existing
        // name, and stablePhysicalName keeps that name steady across updates, so a second
        // UpdateStack must reconcile the pipe rather than recreate it. A replacing update derives a
        // different name and still creates, which is why this asks reusesPriorEntity rather than
        // isUpdate. Null here is a first create, a rename, or a pipe deleted out of band since the
        // prior deploy, and all three create.
        Pipe existingPipe = ctx.reusesPriorEntity(name) ? pipeOnFile(name, ctx.region()) : null;

        Pipe pipe = existingPipe != null
                ? updateExistingPipe(r, existingPipe, name, source, target, roleArn, description,
                        desiredState, enrichment, sourceParameters, targetParameters,
                        enrichmentParameters, tags, ctx)
                : createPipeAndDeleteRenamedPrior(r, name, source, target, roleArn, description,
                        desiredState, enrichment, sourceParameters, targetParameters,
                        enrichmentParameters, tags, priorPhysicalId, ctx);

        // Ref returns the pipe name; Fn::GetAtt Arn returns the pipe ARN.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", pipe.getArn());
    }

    /** Reconciles the pipe already on file under this name, tags included. */
    private Pipe updateExistingPipe(StackResource r, Pipe existingPipe, String name, String source,
                                    String target, String roleArn, String description,
                                    DesiredState desiredState, String enrichment,
                                    JsonNode sourceParameters, JsonNode targetParameters,
                                    JsonNode enrichmentParameters, Map<String, String> tags,
                                    ProvisionContext ctx) {
        // A Source that is absent, null or resolves blank is a missing required property, not a
        // changed one. The create path reports it as such, so this path reports it identically
        // instead of blaming a replacement the template never asked for.
        if (source == null || source.isBlank()) {
            throw new AwsException("ValidationException", "Source is required", 400);
        }
        // Source is a createOnly property. With the name reused there is no replacement to move to,
        // which is the update CloudFormation refuses for a custom-named resource, so it is refused
        // here rather than silently kept on the old source. The 13 create-only paths nested under
        // SourceParameters (the StartingPosition, TopicName, QueueName, VirtualHost,
        // ConsumerGroupID and AdditionalBootstrapServers entries) are reconciled in place, where
        // AWS replaces the pipe. That gap is left to a later change.
        if (!source.equals(existingPipe.getSource())) {
            throw new AwsException("ValidationError",
                    "Updating Source requires resource replacement, which is not supported.", 400);
        }
        snapshotPipeBeforeUpdate(r, existingPipe, ctx.region());
        Pipe pipe = pipesService.updatePipe(name, target, roleArn, description, desiredState,
                enrichment, sourceParameters, targetParameters, enrichmentParameters, ctx.region());
        reconcileTags(pipe, tags, ctx.region());
        return pipe;
    }

    /**
     * Records what the pipe carries before updatePipe and the tag calls change it, so
     * {@link #rollbackUpdate} can put it back when the stack update fails. Only the properties
     * updatePipe accepts, plus the tags: Name, Source and Arn cannot change on this path. The
     * region travels with them because the rollback hook is handed the stack resource alone, and
     * name plus region is how PipesService addresses a pipe.
     */
    private void snapshotPipeBeforeUpdate(StackResource r, Pipe existingPipe, String region) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("target", existingPipe.getTarget());
        snapshot.put("roleArn", existingPipe.getRoleArn());
        snapshot.put("description", existingPipe.getDescription());
        snapshot.put("desiredState", existingPipe.getDesiredState() == null
                ? null : existingPipe.getDesiredState().name());
        snapshot.put("enrichment", existingPipe.getEnrichment());
        snapshot.set("sourceParameters", existingPipe.getSourceParameters());
        snapshot.set("targetParameters", existingPipe.getTargetParameters());
        snapshot.set("enrichmentParameters", existingPipe.getEnrichmentParameters());
        snapshot.set("tags", objectMapper.valueToTree(
                existingPipe.getTags() == null ? Map.of() : existingPipe.getTags()));
        r.getAttributes().put(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().get(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return false;
        }
        try {
            restoreSnapshottedPipe(resource, objectMapper.readTree(rawSnapshot));
        } catch (JsonProcessingException unreadableSnapshot) {
            throw new IllegalStateException("Could not read the pipe update snapshot for "
                    + resource.getLogicalId(), unreadableSnapshot);
        }
        return true;
    }

    /**
     * Puts the pipe back to the configuration the snapshot holds and spends the snapshot. Tags go
     * through the same reconciliation the update uses.
     *
     * <p>A property the pipe had unset stays as the failed update wrote it: updatePipe reads a null
     * as "leave this one alone", the same limit the update path itself works under.
     */
    private void restoreSnapshottedPipe(StackResource resource, JsonNode snapshot) {
        String region = snapshot.get("region").asText();
        String desiredState = snapshotText(snapshot, "desiredState");
        Pipe restored = pipesService.updatePipe(resource.getPhysicalId(),
                snapshotText(snapshot, "target"),
                snapshotText(snapshot, "roleArn"),
                snapshotText(snapshot, "description"),
                desiredState == null ? null : DesiredState.valueOf(desiredState),
                snapshotText(snapshot, "enrichment"),
                snapshotValue(snapshot, "sourceParameters"),
                snapshotValue(snapshot, "targetParameters"),
                snapshotValue(snapshot, "enrichmentParameters"),
                region);
        Map<String, String> tags = new HashMap<>();
        snapshot.path("tags").fields().forEachRemaining(
                tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(restored, tags, region);
        resource.getAttributes().remove(CfnRollback.PIPE_UPDATE_SNAPSHOT_ATTR);
    }

    /** A snapshotted property, or null when the pipe carried none. */
    private static JsonNode snapshotValue(JsonNode snapshot, String property) {
        JsonNode value = snapshot.get(property);
        return value == null || value.isNull() ? null : value;
    }

    private static String snapshotText(JsonNode snapshot, String property) {
        JsonNode value = snapshotValue(snapshot, property);
        return value == null ? null : value.asText();
    }

    /**
     * Creates the pipe, and on a rename deletes the pipe the prior deploy left under the old name.
     * The replacement is created first, so a createPipe that throws leaves the original intact and
     * the resource is marked as already restored: rollback does not restore what was never deleted.
     */
    private Pipe createPipeAndDeleteRenamedPrior(StackResource r, String name, String source,
                                                 String target, String roleArn, String description,
                                                 DesiredState desiredState, String enrichment,
                                                 JsonNode sourceParameters, JsonNode targetParameters,
                                                 JsonNode enrichmentParameters,
                                                 Map<String, String> tags, String priorPhysicalId,
                                                 ProvisionContext ctx) {
        Pipe preservedPriorPipe = priorPhysicalId != null && !priorPhysicalId.equals(name)
                ? pipeOnFile(priorPhysicalId, ctx.region())
                : null;
        Pipe pipe;
        try {
            pipe = pipesService.createPipe(name, source, target, roleArn, description, desiredState,
                    enrichment, sourceParameters, targetParameters, enrichmentParameters, tags,
                    ctx.region());
        } catch (RuntimeException failure) {
            if (preservedPriorPipe != null) {
                r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
            }
            throw failure;
        }
        if (preservedPriorPipe != null) {
            try {
                pipesService.deletePipe(priorPhysicalId, ctx.region());
            } catch (RuntimeException cleanupFailure) {
                // The replacement is already on file. Propagating here would leave it with no stack
                // resource pointing at it, so nothing would ever delete it.
                LOG.warnv(cleanupFailure, "Failed to delete renamed pipe {0} after replacement by {1}",
                        priorPhysicalId, name);
            }
        }
        return pipe;
    }

    /**
     * The pipe on file under this name, or null when it is not there. Only the not-found error
     * yields null: any other failure reaches the user as itself, instead of sending the caller into
     * the create arm to report ConflictException over an unrelated fault.
     */
    private Pipe pipeOnFile(String name, String region) {
        try {
            return pipesService.describePipe(name, region);
        } catch (AwsException lookupFailure) {
            if (!"NotFoundException".equals(lookupFailure.getErrorCode())
                    && lookupFailure.getHttpStatus() != 404) {
                throw lookupFailure;
            }
            // Expected when the pipe was deleted out of band since the prior deploy, and on the
            // rename arm when the prior pipe is already gone; both fall back to a plain create.
            LOG.debugv(lookupFailure, "No pipe {0} found on file", name);
            return null;
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        pipesService.deletePipe(physicalId, region);
    }

    private JsonNode resolvedObject(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return ctx.engine().resolveNode(props.get(name));
    }

    /**
     * UpdatePipe carries no Tags (only CreatePipe does), so on the update path the template's tags
     * are driven to their desired state through TagResource and UntagResource, keyed by the pipe's
     * ARN. A key the template dropped is untagged rather than left over.
     */
    private void reconcileTags(Pipe pipe, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(
                pipesService.listTags(region, pipe.getArn()), desired);
        if (!stale.isEmpty()) {
            pipesService.untagResource(region, pipe.getArn(), stale);
        }
        if (!desired.isEmpty()) {
            pipesService.tagResource(region, pipe.getArn(), desired);
        }
    }

    /** See {@code KmsCfnProvisioner#parseCfnTags} for why this is copied rather than shared. */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
