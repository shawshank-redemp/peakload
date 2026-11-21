package com.peakload.order;

/** The JSON body of POST /orders. Fields are checked by {@link OrderInput#validated}. */
record CreateOrderRequest(String customerId, String city, Long totalPaise, Double pickupLat, Double pickupLon) {}
