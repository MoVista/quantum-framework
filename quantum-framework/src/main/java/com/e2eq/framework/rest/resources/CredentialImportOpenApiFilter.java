package com.e2eq.framework.rest.resources;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.List;

/** Removes inherited success responses from the credential operations that always reject sessions. */
@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
public class CredentialImportOpenApiFilter implements OASFilter {
    @Override
    public void filterOpenAPI(OpenAPI api) {
        if (api.getPaths() == null) return;
        for (String path : List.of("/user/credentials/csv/session", "/user/credentials/csv/session/profile",
                "/user/credentials/csv/session/{sessionId}/commit")) {
            var item = api.getPaths().getPathItem(path);
            if (item == null || item.getPOST() == null || item.getPOST().getResponses() == null) continue;
            var responses = item.getPOST().getResponses();
            if (responses.getAPIResponses() == null) continue;
            responses.getAPIResponses().keySet().stream().filter(code -> code.startsWith("2"))
                    .toList().forEach(responses::removeAPIResponse);
        }
    }
}
