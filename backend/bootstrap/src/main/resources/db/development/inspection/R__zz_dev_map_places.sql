-- Only development databases get example points. Coordinates are fixed per place, independent of the search origin.
INSERT INTO platform_map_place (city_code, place_key, name, address, location, sample, published, sort_order)
SELECT c.city_code, s.place_key, s.name, c.city_name || s.street || '（示例）',
       ST_SetSRID(ST_MakePoint(anchor.lon + s.dx, anchor.lat + s.dy), 4326)::geography, true, true, s.sort_order
FROM (VALUES
    ('110100', 116.4074, 39.9042), ('310100', 121.4737, 31.2304),
    ('370100', 117.1201, 36.6512), ('370200', 120.3826, 36.0671),
    ('370500', 118.6749, 37.4347), ('370700', 119.1618, 36.7069),
    ('440100', 113.2644, 23.1291), ('440300', 114.0579, 22.5431)
) AS anchor(code, lon, lat)
JOIN platform_city c ON c.city_code = anchor.code
CROSS JOIN (VALUES
    ('road-88', '示例路 88 号', '示例路 88 号', 0.000, 0.000, 10),
    ('park-gate-3', '示例产业园 3 号门', '示例路 96 号', -0.003, 0.002, 20),
    ('logistics', '示例物流园区', '示例港路 6 号', 0.009, 0.008, 30)
) AS s(place_key, name, street, dx, dy, sort_order)
ON CONFLICT (city_code, place_key) DO NOTHING;
