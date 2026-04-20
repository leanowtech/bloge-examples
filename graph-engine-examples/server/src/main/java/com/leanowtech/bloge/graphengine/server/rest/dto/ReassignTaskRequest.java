package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP payload that reassigns one human task to a different user.
 *
 * @param newAssignee new assignee identity
 */
public record ReassignTaskRequest(
        @NotBlank String newAssignee
) {
}
