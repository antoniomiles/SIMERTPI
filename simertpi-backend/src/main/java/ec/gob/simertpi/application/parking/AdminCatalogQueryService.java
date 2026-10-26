package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, paginated administrative projections for the parking catalog. */
@Service
public class AdminCatalogQueryService {
    private final JdbcTemplate jdbc;
    public AdminCatalogQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Page<T>(int page, int size, long totalElements, int totalPages, List<T> content) { }
    public record ZoneRow(UUID id, String code, String name, String description, boolean active,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, long streetCount, long spaceCount,
            long activeSpaceCount) { }
    public record StreetRow(UUID id, UUID zoneId, String zoneCode, String zoneName, String code,
            String name, String description, boolean active, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, long spaceCount) { }
    public record SpaceRow(UUID id, UUID zoneId, String zoneCode, String zoneName, UUID streetId,
            String streetCode, String streetName, String code, String qrCode, String spaceNumber,
            BigDecimal latitude, BigDecimal longitude, String spaceType, boolean active,
            String operationalStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    @Transactional(readOnly = true)
    @Audited(action = "ADMIN_PARKING_ZONES_SEARCHED", resourceType = "PARKING_ZONE")
    public Page<ZoneRow> zones(String q, Boolean active, int page, int size) {
        int p = safePage(page), s = safeSize(size); String term = like(q);
        Long total = jdbc.queryForObject("SELECT count(*) FROM parking.zones z WHERE (?::boolean IS NULL OR z.active=?) AND (?::text IS NULL OR lower(z.code) LIKE ? OR lower(z.name) LIKE ?)", Long.class,
                active, active, term, term, term);
        List<ZoneRow> rows = jdbc.query("""
                SELECT z.id,z.code,z.name,z.description,z.active,z.created_at,z.updated_at,
                       count(DISTINCT st.id) street_count,count(DISTINCT sp.id) space_count,
                       count(DISTINCT sp.id) FILTER (WHERE sp.active AND st.active AND z.active) active_space_count
                  FROM parking.zones z LEFT JOIN parking.streets st ON st.zone_id=z.id
                  LEFT JOIN parking.parking_spaces sp ON sp.street_id=st.id
                 WHERE (?::boolean IS NULL OR z.active=?) AND (?::text IS NULL OR lower(z.code) LIKE ? OR lower(z.name) LIKE ?)
                 GROUP BY z.id ORDER BY lower(z.code),z.id LIMIT ? OFFSET ?
                """, (r,n)->new ZoneRow(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),r.getBoolean("active"),
                r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class),r.getLong("street_count"),r.getLong("space_count"),r.getLong("active_space_count")),active,active,term,term,term,s,(long)p*s);
        return result(p,s,total,rows);
    }

    @Transactional(readOnly = true)
    @Audited(action = "ADMIN_PARKING_STREETS_SEARCHED", resourceType = "PARKING_STREET")
    public Page<StreetRow> streets(String q, UUID zoneId, Boolean active, int page, int size) {
        int p=safePage(page),s=safeSize(size); String term=like(q);
        Long total=jdbc.queryForObject("SELECT count(*) FROM parking.streets st WHERE (?::uuid IS NULL OR st.zone_id=?) AND (?::boolean IS NULL OR st.active=?) AND (?::text IS NULL OR lower(st.code) LIKE ? OR lower(st.name) LIKE ?)",Long.class,zoneId,zoneId,active,active,term,term,term);
        List<StreetRow> rows=jdbc.query("""
                SELECT st.id,st.zone_id,z.code zone_code,z.name zone_name,st.code,st.name,st.description,st.active,st.created_at,st.updated_at,count(sp.id) space_count
                  FROM parking.streets st JOIN parking.zones z ON z.id=st.zone_id LEFT JOIN parking.parking_spaces sp ON sp.street_id=st.id
                 WHERE (?::uuid IS NULL OR st.zone_id=?) AND (?::boolean IS NULL OR st.active=?) AND (?::text IS NULL OR lower(st.code) LIKE ? OR lower(st.name) LIKE ?)
                 GROUP BY st.id,z.code,z.name ORDER BY lower(z.code),lower(st.code),st.id LIMIT ? OFFSET ?
                """,(r,n)->new StreetRow(r.getObject("id",UUID.class),r.getObject("zone_id",UUID.class),r.getString("zone_code"),r.getString("zone_name"),r.getString("code"),r.getString("name"),r.getString("description"),r.getBoolean("active"),r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class),r.getLong("space_count")),zoneId,zoneId,active,active,term,term,term,s,(long)p*s);
        return result(p,s,total,rows);
    }

    @Transactional(readOnly = true)
    @Audited(action = "ADMIN_PARKING_SPACES_SEARCHED", resourceType = "PARKING_SPACE")
    public Page<SpaceRow> spaces(String q, UUID zoneId, UUID streetId, Boolean active, String operational,
            Boolean georeferenced, int page, int size) {
        int p=safePage(page),s=safeSize(size); String term=like(q);
        String joins=" FROM parking.parking_spaces sp JOIN parking.streets st ON st.id=sp.street_id JOIN parking.zones z ON z.id=st.zone_id "
                + "LEFT JOIN parking.parking_sessions ps ON ps.parking_space_id=sp.id AND ps.status IN ('PENDING_PAYMENT','ACTIVE','EXTENDED','EXPIRED','MAX_TIME_REACHED') ";
        String where=" WHERE (?::uuid IS NULL OR z.id=?) AND (?::uuid IS NULL OR st.id=?) AND (?::boolean IS NULL OR sp.active=?) "
                + "AND (?::text IS NULL OR lower(sp.code) LIKE ? OR lower(sp.space_number) LIKE ? OR lower(st.name) LIKE ?) "
                + "AND (?::boolean IS NULL OR ((sp.latitude IS NOT NULL AND sp.longitude IS NOT NULL)=?)) "
                + "AND (?::text IS NULL OR (CASE WHEN NOT (sp.active AND st.active AND z.active) THEN 'DISABLED' WHEN ps.status IS NULL THEN 'AVAILABLE' WHEN ps.status IN ('ACTIVE','EXTENDED') THEN 'OCCUPIED' ELSE 'OCCUPIED' END)=?)";
        Long total=jdbc.queryForObject("SELECT count(DISTINCT sp.id)"+joins+where,Long.class,zoneId,zoneId,streetId,streetId,active,active,term,term,term,term,georeferenced,georeferenced,operational,operational);
        List<SpaceRow> rows=jdbc.query("""
                SELECT DISTINCT sp.id,z.id zone_id,z.code zone_code,z.name zone_name,st.id street_id,st.code street_code,st.name street_name,
                       sp.code,sp.qr_code,sp.space_number,sp.latitude,sp.longitude,sp.space_type,sp.active,sp.created_at,sp.updated_at,
                       lower(z.code) zone_sort,lower(st.code) street_sort,lower(sp.space_number) space_sort,
                       CASE WHEN NOT (sp.active AND st.active AND z.active) THEN 'DISABLED' WHEN ps.status IS NULL THEN 'AVAILABLE' ELSE 'OCCUPIED' END operational_status
                """+joins+where+" ORDER BY zone_sort,street_sort,space_sort,sp.id LIMIT ? OFFSET ?",
                (r,n)->new SpaceRow(r.getObject("id",UUID.class),r.getObject("zone_id",UUID.class),r.getString("zone_code"),r.getString("zone_name"),r.getObject("street_id",UUID.class),r.getString("street_code"),r.getString("street_name"),r.getString("code"),r.getString("qr_code"),r.getString("space_number"),r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),r.getString("space_type"),r.getBoolean("active"),r.getString("operational_status"),r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class)),zoneId,zoneId,streetId,streetId,active,active,term,term,term,term,georeferenced,georeferenced,operational,operational,s,(long)p*s);
        return result(p,s,total,rows);
    }

    private static String like(String q) { return q==null||q.isBlank()?null:"%"+q.trim().toLowerCase(java.util.Locale.ROOT)+"%"; }
    private static int safePage(int page) { if(page<0) throw new IllegalArgumentException("La página debe ser cero o mayor."); return page; }
    private static int safeSize(int size) { if(size<1||size>100) throw new IllegalArgumentException("El tamaño de página debe estar entre 1 y 100."); return size; }
    private static <T> Page<T> result(int page,int size,Long total,List<T> rows) { long count=total==null?0:total; return new Page<>(page,size,count,(int)Math.ceil(count/(double)size),rows); }
}
