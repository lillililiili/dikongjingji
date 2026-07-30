UPDATE platform_city AS city
SET search_count = seed.search_count,
    version = city.version + 1,
    updated_at = now()
FROM (VALUES
    ('370100', 1260::bigint),
    ('370200', 1120::bigint),
    ('370500', 980::bigint),
    ('370600', 760::bigint),
    ('370700', 690::bigint),
    ('371300', 610::bigint)
) AS seed(city_code, search_count)
WHERE city.city_code = seed.city_code;
