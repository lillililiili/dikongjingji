package com.lowaltitude.inspection.application.city;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.ops.OpsActor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class MapPlaceOperations {
    public record Edit(String cityCode, String name, String address, Double latitude, Double longitude,
                       Integer sortOrder, Boolean published, Integer version) {}
    public record Place(UUID id, String cityCode, String cityName, String name, String address, double latitude,
                        double longitude, int sortOrder, boolean published, boolean sample, int version) {}
    public record Page(int page, int size, long total, boolean hasMore, List<Place> items) {}
    public record Change(int version, String operatorName, OffsetDateTime changedAt, Place previous, Place current) {}

    private static final String FIELDS = """
            SELECT p.management_id, p.city_code, c.city_name, p.name, p.address,
                   ST_Y(p.location::geometry) AS latitude, ST_X(p.location::geometry) AS longitude,
                   p.sort_order, p.published, p.sample, p.version
            """;
    private static final String FROM = " FROM platform_map_place p JOIN platform_city c ON c.city_code=p.city_code ";
    private static final String FILTER = """
            WHERE (CAST(? AS text) IS NULL OR p.city_code=?) AND
                  (CAST(? AS boolean) IS NULL OR p.published=?) AND
                  (strpos(lower(p.name),lower(?))>0 OR strpos(lower(p.address),lower(?))>0)
            """;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public MapPlaceOperations(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page list(String city, String keyword, Boolean published, int page, int size) {
        if (city != null && !city.matches("[0-9]{6}") || keyword.length() > 120 || page < 1 || size < 1 || size > 50)
            throw new IllegalArgumentException("Invalid place search");
        String query = keyword.strip();
        long total = db.queryForObject("SELECT count(*)" + FROM + FILTER, Long.class,
                city, city, published, published, query, query);
        var items = db.query(FIELDS + FROM + FILTER + " ORDER BY p.city_code,p.sort_order,p.place_key LIMIT ? OFFSET ?",
                MapPlaceOperations::row, city, city, published, published, query, query, size, ((long) page - 1) * size);
        return new Page(page, size, total, (long) page * size < total, items);
    }

    @Transactional
    public Place save(OpsActor actor, UUID id, Edit edit) {
        validate(edit);
        String name = edit.name().strip();
        String address = edit.address().strip();
        if (!Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_city WHERE city_code=? AND status='ACTIVE')",
                Boolean.class, edit.cityCode()))) throw new CityOperationException(CityOperationException.Error.NOT_FOUND);
        Place previous = null;
        if (edit.version() == null) {
            // The editor keeps its UUID across retries, including a lost first response.
            int inserted = db.update("""
                    INSERT INTO platform_map_place(management_id,city_code,place_key,name,address,location,sort_order,published)
                    VALUES (?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?,?) ON CONFLICT(management_id) DO NOTHING
                    """, id, edit.cityCode(), id.toString(), name, address, edit.longitude(), edit.latitude(),
                    edit.sortOrder(), edit.published());
            if (inserted == 0) {
                var existing = get(id, true);
                if (existing.version() == 0 && existing.cityCode().equals(edit.cityCode()) && existing.name().equals(name)
                        && existing.address().equals(address) && existing.latitude() == edit.latitude()
                        && existing.longitude() == edit.longitude() && existing.sortOrder() == edit.sortOrder()
                        && existing.published() == edit.published()) return existing;
                throw new CityOperationException(CityOperationException.Error.VERSION_CONFLICT);
            }
        } else {
            previous = get(id, true);
            if (previous.version() != edit.version()) throw new CityOperationException(CityOperationException.Error.VERSION_CONFLICT);
            // Legacy keys such as "road-88" repeat across cities. A moved row gets its unique management key.
            db.update("""
                    UPDATE platform_map_place SET place_key=CASE WHEN city_code<>? THEN management_id::text ELSE place_key END,
                      city_code=?,name=?,address=?,location=ST_SetSRID(ST_MakePoint(?,?),4326)::geography,
                      sort_order=?,published=?,version=version+1 WHERE management_id=?
                    """, edit.cityCode(), edit.cityCode(), name, address, edit.longitude(), edit.latitude(),
                    edit.sortOrder(), edit.published(), id);
        }
        Place current = get(id, false);
        db.update("""
                INSERT INTO platform_map_place_change(management_id, version, operator_id, operator_name, previous_value, current_value)
                VALUES(?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """, id, current.version(), actor.accountId(), actor.displayName(),
                previous == null ? null : encoded(previous), encoded(current));
        return current;
    }

    @Transactional(readOnly = true)
    public List<Change> changes(UUID id) {
        get(id, false);
        return db.query("""
                SELECT version,operator_name,changed_at,previous_value,current_value FROM platform_map_place_change
                WHERE management_id=? ORDER BY version DESC LIMIT 50
                """, (r,n) -> new Change(r.getInt("version"), r.getString("operator_name"),
                r.getObject("changed_at",OffsetDateTime.class), decoded(r.getString("previous_value")),
                decoded(r.getString("current_value"))), id);
    }

    private Place get(UUID id, boolean lock) {
        return db.query(FIELDS + FROM + " WHERE p.management_id=?" + (lock ? " FOR UPDATE OF p" : ""),
                MapPlaceOperations::row, id).stream().findFirst()
                .orElseThrow(() -> new CityOperationException(CityOperationException.Error.NOT_FOUND));
    }

    private static Place row(ResultSet r, int index) throws SQLException {
        return new Place(r.getObject("management_id", UUID.class), r.getString("city_code"), r.getString("city_name"),
                r.getString("name"), r.getString("address"), r.getDouble("latitude"), r.getDouble("longitude"),
                r.getInt("sort_order"), r.getBoolean("published"), r.getBoolean("sample"), r.getInt("version"));
    }

    private static void validate(Edit e) {
        if (e.cityCode() == null || !e.cityCode().matches("[0-9]{6}") || invalidText(e.name(),120)
                || invalidText(e.address(),240) || !validCoordinate(e.latitude(),90) || !validCoordinate(e.longitude(),180)
                || e.sortOrder() == null || e.sortOrder() < 0 || e.published() == null
                || e.version() != null && e.version() < 0) throw new IllegalArgumentException("Invalid map place");
    }

    private static boolean invalidText(String s, int max) {
        return s == null || s.isBlank() || s.strip().length() > max;
    }
    private static boolean validCoordinate(Double n, int max) {
        return n != null && Double.isFinite(n) && Math.abs(n) <= max;
    }
    private String encoded(Place place) {
        try { return json.writeValueAsString(place); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Unable to encode place history", e); }
    }
    private Place decoded(String value) {
        if (value == null) return null;
        try { return json.readValue(value, Place.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid place history", e); }
    }
}
