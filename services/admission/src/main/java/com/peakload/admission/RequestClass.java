package com.peakload.admission;

/**
 * Priority class of a request. Week 3 only labels requests with a class: no request is rejected or delayed
 * by it yet. Rate limiting and load shedding (later steps) use these classes.
 *
 * <ul>
 *   <li>CRITICAL: placing an order. Must keep working under load.</li>
 *   <li>STANDARD: reading or changing an existing order.</li>
 *   <li>LOW: browsing, which can be shed first.</li>
 * </ul>
 */
enum RequestClass {
    CRITICAL,
    STANDARD,
    LOW
}
