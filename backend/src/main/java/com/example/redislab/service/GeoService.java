package com.example.redislab.service;

import com.example.redislab.model.DemoResult;
import com.example.redislab.support.Demo;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Geospatial indexes: store coordinates in a sorted set and query by radius. */
@Service
public class GeoService {

    private static final String KEY = "lab:geo:stores";

    /** Store name → {longitude, latitude}. */
    static final Map<String, double[]> STORES = new LinkedHashMap<>();

    static {
        STORES.put("Seattle", new double[]{-122.3321, 47.6062});
        STORES.put("Portland", new double[]{-122.6765, 45.5231});
        STORES.put("San Francisco", new double[]{-122.4194, 37.7749});
        STORES.put("Los Angeles", new double[]{-118.2437, 34.0522});
        STORES.put("San Diego", new double[]{-117.1611, 32.7157});
        STORES.put("Las Vegas", new double[]{-115.1398, 36.1699});
        STORES.put("Phoenix", new double[]{-112.074, 33.4484});
        STORES.put("Salt Lake City", new double[]{-111.891, 40.7608});
        STORES.put("Denver", new double[]{-104.9903, 39.7392});
        STORES.put("Albuquerque", new double[]{-106.6504, 35.0844});
        STORES.put("Dallas", new double[]{-96.797, 32.7767});
        STORES.put("Austin", new double[]{-97.7431, 30.2672});
        STORES.put("Houston", new double[]{-95.3698, 29.7604});
        STORES.put("Kansas City", new double[]{-94.5786, 39.0997});
        STORES.put("Minneapolis", new double[]{-93.265, 44.9778});
        STORES.put("St. Louis", new double[]{-90.1994, 38.627});
        STORES.put("Chicago", new double[]{-87.6298, 41.8781});
        STORES.put("Milwaukee", new double[]{-87.9065, 43.0389});
        STORES.put("Indianapolis", new double[]{-86.1581, 39.7684});
        STORES.put("Detroit", new double[]{-83.0458, 42.3314});
        STORES.put("Nashville", new double[]{-86.7816, 36.1627});
        STORES.put("Atlanta", new double[]{-84.388, 33.749});
        STORES.put("Miami", new double[]{-80.1918, 25.7617});
        STORES.put("Orlando", new double[]{-81.3792, 28.5383});
        STORES.put("Charlotte", new double[]{-80.8431, 35.2271});
        STORES.put("Washington", new double[]{-77.0369, 38.9072});
        STORES.put("Philadelphia", new double[]{-75.1652, 39.9526});
        STORES.put("New York", new double[]{-74.006, 40.7128});
        STORES.put("Boston", new double[]{-71.0589, 42.3601});
        STORES.put("Pittsburgh", new double[]{-79.9959, 40.4406});
    }

    private final StringRedisTemplate redis;

    public GeoService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public DemoResult search(double longitude, double latitude, double radiusKm) {
        Demo demo = Demo.start("geo");
        Map<String, Point> points = new LinkedHashMap<>();
        STORES.forEach((name, lonLat) -> points.put(name, new Point(lonLat[0], lonLat[1])));
        Long added = redis.opsForGeo().add(KEY, points);
        demo.cmd("GEOADD " + KEY + " -122.3321 47.6062 Seattle … (" + points.size() + " stores)  → " + added + " new");

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(KEY,
                GeoReference.fromCoordinate(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().includeCoordinates().sortAscending().limit(10));
        demo.cmd(String.format("GEOSEARCH %s FROMLONLAT %.4f %.4f BYRADIUS %.0f km ASC COUNT 10 WITHDIST WITHCOORD  → %d",
                KEY, longitude, latitude, radiusKm, results == null ? 0 : results.getContent().size()));
        List<Map<String, Object>> nearby = new ArrayList<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("name", result.getContent().getName());
                hit.put("distanceKm", Math.round(result.getDistance().getValue() * 10) / 10.0);
                hit.put("lon", result.getContent().getPoint().getX());
                hit.put("lat", result.getContent().getPoint().getY());
                nearby.add(hit);
            }
        }
        Distance nyToLa = redis.opsForGeo().distance(KEY, "New York", "Los Angeles", Metrics.KILOMETERS);
        demo.cmd("GEODIST " + KEY + " \"New York\" \"Los Angeles\" km  → " + (nyToLa == null ? "?" : Math.round(nyToLa.getValue())));
        List<String> hash = redis.opsForGeo().hash(KEY, "Chicago");
        demo.cmd("GEOHASH " + KEY + " Chicago  → " + hash);

        List<Map<String, Object>> stores = new ArrayList<>();
        STORES.forEach((name, lonLat) -> stores.add(Map.of("name", name, "lon", lonLat[0], "lat", lonLat[1])));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("center", Map.of("lon", longitude, "lat", latitude));
        data.put("radiusKm", radiusKm);
        data.put("stores", stores);
        data.put("nearby", nearby);
        data.put("newYorkToLosAngelesKm", nyToLa == null ? null : Math.round(nyToLa.getValue()));
        boolean sorted = true;
        for (int i = 1; i < nearby.size(); i++) {
            sorted &= (double) nearby.get(i - 1).get("distanceKm") <= (double) nearby.get(i).get("distanceKm");
        }
        demo.check("Results are nearest first", sorted, nearby.size() + " stores");
        demo.check("Every result is inside the radius", nearby.stream().allMatch(h -> (double) h.get("distanceKm") <= radiusKm),
                "radius " + radiusKm + " km");
        demo.check("GEODIST New York ↔ Los Angeles ≈ 3,940 km", nyToLa != null && Math.abs(nyToLa.getValue() - 3940) < 30,
                nyToLa == null ? "?" : Math.round(nyToLa.getValue()) + " km");
        return demo.finish(data);
    }
}
