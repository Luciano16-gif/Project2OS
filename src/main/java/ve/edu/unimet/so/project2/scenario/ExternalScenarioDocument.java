package ve.edu.unimet.so.project2.scenario;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;

public record ExternalScenarioDocument(
        @JsonProperty("test_id") String testId,
        @JsonProperty("initial_head") Integer initialHead,
        @JsonProperty("direction") String direction,
        @JsonProperty("requests") RequestData[] requests,
        @JsonProperty("system_files") LinkedHashMap<String, SystemFileData> systemFiles) {

    public record RequestData(
            @JsonProperty("pos") Integer pos,
            @JsonProperty("op") String op,
            @JsonProperty("name") String name,
            @JsonProperty("blocks") Integer blocks) {
    }

    public record SystemFileData(
            @JsonProperty("name") String name,
            @JsonProperty("blocks") Integer blocks) {
    }
}
