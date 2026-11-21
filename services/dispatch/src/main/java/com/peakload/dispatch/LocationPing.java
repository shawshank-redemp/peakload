package com.peakload.dispatch;

/**
 * The JSON body of POST /riders/{id}/location. Latitude is in [-90, 90] and longitude in [-180, 180].
 * Redis GEOADD takes longitude first; {@link RiderService} handles the order.
 */
record LocationPing(Double lat, Double lon) {

    /** @throws ApiException 400 if a coordinate is missing, not a number, or out of range */
    LocationPing validated() {
        if (lat == null || lat.isNaN() || lat < -90 || lat > 90) {
            throw ApiException.badRequest("invalid_latitude", "lat must be a number between -90 and 90");
        }
        if (lon == null || lon.isNaN() || lon < -180 || lon > 180) {
            throw ApiException.badRequest("invalid_longitude", "lon must be a number between -180 and 180");
        }
        return this;
    }
}
