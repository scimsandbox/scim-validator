package de.palsoftware.scim.validator.base

import java.time.OffsetDateTime

class ScimHttpExchange {
    String method
    String url
    String requestHeaders
    String requestBody
    Integer responseStatus
    String responseHeaders
    String responseBody
    OffsetDateTime createdAt
}
