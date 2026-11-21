package com.peakload.order;

/** The JSON body of PATCH /orders/{id}/status. The expected version comes in the If-Match header. */
record StatusChangeRequest(String status) {}
