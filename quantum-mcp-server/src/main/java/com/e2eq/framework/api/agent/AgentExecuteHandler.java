package com.e2eq.framework.api.agent;

import com.e2eq.framework.api.query.QueryGatewayResource;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.filters.SecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Executes gateway tools by name with the given arguments. Delegates to QueryGatewayResource.
 * Realm is resolved from arguments.realm or the current principal's default realm.
 *
 * @see AgentResource
 * @see QueryGatewayResource
 * @see TenantAgentConfigResolver
 */
@ApplicationScoped
public class AgentExecuteHandler {

    @Inject
    QueryGatewayResource queryGatewayResource;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SecurityFilter securityFilter;

    /** Tool name to the gateway action the REST endpoint for that tool is authorized under. */
    private static final Map<String, String> TOOL_ACTIONS = Map.ofEntries(
            Map.entry("query_rootTypes", "listRootTypes"),
            Map.entry("query_plan", "plan"),
            Map.entry("query_find", "find"),
            Map.entry("query_count", "count"),
            Map.entry("query_save", "save"),
            Map.entry("query_delete", "delete"),
            Map.entry("query_deleteMany", "deleteMany"),
            Map.entry("query_import_analyze", "importAnalyze"),
            Map.entry("query_import_rows", "importRows"),
            Map.entry("query_import_commit", "importCommit"),
            Map.entry("query_import_cancel", "importCancel"),
            Map.entry("query_export", "export"));

    /**
     * Executes a gateway tool by name.
     *
     * @param tool      tool name (e.g. query_find, query_rootTypes)
     * @param arguments map of arguments (rootType, query, realm, page, sort, entity, id, etc.)
     * @return response from the underlying gateway operation
     */
    public Response execute(String tool, Map<String, Object> arguments) {
        if (tool == null || tool.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "ToolRequired", "message", "tool is required"))
                    .build();
        }
        arguments = arguments != null ? arguments : Map.of();

        // Callers reach this without the gateway's own REST pre-authorization: MCP tool calls never
        // pass through the SecurityFilter, and /api/agent/execute is @Authenticated, which skips it.
        // Establish the caller's security context if needed and apply the same check here.
        boolean establishedContext = false;
        boolean pushedResource = false;
        try {
            if (SecurityContext.getPrincipalContext().isEmpty()) {
                SecurityContext.setPrincipalContext(securityFilter.buildPrincipalContextForCurrentIdentity(null));
                establishedContext = true;
            }
            String action = TOOL_ACTIONS.get(tool);
            if (action != null) {
                PrincipalContext principal = SecurityContext.getPrincipalContext().orElseThrow();
                ResourceContext gatewayResource = new ResourceContext.Builder()
                        .withArea("integration")
                        .withFunctionalDomain("query")
                        .withAction(action)
                        .build();
                if (!securityFilter.isAllowed(principal, gatewayResource)) {
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity(Map.of("error", "AccessDenied", "message", String.format(
                                    "Access denied: user=%s, area=integration, domain=query, action=%s",
                                    principal.getUserId(), action)))
                            .build();
                }
                // Run the tool as the gateway resource, as the REST endpoint does, so repository
                // calls it makes directly (import sessions) get that resource's rule filters.
                SecurityContext.pushResourceContext(gatewayResource);
                pushedResource = true;
            }
            return dispatch(tool, arguments);
        } catch (WebApplicationException e) {
            int status = e.getResponse() != null ? e.getResponse().getStatus() : 500;
            return Response.status(status)
                    .entity(Map.of("error", status == 403 ? "AccessDenied" : "RequestFailed",
                            "message", String.valueOf(e.getMessage())))
                    .build();
        } finally {
            if (pushedResource) {
                SecurityContext.popResourceContext();
            }
            if (establishedContext) {
                // No response filter runs for MCP calls, so clear what the REST filter would clear.
                SecurityContext.clear();
                com.e2eq.framework.security.runtime.RuleContext.clearRequestCache();
            }
        }
    }

    private Response dispatch(String tool, Map<String, Object> arguments) {
        return switch (tool) {
            case "query_rootTypes" -> {
                QueryGatewayResource.RootTypesResponse r = queryGatewayResource.listRootTypes();
                yield Response.ok(r).build();
            }
            case "query_plan" -> {
                QueryGatewayResource.PlanRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.PlanRequest.class);
                QueryGatewayResource.PlanResponse r = queryGatewayResource.plan(req);
                yield Response.ok(r).build();
            }
            case "query_find" -> {
                QueryGatewayResource.FindRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.FindRequest.class);
                yield queryGatewayResource.find(req);
            }
            case "query_save" -> {
                QueryGatewayResource.SaveRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.SaveRequest.class);
                yield queryGatewayResource.save(req);
            }
            case "query_delete" -> {
                QueryGatewayResource.DeleteRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.DeleteRequest.class);
                yield queryGatewayResource.delete(req);
            }
            case "query_deleteMany" -> {
                QueryGatewayResource.DeleteManyRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.DeleteManyRequest.class);
                yield queryGatewayResource.deleteMany(req);
            }
            case "query_count" -> {
                QueryGatewayResource.CountRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.CountRequest.class);
                yield queryGatewayResource.count(req);
            }
            case "query_import_analyze" -> {
                QueryGatewayResource.ImportAnalyzeRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.ImportAnalyzeRequest.class);
                yield queryGatewayResource.importAnalyze(req);
            }
            case "query_import_rows" -> {
                QueryGatewayResource.ImportRowsRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.ImportRowsRequest.class);
                yield queryGatewayResource.importRows(req);
            }
            case "query_import_commit" -> {
                QueryGatewayResource.ImportCommitRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.ImportCommitRequest.class);
                yield queryGatewayResource.importCommit(req);
            }
            case "query_import_cancel" -> {
                QueryGatewayResource.ImportCancelRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.ImportCancelRequest.class);
                yield queryGatewayResource.importCancel(req);
            }
            case "query_export" -> {
                QueryGatewayResource.ExportRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.ExportRequest.class);
                yield queryGatewayResource.export(req);
            }
            default -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "UnknownTool", "message", "Unknown tool: " + tool,
                            "availableTools", new String[]{
                                    "query_rootTypes", "query_plan", "query_find", "query_count",
                                    "query_save", "query_delete", "query_deleteMany",
                                    "query_import_analyze", "query_import_rows", "query_import_commit",
                                    "query_import_cancel", "query_export"
                            }))
                    .build();
        };
    }
}
