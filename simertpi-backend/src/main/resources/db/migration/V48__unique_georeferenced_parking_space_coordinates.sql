DO $$
DECLARE
    duplicate_details TEXT;
    invalid_details TEXT;
BEGIN
    SELECT string_agg(format('%s: (%s, %s)', id, latitude, longitude), '; ')
      INTO invalid_details
      FROM (
          SELECT id, latitude, longitude
            FROM parking.parking_spaces
           WHERE (latitude IS NULL) <> (longitude IS NULL)
              OR (latitude IS NOT NULL AND (latitude < -90 OR latitude > 90))
              OR (longitude IS NOT NULL AND (longitude < -180 OR longitude > 180))
           ORDER BY id
           LIMIT 10
      ) invalid_spaces;

    IF invalid_details IS NOT NULL THEN
        RAISE EXCEPTION 'V48 cannot be applied: incomplete or out-of-range existing coordinates require review: %', invalid_details
            USING ERRCODE = '23514';
    END IF;

    SELECT string_agg(format('(%s, %s): %s', latitude, longitude, space_ids), '; ')
      INTO duplicate_details
      FROM (
          SELECT latitude, longitude, string_agg(id::text, ', ' ORDER BY id) AS space_ids
            FROM parking.parking_spaces
           WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           GROUP BY latitude, longitude
          HAVING count(*) > 1
           ORDER BY latitude, longitude
           LIMIT 5
      ) duplicates;

    IF duplicate_details IS NOT NULL THEN
        RAISE EXCEPTION 'V48 cannot be applied: duplicate georeferenced parking spaces require municipal review: %', duplicate_details
            USING ERRCODE = '23505';
    END IF;
END;
$$;

ALTER TABLE parking.parking_spaces
    ADD CONSTRAINT ck_parking_spaces_coordinate_pair
        CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT ck_parking_spaces_latitude_range
        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_parking_spaces_longitude_range
        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180);

CREATE UNIQUE INDEX uq_parking_spaces_coordinates
    ON parking.parking_spaces (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;

COMMENT ON INDEX parking.uq_parking_spaces_coordinates IS
    'Prevents two spaces with a complete, normalized coordinate pair from sharing the same physical location.';
