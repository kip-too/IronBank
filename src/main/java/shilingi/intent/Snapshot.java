package shilingi.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds the JSON document that an {@link Intent} carries as its evidence.
 *
 * <p>SPEC.md section 6: the snapshot is <b>a copy, not a reference</b>. "The data will have
 * changed by the time anyone reads the log." So this produces text, once, and that text is what
 * is stored - nothing here holds a live object that could be read again later and give a
 * different answer.
 *
 * <h2>Keys are sorted</h2>
 * Two snapshots of the same facts produce byte-identical text. That matters for a log whose
 * purpose is comparison: a reviewer diffing yesterday's intent against today's should see the
 * differences that are real, not the ones Java's map iteration order invented.
 *
 * <h2>No new dependency</h2>
 * Jackson is already on the compile classpath by way of Spring Boot itself - confirmed, not
 * assumed - so this adds nothing to the build. The {@link ObjectMapper} is constructed here
 * rather than injected, so that its configuration is visible at the point of use and cannot be
 * changed underneath this class by some other part of the application.
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>No deserialisation.</b> Nothing reads a snapshot back into objects. A snapshot is
 *       read by a person asking why a decision was made, and turning it back into live objects
 *       would invite exactly the "just re-read the data" shortcut the copy exists to prevent.</li>
 *   <li><b>No schema.</b> What goes in a snapshot is the agent's business, on day 8. This only
 *       guarantees it is well-formed, stable and complete.</li>
 *   <li>Values are whatever Jackson makes of them. Money and rates should be put in as their
 *       minor units and their plain strings, never as floating point - the caller's job.</li>
 * </ul>
 */
public final class Snapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final Map<String, Object> facts = new LinkedHashMap<>();

    private Snapshot() {
    }

    public static Snapshot of() {
        return new Snapshot();
    }

    /** Records one fact. Keys are sorted on serialisation, so insertion order does not matter. */
    public Snapshot with(String key, Object value) {
        Objects.requireNonNull(key, "A snapshot fact needs a key");
        facts.put(key, value);
        return this;
    }

    /** The JSON text. Call it once and store the result; this is the copy. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(facts));
        } catch (JsonProcessingException e) {
            // A snapshot that cannot be written is a decision that cannot be explained, so this
            // fails loudly rather than storing an empty document or a placeholder.
            throw new IllegalArgumentException(
                    "This snapshot cannot be serialised, so the decision it belongs to could not "
                    + "be explained later. Put plain values in it - numbers, strings, lists and maps.", e);
        }
    }

    /** Serialises an already-built map. Same guarantees. */
    public static String json(Map<String, Object> facts) {
        Snapshot snapshot = new Snapshot();
        snapshot.facts.putAll(facts);
        return snapshot.toJson();
    }
}
