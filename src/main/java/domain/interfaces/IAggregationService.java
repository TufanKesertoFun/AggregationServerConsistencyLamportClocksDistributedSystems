package domain.interfaces;

import java.util.Map;
import java.util.Optional;

public interface IAggregationService {
    void put(String sourceId, int lamport, Map<String,Object> data);
    Map<String, Map<String,Object>> getAll();
    Optional<Map<String,Object>> getById(String id); //optional means return value can be empty but not null

}
