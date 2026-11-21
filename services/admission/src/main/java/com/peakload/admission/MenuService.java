package com.peakload.admission;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads a restaurant and its menu from the admission schema. No cache yet; that is a later step. */
@Service
class MenuService {

    private final JdbcTemplate jdbc;

    MenuService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The menu, or empty if the restaurant does not exist. */
    Optional<Map<String, Object>> menu(UUID restaurantId) {
        List<Map<String, Object>> restaurants = jdbc.queryForList(
                "SELECT id, name, city FROM restaurants WHERE id = ?", restaurantId);
        if (restaurants.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id, name, price_paise AS "pricePaise", available
                FROM menu_items
                WHERE restaurant_id = ?
                ORDER BY name
                """, restaurantId);
        return Optional.of(Map.of(
                "restaurantId", restaurants.get(0).get("id").toString(),
                "name", restaurants.get(0).get("name"),
                "city", restaurants.get(0).get("city"),
                "items", items));
    }
}
